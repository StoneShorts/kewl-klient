package kewl.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Units;

/**
 * What {@link net.runelite.client.config.ConfigManager} throws away when it flattens a RuneLite config
 * interface into kewl settings: the {@code @ConfigSection} grouping, the {@code @ConfigItem position},
 * whether an int is really a {@code Keybind}, and the {@code @Units} suffix.
 *
 * <p>The shim cannot be extended from here, so the panel recovers the metadata itself: for a wrapped
 * RuneLite plugin it finds the plugin's {@code @ConfigGroup} config interface by reflection and reads
 * the annotations directly -- the exact walk RuneLite's own {@code ConfigManager.getConfigDescriptor}
 * does (sections from annotated String fields, items from annotated methods). A plain kewl plugin has
 * no interface and gets {@link Meta#EMPTY}, which renders as one unsectioned list in declaration order.</p>
 *
 * <p>Looked up once per plugin and cached; the annotations cannot change at runtime.</p>
 */
public final class RlConfigMeta {

    private RlConfigMeta() {}

    /** One collapsible section: the key items refer to, plus what the header shows. */
    public record Section(String key, String name, String description, int position, boolean closedByDefault) {}

    /** Everything the panel needs about one plugin's RuneLite-side config. Immutable, so it is shared freely. */
    public static final class Meta {
        static final Meta EMPTY = new Meta(Map.of(), Map.of(), Map.of(), Set.of(), Map.of());

        final Map<String, Section> sections;   // section key -> section
        final Map<String, Integer> positions;  // item key -> @ConfigItem position
        final Map<String, String> itemSection; // item key -> @ConfigItem section (the raw key)
        final Set<String> keybinds;            // item keys whose RuneLite type was Keybind
        final Map<String, String> units;       // item key -> @Units suffix for the value display

        Meta(Map<String, Section> sections, Map<String, Integer> positions, Map<String, String> itemSection,
             Set<String> keybinds, Map<String, String> units) {
            this.sections = sections;
            this.positions = positions;
            this.itemSection = itemSection;
            this.keybinds = keybinds;
            this.units = units;
        }

        boolean isEmpty() { return positions.isEmpty() && sections.isEmpty(); }

        // -- read accessors. Package-private fields would lock the metadata to kewl.ui, and the ImGui
        //    panel bridge (kewl.panel) needs exactly these three answers when it packs the model.

        /** True when the shim stored this item as a RuneLite {@link Keybind} (an F-index INT). */
        public boolean isKeybind(String key) { return keybinds.contains(key); }

        /** The {@code @Units} suffix shown after the value, or "" for none. */
        public String units(String key) { return units.getOrDefault(key, ""); }

        /**
         * The section a setting sits in, or null when it is loose above the sections (or the plugin has
         * no config interface at all). Returns the Section, so callers choose key or display name.
         */
        public Section section(String key) {
            String k = itemSection.get(key);
            return k == null ? null : sections.get(k);
        }
    }

    private static final Map<kewl.Plugin, Meta> CACHE = new ConcurrentHashMap<>();

    public static Meta of(kewl.Plugin plugin) {
        return CACHE.computeIfAbsent(plugin, RlConfigMeta::discover);
    }

    /** The plugin's sections, in RuneLite's order: position, then name. */
    public static List<Section> sections(kewl.Plugin plugin) {
        List<Section> out = new ArrayList<>(of(plugin).sections.values());
        out.sort(Comparator.comparingInt(Section::position).thenComparing(Section::name));
        return out;
    }

    /**
     * The wrapped RuneLite plugin, if this adapter has one. Found by field type rather than by naming
     * kewl.rl.RlitePlugin, so the panel stays ignorant of how the adapter is built.
     */
    private static Object wrappedRlPlugin(kewl.Plugin plugin) throws IllegalAccessException {
        for (Class<?> c = plugin.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getType().getName().equals("net.runelite.client.plugins.Plugin")) continue;
                f.setAccessible(true);
                return f.get(plugin);
            }
        }
        return null;
    }

    /** The config interface of a RuneLite plugin: the first @ConfigGroup interface among its fields' types. */
    private static Class<?> configInterface(Class<?> rlPlugin) {
        for (Class<?> c = rlPlugin; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                Class<?> t = f.getType();
                if (t.isInterface() && t.isAnnotationPresent(ConfigGroup.class)) return t;
            }
        }
        return null;
    }

    private static Meta discover(kewl.Plugin plugin) {
        try {
            Object rl = wrappedRlPlugin(plugin);
            if (rl == null) return Meta.EMPTY;
            Class<?> iface = configInterface(rl.getClass());
            if (iface == null) return Meta.EMPTY;

            // Sections: declared String fields annotated @ConfigSection; the field's VALUE is the key
            // items refer to. Same rule as ConfigManager.getConfigDescriptor.
            Map<String, Section> sections = new HashMap<>();
            for (Field f : iface.getDeclaredFields()) {
                ConfigSection cs = f.getAnnotation(ConfigSection.class);
                if (cs == null || f.getType() != String.class) continue;
                f.setAccessible(true);
                String key = String.valueOf(f.get(null));
                sections.put(key, new Section(key, cs.name(), cs.description(), cs.position(), cs.closedByDefault()));
            }

            Map<String, Integer> positions = new HashMap<>();
            Map<String, String> itemSection = new HashMap<>();
            Set<String> keybinds = new HashSet<>();
            Map<String, String> units = new HashMap<>();
            for (Method m : iface.getMethods()) {
                ConfigItem item = m.getAnnotation(ConfigItem.class);
                if (item == null) continue;
                positions.put(item.keyName(), item.position());
                itemSection.put(item.keyName(), item.section());
                if (m.getReturnType() == Keybind.class) keybinds.add(item.keyName());
                Units u = m.getAnnotation(Units.class);
                if (u != null) units.put(item.keyName(), u.value());
            }
            return new Meta(sections, positions, itemSection, keybinds, units);
        } catch (Throwable t) {
            // Metadata is decoration; a plugin whose config cannot be introspected still gets a plain list.
            System.out.println("[panel] no config metadata for " + plugin.name() + ": " + t);
            return Meta.EMPTY;
        }
    }
}

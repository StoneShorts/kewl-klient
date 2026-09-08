package kewl.config;

import java.awt.Color;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A plugin's settings.
 *
 * <p>Declare them once, read them whenever. The control panel builds itself from whatever you declared,
 * in the order you declared it, so no plugin ever writes a line of Swing:</p>
 *
 * <pre>{@code
 *   public Woodcutter() {
 *       config.bool("notify", "Tell me when I stop", "Print a line when the bot goes idle", true);
 *       config.number("radius", "Search radius", "How far to look for a tree", 8, 1, 20);
 *       config.colour("marker", "Highlight colour", "", new Color(90, 220, 120));
 *   }
 *
 *   public void tick() {
 *       int radius = config.number("radius");
 *   }
 * }</pre>
 *
 * <p>Insertion-ordered, because the order you declare settings in is the order they should appear, and
 * an alphabetised config panel puts "Colour" above "Enabled" for no reason anyone wanted.</p>
 */
public final class Config {

    private final Map<String, Setting> settings = new LinkedHashMap<>();

    private Setting add(Setting s) {
        settings.put(s.key(), s);
        return s;
    }

    /** A checkbox. */
    public Setting bool(String key, String label, String description, boolean def) {
        return add(new Setting(key, label, description, Setting.Kind.BOOL, def, 0, 0));
    }

    /** A slider, clamped to {@code min..max}. */
    public Setting number(String key, String label, String description, int def, int min, int max) {
        return add(new Setting(key, label, description, Setting.Kind.INT, def, min, max));
    }

    /** A text box. */
    public Setting text(String key, String label, String description, String def) {
        return add(new Setting(key, label, description, Setting.Kind.TEXT, def, 0, 0));
    }

    /**
     * A text box whose value is a secret: masked wherever the panels draw it and wherever anything
     * logs it, editable only in a password-mode field. Otherwise exactly a {@link #text} setting --
     * same kind, same persistence through the profile store, same reset and listeners -- so a
     * plugin that declares one accepts that the value lives in the profile's config.json.
     */
    public Setting secret(String key, String label, String description, String def) {
        return add(new Setting(key, label, description, Setting.Kind.TEXT, def, 0, 0, null, true));
    }

    /** A colour swatch you can click to open a picker. */
    public Setting colour(String key, String label, String description, Color def) {
        return add(new Setting(key, label, description, Setting.Kind.COLOR, def, 0, 0));
    }

    /** A drop-down of an enum's constants, in declaration order. */
    public Setting enumeration(String key, String label, String description, Enum<?> def) {
        Object[] options = def.getClass().getEnumConstants();
        return add(new Setting(key, label, description, Setting.Kind.ENUM, def, 0, 0, options));
    }

    /** Everything declared, in declaration order. The control panel walks this. */
    public Collection<Setting> all() { return settings.values(); }

    /** True when nothing has been declared -- the panel uses this to skip the config section. */
    public boolean isEmpty() { return settings.isEmpty(); }

    /** One setting by key, or null. */
    public Setting get(String key) { return settings.get(key); }

    // -- reading. Each returns a harmless default for an unknown key rather than throwing, so a typo
    //    shows up as a plugin that does nothing instead of an exception thirty times a second.

    public boolean bool(String key) {
        Setting s = settings.get(key);
        return s != null && s.asBool();
    }

    public int number(String key) {
        Setting s = settings.get(key);
        return s == null ? 0 : s.asInt();
    }

    public String text(String key) {
        Setting s = settings.get(key);
        return s == null ? "" : s.asText();
    }

    public Color colour(String key) {
        Setting s = settings.get(key);
        return s == null ? Color.WHITE : s.asColor();
    }
}

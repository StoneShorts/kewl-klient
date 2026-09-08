package kewl.panel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import kewl.KewlKlient;
import kewl.Natives;
import kewl.Plugin;
import kewl.api.Game;
import kewl.api.Local;
import kewl.config.Setting;
import kewl.ui.RlConfigMeta;
import net.runelite.client.config.Keybind;

/**
 * The data half of the ImGui launcher panel: everything the launcher process draws, packed into one
 * int array, plus the edits it sends back.
 *
 * <p>The launcher is a separate process. It cannot touch these objects, so the injected DLL pulls a
 * snapshot from here over JNI, copies it into the shared-memory bridge ("Local\KewlKlientBridge-&lt;pid&gt;",
 * see the C++ side), and the launcher renders from that. Edits travel the other way: the launcher
 * writes an edit record, the DLL calls one of the {@code set*} methods here, and the edit lands in
 * {@link Setting#set} -- never in the value field, because the change listeners that hang off
 * {@code Setting.onChange} are how the RuneLite config shim's ConfigChanged events get posted, and an
 * edit that bypasses them silently does nothing to the plugin.</p>
 *
 * <h2>Packed format of {@link #snapshot()}</h2>
 *
 * <p>All values are 32-bit ints. Strings are a length (in BYTES) followed by the UTF-8 bytes packed
 * four to an int, lowest byte first, zero-padded -- so on the little-endian x86 we run under, the int
 * array from {@code (len+3)/4} onwards IS the byte string and the C++ side can memcpy straight out of
 * it. There is no terminator; the length is the truth.</p>
 *
 * <pre>
 *   MAGIC ('KKBR')                format tag, so a mismatched jar meets a loud error not garbage
 *   FORMAT (2)                    bumped when any layout below changes
 *   pluginCount
 *   per plugin:
 *     enabled (0/1), flags, hotkey (-1..7)
 *                   flags bit0 = has settings (this int WAS "hasConfig 0/1" and bit0 still is
 *                   exactly that, which is why the developer bit could be added without a FORMAT
 *                   bump -- the field's width, offset and old meaning are unchanged),
 *                   bit1 = developer scaffolding (kewl.Plugin.developer(): the panel groups these
 *                   under a "Developer" heading, sorted after everything else)
 *     str name (63)  str description (159)  str status (159)
 *     settingCount
 *     per setting:
 *       kind        0=bool 1=int 2=enum 3=keybind 4=color 5=text
 *                   (a Keybind is an INT setting with flag bit0; it is ALSO reported as kind 3 so
 *                    the launcher does not have to know about the flag)
 *       valueInt    bool 0/1, int/keybind the value, enum its option index, color 0xRRGGBB(A), text 0
 *       min, max    INT bounds; 0 for everything else
 *       enumIndex   index into options of the current value (0 when unknown), 0 for non-enums
 *       optionCount capped at 8
 *       flags       bit0 = keybind, bit1 = the value display carries a @Units suffix,
 *                   bit2 = secret (a TEXT setting the launcher must edit in a password field and
 *                   never draw in clear; valueText still carries the value, because the field
 *                   has to be able to show and edit it -- the MASKING is the launcher's job)
 *       str key (63)  str label (95)  str description (191)
 *       str section (63)   the SECTION HEADER's display name, "" when the setting sits loose above
 *                          the sections -- the launcher draws grouping from it
 *       str valueText (63) the value as the Java panel shows it ("on", "12 ms", "F3", "#5adc78")
 *       optionCount x str option (47)
 *   pinned[pluginCount]           0/1, index-parallel to the plugins above (global, not per profile)
 *   activeProfileIndex            -1 = none, else 0..profileCount-1
 *   profileCount
 *   per profile: str name (63)  str id (63)
 *   hubState                      0=idle 1=loading 2=error 3=ready
 *   str hubError (159)            "" unless hubState is 2
 *   hubCount
 *   per hub entry:
 *     str id (63)  str name (95)  str version (31)  str author (63)  str description (159)
 *     flags        bit0=installed bit1=hasUpdate bit2=install/remove in flight
 *     installedPluginIdx     index into the plugins above, or -1
 * </pre>
 *
 * <p>Length caps in the parentheses above match the C++ model region's fixed char fields, and are
 * applied HERE by {@link #utf8}, on byte boundaries -- a fixed-field strncpy on the C++ side could cut
 * a multi-byte UTF-8 character in half and hand the renderer a broken sequence.</p>
 *
 * <p>Everything after the plugin records is v2 (profiles, hub, pins), and it is read from the three
 * owners in turn: {@code kewl.profile.ProfileManager} for pins and profiles, {@code
 * kewl.plugin.hub.Hub} for the hub block. Both are optional at read time -- a session that never
 * installed either (the bare test suite) still gets a complete, well-formed tail of counts and empty
 * lists, so the launcher's parser never has to know the difference.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #snapshot} and {@link #modelRevision} are called on the DLL's thread, between frames.
 * Reads are lock-free and tolerate a concurrent frame ({@code enabled} is volatile, the rest are
 * references or ints that at worst arrive one frame stale). {@link #setBool} and friends deliberately
 * do NOT apply the edit on the calling thread: they queue it through {@link Plugin#later} so it runs
 * at the top of the next frame, which is the only thread that has ever touched plugin state -- today's
 * Java panel takes the same road for every click. The v2 commands (reset, pin, profile, hub) take the
 * same road, and for the same reason: they end up running lifecycle hooks and mutating the registry.</p>
 */
public final class PanelBridge {

    private PanelBridge() {}

    /** {@link #snapshot()}'s leading tag; the same 'KKBR' the C++ shared-memory header carries. */
    public static final int MAGIC = 0x4B424252;

    /** Bumped when the packed layout changes; the DLL refuses a snapshot whose FORMAT it does not know. */
    public static final int FORMAT = 2;

    /** Setting flag bit2: a secret TEXT setting -- see the layout comment. Bits 0/1 are packed inline. */
    public static final int FLAG_SECRET = 1 << 2;

    /**
     * Per-plugin flags word, the int the layout comment above once called "hasConfig".
     *
     * <p>Bit0 keeps that exact meaning, so the format did not have to move: every reader that tested
     * the old int for non-zero still sees a configurable plugin as non-zero, and the field is the
     * same width at the same offset. Bit1 is the developer mark. Mirrored on the C++ side as
     * {@code PLUGIN_FLAG_CONFIG} / {@code PLUGIN_FLAG_DEV} in {@code launcher/bridge_layout.hpp}.</p>
     */
    public static final int PLUGIN_FLAG_CONFIG = 1 << 0;

    /** @see #PLUGIN_FLAG_CONFIG */
    public static final int PLUGIN_FLAG_DEV = 1 << 1;

    /**
     * The most settings one plugin's record carries. Must match {@code MAX_SETTINGS_PER_PLUGIN} in
     * {@code client/bridge.hpp} (restated in {@code launcher/bridge_layout.hpp}): the DLL rejects an
     * over-cap snapshot WHOLE, which freezes every tab, so the walk clamps here rather than trusting
     * a plugin -- a hub-loaded jar can declare anything.
     */
    public static final int MAX_SETTINGS_PER_PLUGIN = 256;

    /** A hub entry with no installed plugin behind it: the bridge's "not installed" index. */
    public static final int NOT_INSTALLED = -1;

    /**
     * The key that turns a plugin on and off, instead of naming one of its settings. The launcher's
     * plugin switch sends this; there is no Setting behind it, so it is wired to
     * {@link Plugin#setEnabled} -- queued to the frame thread like every other edit.
     */
    public static final String ENABLE_KEY = "enabled";

    // --------------------------------------------------------------------------- revision

    /**
     * The model version the DLL polls to decide whether {@link #snapshot()} is worth re-pulling and
     * re-publishing.
     *
     * <p>Bit layout (nothing may decode these fields; the DLL only ever compares one revision against
     * another, so the split below is documentation rather than a contract):</p>
     *
     * <pre>
     *   bits 32..63  Setting.REVISION    every Setting.set since start
     *   bits 24..31  the status stamp    a plugin's status line changing text
     *   bits 16..23  Plugin.enableVersion every switch flip
     *   bits  8..15  ProfileManager.generation  profile CRUD, switches, pins
     *   bits  0..7   Hub.generation      manifest fetches, installs, removals
     * </pre>
     *
     * <p>The two generation counters are the v2 additions, and they exist because the model gained
     * sections that no Setting or switch can express: pinning a plugin, renaming a profile and
     * installing from the hub all change what the launcher draws without moving {@code
     * Setting.REVISION} or {@code enableVersion} at all. Without counters of their own, those changes
     * would never be republished. An 8-bit field wrapping to the same value needs 256 changes between
     * two polls a frame apart, which is not a thing that happens to a real panel.</p>
     */
    public static long modelRevision() {
        long profiles = 0, hub = 0;
        kewl.profile.ProfileManager pm = kewl.profile.ProfileManager.instance();
        if (pm != null) profiles = pm.generation();
        kewl.plugin.hub.Hub h = kewl.plugin.hub.Hub.instance();
        if (h != null) hub = h.generation();
        return (Setting.REVISION << 32)
                | ((statusStamp() & 0xFFL) << 24)
                | ((Plugin.enableVersion() & 0xFFL) << 16)
                | ((profiles & 0xFFL) << 8)
                | (hub & 0xFFL);
    }

    /**
     * Bumped whenever an enabled plugin's status line changes text. Polled by {@link #modelRevision},
     * so it costs a handful of string compares per poll -- every {@code status()} in this codebase is
     * a cached field read, never a memory walk.
     */
    private static long statusStamp() {
        try {
            for (Plugin p : KewlKlient.plugins()) {
                if (!p.isEnabled()) continue;
                String s = p.status();
                if (!sameAsLast(p, s)) return ++stamp;
            }
        } catch (Throwable t) {
            // A revision poll must never be the thing that kills the caller; worst case the launcher
            // shows a stale status line until the next change.
            System.out.println("[panel-bridge] status poll threw: " + t);
        }
        return stamp;
    }

    private static long stamp;
    private static final Map<Plugin, String> LAST_STATUS = new IdentityHashMap<>();

    private static boolean sameAsLast(Plugin p, String s) {
        String last = LAST_STATUS.put(p, s);
        return last == null ? s == null || s.isEmpty()   // the first poll is never a "change"
                            : last.equals(s);
    }

    // --------------------------------------------------------------------------- snapshot

    /**
     * The whole panel model, packed as the class comment describes. Every section is built into its
     * own buffer and guarded on its own, so one plugin whose {@code status()} throws costs that
     * plugin's record a placeholder -- never the launcher's whole model.
     *
     * <p>Returns null only when something escaped those guards (an allocation failure, a registry
     * that would not even produce a count). Null means "no snapshot this revision": the DLL keeps the
     * last good model and retries, rather than publishing what it was handed. It must NOT fall back
     * to a well-formed EMPTY model here, however tempting -- that parses fine, so the DLL's
     * keep-last-good recovery never engages, and the launcher blanks every tab (plugins, profiles AND
     * hub) until Java's revision next happens to move. The one case that matters is a hub plugin
     * that throws during the very publish its registration triggered: the user would lose the hub
     * tab they need to remove the plugin that broke it.</p>
     */
    public static int[] snapshot() {
        try {
            Buf b = new Buf();
            b.put(MAGIC);
            b.put(FORMAT);
            List<Plugin> plugins = KewlKlient.plugins();
            int pluginCount = plugins.size();
            if (pluginCount > kewl.plugin.PluginManager.MAX_PLUGINS) {
                // PluginManager.register refuses the over-cap plugin, but install() copies a caller's
                // list straight in, so the registry CAN be over the cap. Truncate rather than hand
                // the DLL a count it will reject wholesale -- and keep pinned[] index-parallel below.
                pluginCount = kewl.plugin.PluginManager.MAX_PLUGINS;
                b.put(pluginCount);
                System.out.println("[panel-bridge] registry holds " + plugins.size()
                        + " plugins; the model carries the first " + pluginCount);
            } else {
                b.put(pluginCount);
            }
            for (int i = 0; i < pluginCount; i++) {
                pluginGuarded(b, plugins.get(i));
            }
            // v2 tail. Each section reads from its own owner and is guarded on its own, so a hub
            // that throws cannot cost the launcher its profile list, and vice versa. The fallbacks
            // are complete sections, not truncations: a count the DLL cannot parse costs the whole
            // snapshot, so the fallback must be exactly as long as the real thing would have been.
            final int count = pluginCount;
            section(b, "pins", () -> pins(plugins, count), () -> zeros(count));
            section(b, "profiles", PanelBridge::profiles, PanelBridge::profilesEmpty);
            section(b, "hub", () -> hub(plugins, count), PanelBridge::hubEmpty);
            return b.trim();
        } catch (Throwable t) {
            // See the method comment: null = "keep the last good model", never an empty one.
            System.out.println("[panel-bridge] snapshot threw: " + t);
            t.printStackTrace();
            return null;
        }
    }

    /**
     * Builds one section into its own buffer and appends it, falling back to a same-shaped empty
     * section when the build throws. Built separately rather than straight into {@code b} because a
     * throw halfway through would leave a half-written section in the buffer, and the launcher's
     * parser would read every section after it four bytes off -- a worse lie than an empty list.
     */
    private static void section(Buf b, String what, java.util.function.Supplier<Buf> write,
                                java.util.function.Supplier<Buf> fallback) {
        Buf part;
        try {
            part = write.get();
        } catch (Throwable t) {
            System.out.println("[panel-bridge] snapshot section \"" + what + "\" threw: " + t);
            part = fallback.get();
        }
        b.append(part);
    }

    /** {@code n} zero ints: the shape a guarded section falls back to when its owner is unusable. */
    private static Buf zeros(int n) {
        Buf b = new Buf();
        for (int i = 0; i < n; i++) b.put(0);
        return b;
    }

    /** The pinned[] array, index-parallel to the plugin records. Global state, not per profile. */
    private static Buf pins(List<Plugin> plugins, int count) {
        Buf b = new Buf();
        kewl.profile.ProfileManager pm = kewl.profile.ProfileManager.instance();
        for (int i = 0; i < count; i++) {
            Plugin p = plugins.get(i);
            b.put(pm != null && pm.isPinned(p) ? 1 : 0);
        }
        return b;
    }

    /** The profiles block: the active index, then name-then-id per profile. */
    private static Buf profiles() {
        Buf b = new Buf();
        kewl.profile.ProfileManager pm = kewl.profile.ProfileManager.instance();
        if (pm == null) {
            b.put(NOT_INSTALLED);                       // activeProfileIndex: no profiles at all
            b.put(0);
            return b;
        }
        List<kewl.profile.Profile> list = pm.profiles();
        int n = Math.min(list.size(), kewl.profile.ProfileManager.MAX_PROFILES);
        int active = pm.activeIndex();
        b.put(active < n ? active : NOT_INSTALLED);     // never name a profile the clamp cut off
        b.put(n);
        for (int i = 0; i < n; i++) {
            kewl.profile.Profile p = list.get(i);
            b.putString(p.name(), 63);
            b.putString(p.id(), 63);
        }
        return b;
    }

    /** The profiles section's fallback: no active profile, an empty list. */
    private static Buf profilesEmpty() {
        Buf b = new Buf();
        b.put(NOT_INSTALLED);
        b.put(0);
        return b;
    }

    /**
     * The hub block: state, error, then the entries with their flags and installed index. {@code
     * pluginCount} is the count the model actually carries (see the clamp in {@link #snapshot}): an
     * installed plugin past it would be reported as an index the launcher's plugin list has no row
     * for, so it reads as not installed instead -- the hub tab's install button still works, which
     * is the part the user needs.
     */
    private static Buf hub(List<Plugin> plugins, int pluginCount) {
        Buf b = new Buf();
        kewl.plugin.hub.Hub h = kewl.plugin.hub.Hub.instance();
        if (h == null) {
            b.put(HUB_IDLE);                            // hubState: no hub in this jar
            b.putString("", 159);                       // hubError: none
            b.put(0);                                   // hubCount: none
            return b;
        }
        b.put(h.state().ordinal());
        b.putString(h.state() == kewl.plugin.hub.Hub.State.ERROR ? h.error() : "", 159);
        List<kewl.plugin.hub.HubEntry> entries = h.entries();
        int n = Math.min(entries.size(), kewl.plugin.hub.Hub.MAX_ENTRIES);
        b.put(n);
        for (int i = 0; i < n; i++) {
            kewl.plugin.hub.HubEntry e = entries.get(i);
            b.putString(e.id(), 63);
            b.putString(e.name(), 95);
            b.putString(e.version(), 31);
            b.putString(e.author(), 63);
            b.putString(e.description(), 159);
            b.put(h.flagsOf(e));
            Plugin installed = h.installedPlugin(e.id());
            int idx = installed == null ? NOT_INSTALLED : plugins.indexOf(installed);
            b.put(idx >= 0 && idx < pluginCount ? idx : NOT_INSTALLED);
        }
        return b;
    }

    /** The hub section's fallback: idle, no error, no entries. */
    private static Buf hubEmpty() {
        Buf b = new Buf();
        b.put(HUB_IDLE);
        b.putString("", 159);
        b.put(0);
        return b;
    }

    /** The hub states, restated from the bridge contract so the numbering is pinned on this side too. */
    private static final int HUB_IDLE = 0;

    /**
     * One plugin's flags word. {@code developer()} is plugin code like {@code status()} is, so it is
     * guarded the same way -- a plugin that throws here would otherwise tear the whole snapshot and
     * freeze every tab (the activeProfileIndex-of-1.7-billion failure, 2026-09-06). Not developer is
     * the honest fallback: the plugin still shows, in the main list.
     */
    private static int pluginFlags(Plugin p) {
        int flags = p.config.isEmpty() ? 0 : PLUGIN_FLAG_CONFIG;
        try {
            if (p.developer()) flags |= PLUGIN_FLAG_DEV;
        } catch (Throwable t) {
            System.out.println("[panel-bridge] plugin \"" + p.name() + "\" developer() threw: " + t);
        }
        return flags;
    }

    /**
     * One plugin's record, or a placeholder that keeps the record COUNT -- and therefore the plugin
     * INDEXES -- intact when the walk over that plugin throws. The launcher's edits name plugins by
     * index into the registry, so a record silently dropped from the middle would make every later
     * edit land on the wrong plugin: the one corruption this bridge must never produce. A placeholder
     * shows the user an inert row (and says so in its description) instead of someone else's config.
     */
    private static void pluginGuarded(Buf b, Plugin p) {
        try {
            plugin(b, p);
        } catch (Throwable t) {
            String name;
            try {
                name = p.name();
            } catch (Throwable t2) {
                name = "?";
            }
            System.out.println("[panel-bridge] plugin \"" + name + "\" threw while being read: " + t);
            b.put(0);                                        // enabled: unknowable, so off
            b.put(0);                                        // flags: no settings, not developer
            b.put(-1);                                       // hotkey: unbound
            b.putString(name, 63);
            b.putString("could not be read: " + t, 159);
            b.putString("", 159);
            b.put(0);                                        // settingCount
        }
    }

    private static void plugin(Buf b, Plugin p) {
        b.put(p.isEnabled() ? 1 : 0);
        b.put(pluginFlags(p));
        b.put(p.hotkey());
        b.putString(p.name(), 63);
        b.putString(p.description(), 159);
        // A plugin's status() is plugin code: if it throws mid-record the snapshot is torn from here
        // on and the launcher's reader lands on garbage (seen 2026-09-06 as an activeProfileIndex
        // of 1.7 billion when a status line hit a native that was not loaded). An empty status is
        // the honest fallback; the exception is the plugin's to fix, not the bridge's to propagate.
        String status;
        try { status = p.status(); } catch (Throwable t) { status = ""; }
        b.putString(status == null ? "" : status, 159);

        // Metadata comes from the same walk the Java panel draws from, so the two panels can never
        // disagree about what is a keybind, what carries units, or which section a setting sits in.
        RlConfigMeta.Meta meta = RlConfigMeta.of(p);

        List<Setting> settings = new ArrayList<>(p.config.all());
        if (settings.size() > MAX_SETTINGS_PER_PLUGIN) {
            // See MAX_SETTINGS_PER_PLUGIN: the DLL rejects an over-cap snapshot whole, so the tail is
            // cut here -- a panel showing the first 256 of a plugin's settings beats a frozen panel.
            if (!SETTINGS_CLAMP_LOGGED) {
                SETTINGS_CLAMP_LOGGED = true;
                System.out.println("[panel-bridge] plugin \"" + p.name() + "\" declares "
                        + settings.size() + " settings; the model carries the first "
                        + MAX_SETTINGS_PER_PLUGIN);
            }
            settings = settings.subList(0, MAX_SETTINGS_PER_PLUGIN);
        }
        b.put(settings.size());
        for (Setting s : settings) {
            setting(b, s, meta);
        }
    }

    /** Set once, for the settings-clamp line above: it would otherwise print on every republish. */
    private static boolean SETTINGS_CLAMP_LOGGED;

    private static void setting(Buf b, Setting s, RlConfigMeta.Meta meta) {
        boolean keybind = meta.isKeybind(s.key());
        String units = meta.units(s.key());
        RlConfigMeta.Section section = meta.section(s.key());
        Object[] options = s.kind() == Setting.Kind.ENUM ? s.options() : null;
        int optionCount = options == null ? 0 : Math.min(8, options.length);
        int index = optionCount == 0 ? 0 : indexOf(options, s.value());

        b.put(kind(s, keybind));
        b.put(valueInt(s, keybind, index));
        b.put(s.min());
        b.put(s.max());
        b.put(index);
        b.put(optionCount);
        b.put((keybind ? 1 : 0) | (units.isEmpty() ? 0 : 2) | (s.secret() ? FLAG_SECRET : 0));
        b.putString(s.key(), 63);
        b.putString(s.label(), 95);
        b.putString(s.description(), 191);
        b.putString(section == null ? "" : section.name(), 63);
        b.putString(valueText(s, keybind, units, options, index), 63);
        for (int i = 0; i < optionCount; i++) {
            b.putString(String.valueOf(options[i]), 47);
        }
    }

    private static int kind(Setting s, boolean keybind) {
        return switch (s.kind()) {
            case BOOL -> 0;
            case INT -> keybind ? 3 : 1;
            case ENUM -> 2;
            case COLOR -> 4;
            case TEXT -> 5;
        };
    }

    /** The current value as one int, in the shape the launcher's widgets edit in. */
    private static int valueInt(Setting s, boolean keybind, int enumIndex) {
        return switch (s.kind()) {
            case BOOL -> s.asBool() ? 1 : 0;
            case INT -> s.asInt();                     // keybinds are INTs underneath: the F-index
            case ENUM -> enumIndex;
            case COLOR -> s.asColor().getRGB();
            case TEXT -> 0;
        };
    }

    /** The value the way the Java panel renders it, so both panels show the same words. */
    private static String valueText(Setting s, boolean keybind, String units, Object[] options, int index) {
        return switch (s.kind()) {
            case BOOL -> s.asBool() ? "on" : "off";
            case INT -> keybind
                    ? (clampKeybind(s.asInt()) == 0 ? Keybind.NOT_SET.toString() : "F" + clampKeybind(s.asInt()))
                    : s.asInt() + units;
            case ENUM -> options == null || options.length == 0 ? "" : String.valueOf(options[index]);
            case COLOR -> String.format("#%06x", s.asColor().getRGB() & 0xFFFFFF);
            case TEXT -> s.asText();
        };
    }

    private static int clampKeybind(int f) { return Math.max(0, Math.min(8, f)); }

    /** Same rule as the Java panel's ConfigView: an unknown value reads as the first option, not a crash. */
    private static int indexOf(Object[] a, Object v) {
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals(v)) return i;
        }
        return 0;
    }

    // --------------------------------------------------------------------------- edits

    /**
     * Set a plugin's boolean setting, by index in {@link KewlKlient#plugins()} order. {@code key} may
     * be {@link #ENABLE_KEY}, which is the plugin's on/off switch rather than a setting.
     */
    public static void setBool(int pluginIdx, String key, boolean v) {
        if (ENABLE_KEY.equals(key)) {
            setEnabled(pluginIdx, v);
            return;
        }
        Setting s = setting(pluginIdx, key);
        if (s == null) return;
        Plugin.later(() -> s.set(v));
    }

    /** Set a numeric (or keybind: 0 = not set, 1..8 = F1..F8) setting. Setting.set clamps to range. */
    public static void setInt(int pluginIdx, String key, int v) {
        Setting s = setting(pluginIdx, key);
        if (s == null) return;
        Plugin.later(() -> s.set(v));
    }

    /** Select option {@code idx} of an enum setting; out of range is dropped, not wrapped into garbage. */
    public static void setEnum(int pluginIdx, String key, int idx) {
        Setting s = setting(pluginIdx, key);
        if (s == null) return;
        Object[] options = s.options();
        if (options == null || idx < 0 || idx >= options.length) {
            System.out.println("[panel-bridge] setEnum: option " + idx + " out of range for "
                    + pluginIdx + "/" + key);
            return;
        }
        Plugin.later(() -> s.set(options[idx]));
    }

    /** Replace a text setting's value. */
    public static void setText(int pluginIdx, String key, String v) {
        Setting s = setting(pluginIdx, key);
        if (s == null) return;
        Plugin.later(() -> s.set(v == null ? "" : v));
    }

    /**
     * Look a setting up the way the edit records name it: plugin index, then the Setting's key --
     * or {@link #ENABLE_KEY}, which is the plugin's on/off switch and not a Setting at all.
     */
    private static Setting setting(int pluginIdx, String key) {
        try {
            List<Plugin> plugins = KewlKlient.plugins();
            if (pluginIdx < 0 || pluginIdx >= plugins.size()) {
                System.out.println("[panel-bridge] edit for plugin " + pluginIdx + ": no such plugin");
                return null;
            }
            Plugin p = plugins.get(pluginIdx);
            Setting s = p.config.get(key);
            if (s == null) {
                System.out.println("[panel-bridge] edit for plugin " + pluginIdx
                        + ": no setting \"" + key + "\"");
            }
            return s;
        } catch (Throwable t) {
            System.out.println("[panel-bridge] edit lookup threw: " + t);
            return null;
        }
    }

    /**
     * The plugin on/off switch, named by {@link #ENABLE_KEY}. Queued like the setting edits: it runs
     * {@code onEnable}/{@code onDisable}, which build and tear down game-touching state, and those
     * have only ever run on the frame thread. Routed through the {@code PluginManager}, which is the
     * only thing allowed to transition a plugin -- this method is a transport, not an owner.
     */
    public static void setEnabled(int pluginIdx, boolean on) {
        try {
            List<Plugin> plugins = KewlKlient.plugins();
            if (pluginIdx < 0 || pluginIdx >= plugins.size()) {
                System.out.println("[panel-bridge] enable for plugin " + pluginIdx + ": no such plugin");
                return;
            }
            Plugin p = plugins.get(pluginIdx);
            kewl.plugin.PluginManager m = kewl.plugin.PluginManager.instance();
            Plugin.later(() -> {
                if (m != null) m.setEnabled(p, on);
                else p.setEnabled(on);              // no manager yet: the pre-manager inline transition
            });
        } catch (Throwable t) {
            System.out.println("[panel-bridge] enable threw: " + t);
        }
    }

    // --------------------------------------------------------------------------- v2 commands

    // Every method below is one edit kind from the bridge contract, and every one is a COMMAND rather
    // than a value write: it resets something, moves a pin, switches a profile or drives the hub. They
    // all share the same shape on purpose -- bounds-check, queue through Plugin.later, and hand off to
    // the owner (PluginManager for config, ProfileManager for pins and profiles, Hub for the hub) --
    // because a command that lands anywhere else is a second source of truth, which is exactly what
    // this bridge exists to prevent. A session without the owner installed (the bare test suite, or a
    // jar mid-upgrade) drops the command with a log line rather than guessing.

    /** EDIT_RESET_SETTING: put one setting back to its declared value. */
    public static void resetSetting(int pluginIdx, String key) {
        Plugin p = pluginAt(pluginIdx, "reset");
        if (p == null) return;
        kewl.plugin.PluginManager m = kewl.plugin.PluginManager.instance();
        if (m == null) { noOwner("reset", key); return; }
        Plugin.later(() -> m.resetSetting(p, key));
    }

    /** EDIT_RESET_PLUGIN: every setting of one plugin back to its declared value. */
    public static void resetPlugin(int pluginIdx) {
        Plugin p = pluginAt(pluginIdx, "reset");
        if (p == null) return;
        kewl.plugin.PluginManager m = kewl.plugin.PluginManager.instance();
        if (m == null) { noOwner("reset", p.name()); return; }
        Plugin.later(() -> m.resetPlugin(p));
    }

    /** EDIT_SET_PIN: pin or unpin a plugin. Global state in the profile store, not per profile. */
    public static void setPinned(int pluginIdx, boolean pinned) {
        Plugin p = pluginAt(pluginIdx, "pin");
        if (p == null) return;
        kewl.profile.ProfileManager pm = kewl.profile.ProfileManager.instance();
        if (pm == null) { noOwner("pin", p.name()); return; }
        Plugin.later(() -> pm.setPinned(p, pinned));
    }

    /** EDIT_HUB_INSTALL: download, verify and load a hub plugin, then register it. */
    public static void hubInstall(String id) {
        kewl.plugin.hub.Hub h = kewl.plugin.hub.Hub.instance();
        if (h == null) { noOwner("hub install", id); return; }
        Plugin.later(() -> h.install(id));
    }

    /** EDIT_HUB_REMOVE: take an installed plugin back out and delete its jar. */
    public static void hubRemove(String id) {
        kewl.plugin.hub.Hub h = kewl.plugin.hub.Hub.instance();
        if (h == null) { noOwner("hub remove", id); return; }
        Plugin.later(() -> h.remove(id));
    }

    /** EDIT_HUB_REFRESH: re-fetch the manifest. */
    public static void hubRefresh() {
        kewl.plugin.hub.Hub h = kewl.plugin.hub.Hub.instance();
        if (h == null) { noOwner("hub refresh", ""); return; }
        Plugin.later(h::refresh);
    }

    /** EDIT_PROFILE_SWITCH: make profile {@code idx} active, stating its config onto the plugins. */
    public static void profileSwitch(int idx) {
        kewl.profile.ProfileManager pm = kewl.profile.ProfileManager.instance();
        if (pm == null) { noOwner("profile switch", "#" + idx); return; }
        Plugin.later(() -> pm.switchTo(idx));
    }

    /** EDIT_PROFILE_CREATE: a new profile, starting as the state the client is in right now. */
    public static void profileCreate(String name) {
        kewl.profile.ProfileManager pm = kewl.profile.ProfileManager.instance();
        if (pm == null) { noOwner("profile create", name); return; }
        Plugin.later(() -> pm.create(name));
    }

    /** EDIT_PROFILE_DELETE: delete a profile (never the last one) and its directory. */
    public static void profileDelete(int idx) {
        kewl.profile.ProfileManager pm = kewl.profile.ProfileManager.instance();
        if (pm == null) { noOwner("profile delete", "#" + idx); return; }
        Plugin.later(() -> pm.delete(idx));
    }

    /** EDIT_PROFILE_RENAME: rename a profile. Names are display-only, so nothing else moves. */
    public static void profileRename(int idx, String name) {
        kewl.profile.ProfileManager pm = kewl.profile.ProfileManager.instance();
        if (pm == null) { noOwner("profile rename", name); return; }
        Plugin.later(() -> pm.rename(idx, name));
    }

    /** EDIT_PROFILE_DUPLICATE: copy a profile, stored state and all. */
    public static void profileDuplicate(int idx) {
        kewl.profile.ProfileManager pm = kewl.profile.ProfileManager.instance();
        if (pm == null) { noOwner("profile duplicate", "#" + idx); return; }
        Plugin.later(() -> pm.duplicate(idx));
    }

    /** The plugin an edit names, or null with a log line -- the shared bounds check of the commands. */
    private static Plugin pluginAt(int pluginIdx, String what) {
        try {
            List<Plugin> plugins = KewlKlient.plugins();
            if (pluginIdx < 0 || pluginIdx >= plugins.size()) {
                System.out.println("[panel-bridge] " + what + " for plugin " + pluginIdx + ": no such plugin");
                return null;
            }
            return plugins.get(pluginIdx);
        } catch (Throwable t) {
            System.out.println("[panel-bridge] " + what + " lookup threw: " + t);
            return null;
        }
    }

    /** A command whose owner is not installed in this jar. Dropped, and said so. */
    private static void noOwner(String what, String detail) {
        System.out.println("[panel-bridge] " + what + " (" + detail + ") dropped: no owner installed");
    }

    // --------------------------------------------------------------------------- debug data

    /**
     * The debug tab's lines, for the launcher's version of {@code kewl.ui.DebugView}. One line per
     * read, and each read guarded on its own: a broken offset shows up as a "threw" line in the middle
     * of the tab instead of taking the whole readout -- and the same guard is what lets this be called
     * from anywhere, including a JVM that has no natives at all (the test suite).
     */
    public static List<String> debugLines() {
        List<String> out = new ArrayList<>();
        String ready = read(out, "ready", () -> String.valueOf(Game.ready()));
        if (ready != null && ready.endsWith("true")) {
            read(out, "you", () -> {
                Local me = Game.me();
                return me.worldX() + ", " + me.worldY() + " plane " + me.plane();
            });
            read(out, "hp/run", () -> Game.me().health() + " / " + Game.me().runEnergy() + "%");
            read(out, "npcs", () -> Game.npcs().size() + " in view");
            read(out, "inventory", PanelBridge::inventory);
        } else {
            // The memory-read lines above mean nothing before the scene is up; say so rather than
            // present zeros that look like data.
            out.add("you: -");
            out.add("hp/run: -");
            out.add("npcs: -");
            out.add("inventory: -");
        }
        // The pathfinder self-check is the one line here that PROVES something without a login, so it
        // runs (once) whether or not the game is up.
        out.add("pathcheck: " + pathcheck());
        return out;
    }

    /** Adds "{@code label: value}", or "{@code label: threw ...}" if reading the value blew up. */
    private static String read(List<String> out, String label, java.util.function.Supplier<String> read) {
        String v;
        try {
            v = read.get();
        } catch (Throwable t) {
            v = "threw: " + t;
        }
        String line = label + ": " + v;
        out.add(line);
        return line;
    }

    /** Same summary DebugView draws: "N slots: id x qty ...", capped so it stays one line-ish. */
    private static String inventory() {
        int[] inv = Natives.container(93);
        if (inv == null || inv.length == 0) return "(empty or container not found)";
        StringBuilder sb = new StringBuilder(inv.length / 2 + " slots:");
        for (int i = 0; i * 2 < inv.length && i < 8; i++) {
            sb.append(' ').append(inv[2 * i]).append('x').append(inv[2 * i + 1]);
        }
        if (inv.length / 2 > 8) sb.append(" ...");
        return sb.toString();
    }

    /**
     * The Lumbridge-&gt;Varrock route, run once per session exactly as DebugView does it. It costs a
     * few hundred ms of whatever thread calls this the first time -- the honest price of a real answer
     * instead of a guess, and the launcher only asks when its debug tab is open.
     */
    private static String pathcheck() {
        if (!PATHCHECK_DONE) {
            PATHCHECK_DONE = true;
            kewl.rl.PathCheck.run();
        }
        return kewl.rl.PathCheck.result();
    }

    private static boolean PATHCHECK_DONE;

    // --------------------------------------------------------------------------- packing

    /** UTF-8 bytes of {@code s}, cut to at most {@code max} bytes WITHOUT splitting a character. */
    private static byte[] utf8(String s, int max) {
        if (s == null) s = "";
        byte[] all = s.getBytes(StandardCharsets.UTF_8);
        if (all.length <= max) return all;
        int cut = max;
        while (cut > 0 && (all[cut] & 0xC0) == 0x80) cut--;   // back over any continuation bytes
        return Arrays.copyOf(all, cut);
    }

    /**
     * A growable int buffer with the string packing on it. Bytes go four to an int, lowest byte in the
     * lowest bits: on the little-endian machines this ships on, {@code (byte*)&ints[i]} is the string.
     */
    private static final class Buf {
        private int[] a = new int[512];
        private int n;

        void put(int v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        /** Length word, then the bytes padded up to a whole number of ints (none at all for ""). */
        void putString(String s, int max) {
            byte[] b = utf8(s, max);
            put(b.length);
            int i = 0;
            for (; i + 4 <= b.length; i += 4) {
                put((b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF) << 16 | (b[i + 3] & 0xFF) << 24);
            }
            if (i < b.length) {
                int tail = 0;
                for (int k = 0; i + k < b.length; k++) tail |= (b[i + k] & 0xFF) << (8 * k);
                put(tail);
            }
        }

        /** Everything another buffer built, appended -- how a guarded section joins the snapshot. */
        void append(Buf other) {
            for (int i = 0; i < other.n; i++) put(other.a[i]);
        }

        int[] trim() { return n == a.length ? a : Arrays.copyOf(a, n); }
    }
}

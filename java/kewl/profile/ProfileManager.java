package kewl.profile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import kewl.Plugin;
import kewl.config.Setting;
import kewl.persist.JsonStore;
import kewl.plugin.PluginManager;

/**
 * Profiles: which plugin is on, and what its settings are, per way-of-playing -- persisted, so a
 * restart lands back where the user left it.
 *
 * <h2>What this replaces</h2>
 *
 * <p>Nothing, because there was nothing. The audit in {@code docs/architecture-before.md} is blunt
 * about it: "Persist. Nowhere. There is no disk layer" -- no properties file, no Preferences, no
 * JSON, and {@code kewl.ui.Profiles} held session-only snapshots in a static map. So there is no
 * legacy on-disk format to migrate from, and the migration story is the honest one: the first run
 * after this change creates a single "default" profile whose state is <i>empty</i>. Nothing is lost,
 * because nothing was ever written down.</p>
 *
 * <h2>A profile is a complete statement</h2>
 *
 * <p>Silence means "off, and at the declared defaults" -- not "keep whatever happens to be live".
 * That is what makes switching between profiles actually isolate them: a profile that never mentions
 * {@code radius} puts {@code radius} back where the code declared it, however the profile you came
 * from had it. It is self-consistent, too, because every real change is captured the moment it
 * happens ({@link #onEnabledChanged} and {@link #settingChanged}): a plugin you enabled while a
 * profile was active is in that profile's enabled map by the time you could switch away, so
 * "silent" can only describe a plugin the user never turned on or touched under that profile --
 * which is exactly the plugin that should be off with default settings there. The one place this
 * could surprise is a plugin registered mid-session (a hub install): it starts off in the active
 * profile until the user enables it, which is the safe direction to surprise in.</p>
 *
 * <h2>Layout on disk</h2>
 *
 * <pre>
 *   &lt;dataDir&gt;/profiles/index.json        the list, the active id, and the pins
 *   &lt;dataDir&gt;/profiles/&lt;id&gt;/config.json  that profile's enabled map and non-default settings
 * </pre>
 *
 * <p>Writes are atomic (tmp + rename, see {@link JsonStore}) and debounced: a change marks the store
 * dirty and a single background thread writes it out at most 750ms later, so a slider dragged for a
 * second costs one write, not sixty. {@link #flush} forces it for the places that need the write to
 * have happened (a profile switch persists the state it is leaving before it applies the next one) --
 * those run on the calling thread, which is normally the frame thread, and are rare, user-initiated
 * and one small file.</p>
 *
 * <h2>The pin decision</h2>
 *
 * <p><b>Pins are global, not per-profile.</b> A pin is a UI fact about the user ("these are the ones
 * I want at the top of the list"), not a fact about a way of playing; nobody wants their pinned
 * plugins to change because they switched from a skilling profile to a PvM one. They live in
 * index.json next to the profile list, not inside any profile, and the launcher's {@code SET_PIN}
 * edit lands here for that reason. If per-profile pins are ever wanted, this comment and the pins
 * block in {@link #indexJson} are the two places that change.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>State is mutated on the frame thread (registration, the manager's transitions, edits queued
 * through {@link Plugin#later}) and read by the IO thread when it writes. The rule is: the JSON
 * snapshot is BUILT under {@code lock}, and the file work happens AFTER it is released -- never the
 * other way round, because {@code lock} is the same one {@link Setting}'s persistence sink takes on
 * the frame thread, and the frame thread is the thread that runs every plugin tick, every overlay
 * and the whole panel bridge. A write that held the lock would stall all of that for the length of a
 * filesystem round trip, which under Wine on a slow path is not microseconds. The executor is a
 * single daemon thread -- one profile store, one writer, no interleaved files.</p>
 */
public final class ProfileManager implements Setting.Sink, PluginManager.Listener {

    private static final String SCHEMA = "version";
    private static final int SCHEMA_VERSION = 1;
    private static final long DEBOUNCE_MS = 750;

    /**
     * The most profiles that can exist. Must match {@code MAX_PROFILES} in {@code client/bridge.hpp}
     * (restated in {@code launcher/bridge_layout.hpp}): the shared-memory model carries a fixed
     * profile count, and the DLL rejects a snapshot that arrives over the cap WHOLE -- every tab
     * freezes at the last good model, not just the profiles list. Capping the owner is what keeps a
     * 33rd click from costing the user the whole panel.
     */
    public static final int MAX_PROFILES = 32;

    private static volatile ProfileManager instance;

    /** The installed store, or null before {@link #install}. */
    public static ProfileManager instance() { return instance; }

    private final Path root;                       // <dataDir>/profiles
    private final List<Profile> profiles = new ArrayList<>();
    private final Map<String, ProfileState> states = new LinkedHashMap<>();   // profile id -> state
    private final Map<String, Boolean> pins = new LinkedHashMap<>();          // plugin id -> pinned
    private String activeId;

    /** A Setting -> the plugin id that owns it, so an edit can be filed under the right plugin. */
    private final Map<Setting, String> owners = new IdentityHashMap<>();

    private final Object lock = new Object();
    private final ScheduledExecutorService io;
    private final AtomicBoolean flushPending = new AtomicBoolean();
    private volatile long generation;

    // Dirty flags, guarded by lock: what flush has not written out yet. Structural changes (profile
    // CRUD, pins, the active id) write index.json immediately because they are rare and user-visible;
    // setting/enable churn is what the debounce is for.
    private boolean indexDirty, profileDirty;

    private ProfileManager(Path root) {
        this.root = root;
        this.io = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kewl-profile-io");
            t.setDaemon(true);
            return t;
        });
    }

    // --------------------------------------------------------------------------- lifecycle

    /**
     * Install the store under {@code dataDir}, load whatever is there, and start persisting. Safe to
     * call once at start-up; calling it again replaces the store, which only the test suite does.
     */
    public static ProfileManager install(Path dataDir) {
        ProfileManager m = new ProfileManager(dataDir.resolve("profiles"));
        m.load();
        instance = m;
        Setting.setSink(m);
        return m;
    }

    /** Test hook: stop persisting and drop the store. Flushes first, so a test's writes are real. */
    public static void uninstall() {
        ProfileManager m = instance;
        if (m == null) return;
        instance = null;
        Setting.setSink(null);
        m.flush();
        m.io.shutdownNow();
    }

    // --------------------------------------------------------------------------- reading

    /** Every profile, in the order they appear in the panel. */
    public List<Profile> profiles() { return Collections.unmodifiableList(profiles); }

    /** The active profile, or null when there is none (which only a corrupt index produces). */
    public Profile active() {
        for (Profile p : profiles) if (p.id().equals(activeId)) return p;
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    /** Index of the active profile into {@link #profiles}, or -1 when there is none. */
    public int activeIndex() {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id().equals(activeId)) return i;
        }
        return -1;
    }

    /** Whether the profile keeps its own record of this plugin's switch (as opposed to "unknown"). */
    public boolean hasStoredEnabled(Plugin p) {
        ProfileState s = state(activeId);
        return s != null && s.enabled.containsKey(p.id());
    }

    /**
     * Whether the plugin is pinned. Global state -- see the class comment for why it is not part of a
     * profile. Unpinned is the default, including for plugins this store has never heard of.
     */
    public boolean isPinned(Plugin p) {
        return p != null && Boolean.TRUE.equals(pins.get(p.id()));
    }

    /** Bumped on every change the launcher's model needs to hear about: profile CRUD, switch, pin. */
    public long generation() { return generation; }

    // --------------------------------------------------------------------------- edits

    /**
     * Make {@code index} the active profile: the state being left is written out first, then the new
     * profile is stated onto the live plugins -- enabled where it says so, settings put back to
     * default where it is silent. A plugin whose state does not change does not get a second
     * {@code onEnable}/{@code onDisable} (see {@link PluginManager#setEnabled}'s idempotence).
     */
    public void switchTo(int index) {
        flush();                                    // the state we are leaving must be on disk first
        String targetId;
        Map<String, Object> json;
        synchronized (lock) {
            if (index < 0 || index >= profiles.size()) {
                System.out.println("[profile] switch to " + index + ": no such profile");
                return;
            }
            Profile target = profiles.get(index);
            if (target.id().equals(activeId)) return;
            activeId = target.id();
            targetId = activeId;
            json = indexJson();
            indexDirty = false;
        }
        JsonStore.write(root.resolve("index.json"), json);   // outside the lock; see the class comment
        applyState(states.get(targetId));
        bump();
        System.out.println("[profile] switched to " + profiles.get(index).name());
    }

    /**
     * A new, empty-in-intent profile that starts as a copy of how the world is right now. Snapshotting
     * the live state rather than starting blank means switching to it changes nothing until the user
     * does -- which is the least surprising thing a "new profile" button can do. (Blank would mean
     * every plugin silently reset to its code default on the first switch, which reads as data loss.)
     *
     * @return the new profile, or null when {@link #MAX_PROFILES} is already reached (the bridge
     *         cannot carry more; see the constant's comment)
     */
    public Profile create(String name) {
        Profile p;
        Map<String, Object> index, state;
        synchronized (lock) {
            if (profiles.size() >= MAX_PROFILES) {
                System.out.println("[profile] refusing to create profile " + MAX_PROFILES
                        + ": the panel bridge carries at most " + MAX_PROFILES);
                return null;
            }
            p = new Profile(nextId(), uniqueName(name));
            ProfileState s = new ProfileState();
            captureLiveStateInto(s);
            profiles.add(p);
            states.put(p.id(), s);
            index = indexJson();
            state = profileJson(s);
            indexDirty = false;
        }
        JsonStore.write(root.resolve(p.id()).resolve("config.json"), state);
        JsonStore.write(root.resolve("index.json"), index);
        bump();
        return p;
    }

    /** Rename a profile. Names are display-only, so this is an index.json write and nothing else. */
    public void rename(int index, String name) {
        Map<String, Object> json;
        synchronized (lock) {
            Profile p = at(index);
            if (p == null || name == null || name.isBlank()) return;
            p.rename(name.trim());
            json = indexJson();
            indexDirty = false;
        }
        JsonStore.write(root.resolve("index.json"), json);
        bump();
    }

    /** Copy a profile, stored state and all, as "<name> copy". */
    public Profile duplicate(int index) {
        Profile copy;
        Map<String, Object> indexJson, stateJson;
        synchronized (lock) {
            Profile src = at(index);
            if (src == null) return null;
            if (profiles.size() >= MAX_PROFILES) {
                System.out.println("[profile] refusing to duplicate past " + MAX_PROFILES
                        + ": the panel bridge carries at most " + MAX_PROFILES);
                return null;
            }
            ProfileState s = states.get(src.id());
            copy = new Profile(nextId(), uniqueName(src.name() + " copy"));
            profiles.add(copy);
            states.put(copy.id(), s == null ? new ProfileState() : s.copy());
            indexJson = indexJson();
            stateJson = profileJson(states.get(copy.id()));
            indexDirty = false;
        }
        JsonStore.write(root.resolve(copy.id()).resolve("config.json"), stateJson);
        JsonStore.write(root.resolve("index.json"), indexJson);
        bump();
        return copy;
    }

    /**
     * Delete a profile and its directory. Deleting the active one falls back to the first remaining
     * profile, and the last profile cannot be deleted at all -- a client with no profiles has no
     * state to load, and "the panel shows an empty list" is not a state worth supporting.
     */
    public void delete(int index) {
        Profile removed;
        boolean wasActive;
        Map<String, Object> json;
        synchronized (lock) {
            if (profiles.size() <= 1) {
                System.out.println("[profile] refusing to delete the last profile");
                return;
            }
            Profile p = at(index);
            if (p == null) return;
            profiles.remove(p);
            states.remove(p.id());
            wasActive = p.id().equals(activeId);
            if (wasActive) activeId = profiles.get(0).id();
            removed = p;
            json = indexJson();
            indexDirty = false;
        }
        JsonStore.write(root.resolve("index.json"), json);
        deleteProfileDir(removed);                  // file work, outside the lock like every write
        if (wasActive) applyState(states.get(activeId));
        bump();
    }

    /** Pin or unpin a plugin. Global state; see the class comment. */
    public void setPinned(Plugin p, boolean on) {
        if (p == null || isPinned(p) == on) return;
        Map<String, Object> json;
        synchronized (lock) {
            if (on) pins.put(p.id(), Boolean.TRUE); else pins.remove(p.id());
            json = indexJson();
            indexDirty = false;
        }
        JsonStore.write(root.resolve("index.json"), json);
        bump();
    }

    // --------------------------------------------------------------------------- listener (PluginManager)

    /**
     * A plugin joined the registry: file it under its id, then apply whatever the active profile says
     * about it. This is how an installed hub plugin comes back with the state it had, and how a
     * plugin that declares its settings late (the {@code RlitePlugin} adapters build their config in
     * the constructor, but an external plugin may not) still gets its stored values.
     */
    public void onRegistered(Plugin p) {
        synchronized (lock) {
            rebuildOwners();
            bump();          // the launcher's model just grew a record; its revision has to move
            ProfileState s = state(activeId);
            if (s == null) return;

            // Settings FIRST, then the switch (review 2026-09-06). onEnable is where a plugin reads
            // its config -- AutoLogin resolves its credential source there, the RlitePlugin adapters
            // call the port's startUp() -- so enabling before the stored values are in place ran that
            // once on the code defaults and only then delivered the profile's values as changes. The
            // startup log said "credentials: file, missing" for a profile that carries panel
            // credentials. The listener semantics are unchanged: applySettings still fires the same
            // per-key changes, they just land before the plugin is running rather than after.
            applySettings(p, s);

            Boolean on = s.enabled.get(p.id());
            if (on != null) {
                PluginManager mgr = PluginManager.instance();
                if (mgr != null) mgr.setEnabled(p, on);
            }
        }
    }

    /** A plugin left the registry (the hub's remove path): forget it, and move the revision. */
    public void onUnregistered(Plugin p) {
        synchronized (lock) {
            rebuildOwners();
            bump();
        }
    }

    /** The plugin's switch moved: record it in the active profile and schedule a write. */
    public void onEnabledChanged(Plugin p, boolean on) {
        synchronized (lock) {
            ProfileState s = stateForWriting(activeId);
            if (s == null) return;
            s.enabled.put(p.id(), on);
            profileDirty = true;
        }
        scheduleFlush();
    }

    /** {@link Setting.Sink}: a value changed somewhere. Filed under its owner, debounced to disk. */
    @Override
    public void settingChanged(Setting setting) {
        String pluginId;
        synchronized (lock) {
            pluginId = owners.get(setting);
            if (pluginId == null) return;                    // not a registered plugin's setting
            ProfileState s = stateForWriting(activeId);
            if (s == null) return;
            Map<String, Object> values = s.settings.computeIfAbsent(pluginId, k -> new LinkedHashMap<>());
            if (Objects.equals(setting.value(), setting.defaultValue())) {
                values.remove(setting.key());                // back to default: stop persisting an override
            } else {
                Object encoded = SettingCodec.encode(setting);
                if (encoded != null) values.put(setting.key(), encoded);
            }
            if (values.isEmpty()) s.settings.remove(pluginId, values);
            profileDirty = true;
        }
        scheduleFlush();
    }

    // --------------------------------------------------------------------------- applying state

    /**
     * State a whole profile onto the live plugins: every setting set to what the profile says, every
     * setting it is silent on put back to its declared value, and the switch moved only where it has
     * to move. This is the whole of "switch profile", minus the persistence.
     */
    private void applyState(ProfileState s) {
        PluginManager mgr = PluginManager.instance();
        if (mgr == null) return;
        for (Plugin p : mgr.plugins()) {
            // Silent about the plugin means off: see "a profile is a complete statement" above.
            Boolean on = s == null ? null : s.enabled.get(p.id());
            if (on == null) on = Boolean.FALSE;
            // Settings before the switch, same reason as onRegistered above (review 2026-09-06): a
            // plugin being turned ON by this profile switch must see the profile's values in its
            // onEnable, not the code defaults it would then be corrected away from. Harmless for a
            // plugin being turned off or left alone -- the two calls are independent.
            applySettings(p, s);
            if (on != p.isEnabled()) mgr.setEnabled(p, on);
        }
    }

    /** One plugin's settings, to what the profile says (or the default, where it says nothing). */
    private void applySettings(Plugin p, ProfileState s) {
        Map<String, Object> stored = s == null ? null : s.settings.get(p.id());
        for (Setting setting : p.config.all()) {
            Object decoded = stored == null ? null : SettingCodec.decode(setting, stored.get(setting.key()));
            if (decoded != null) {
                if (!Objects.equals(decoded, setting.value())) setting.set(decoded);
            } else if (!Objects.equals(setting.value(), setting.defaultValue())) {
                setting.reset();       // the profile is silent and we are not at default: it says default
            }
        }
    }

    /** Copy the live enabled/settings state of every registered plugin into a state object. */
    private void captureLiveStateInto(ProfileState s) {
        PluginManager mgr = PluginManager.instance();
        if (mgr == null) return;
        for (Plugin p : mgr.plugins()) {
            s.enabled.put(p.id(), p.isEnabled());
            Map<String, Object> values = null;
            for (Setting setting : p.config.all()) {
                if (Objects.equals(setting.value(), setting.defaultValue())) continue;
                Object encoded = SettingCodec.encode(setting);
                if (encoded == null) continue;
                if (values == null) values = new LinkedHashMap<>();
                values.put(setting.key(), encoded);
            }
            if (values != null) s.settings.put(p.id(), values);
        }
    }

    // --------------------------------------------------------------------------- persistence

    /**
     * Force any pending state to disk. The snapshot is built under {@code lock} and the files are
     * written after it is released -- a debounced save running here on the IO thread must not be able
     * to stall the frame thread's next {@link Setting#set}, and a flush from the frame thread (a
     * profile switch) must not stall the IO thread either.
     */
    public void flush() {
        Map<String, Object> index = null, profile = null;
        Path profilePath = null;
        synchronized (lock) {
            flushPending.set(false);
            if (indexDirty) {
                index = indexJson();
                indexDirty = false;
            }
            Profile active = active();
            ProfileState s = state(activeId);
            // An emptied state is still a write: the user reverted their last override, and the file
            // must stop claiming otherwise. Skipping empty states here would leave the old overrides
            // on disk to come back on the next start.
            if (profileDirty && active != null && s != null) {
                profile = profileJson(s);
                profilePath = root.resolve(active.id()).resolve("config.json");
                profileDirty = false;
            }
        }
        if (index != null) JsonStore.write(root.resolve("index.json"), index);
        if (profile != null) JsonStore.write(profilePath, profile);
    }

    private void scheduleFlush() {
        if (!flushPending.compareAndSet(false, true)) return;
        io.schedule(this::flushFromIo, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void flushFromIo() {
        flush();
    }

    /**
     * index.json's content. The caller holds {@code lock} and does the writing -- this only reads the
     * live list, the active id and the pins, and copying those into JSON is what the lock is for.
     */
    private Map<String, Object> indexJson() {
        List<Object> list = new ArrayList<>();
        for (Profile p : profiles) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", p.id());
            e.put("name", p.name());
            list.add(e);
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put(SCHEMA, SCHEMA_VERSION);
        json.put("active", activeId);
        json.put("profiles", list);
        if (!pins.isEmpty()) json.put("pins", new LinkedHashMap<>(pins));
        return json;
    }

    /** One profile's config.json: the enabled map and the settings that differ from their default. */
    private Map<String, Object> profileJson(ProfileState s) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put(SCHEMA, SCHEMA_VERSION);
        json.put("enabled", new LinkedHashMap<>(s.enabled));
        json.put("settings", new LinkedHashMap<>(s.settings));
        return json;
    }

    private void deleteProfileDir(Profile p) {
        try {
            Files.deleteIfExists(root.resolve(p.id()).resolve("config.json"));
            Files.deleteIfExists(root.resolve(p.id()));
        } catch (java.io.IOException e) {
            System.out.println("[profile] could not delete " + p.id() + "'s directory: " + e);
        }
    }

    /**
     * Build index.json under the lock and write it outside it. For the callers that are not already
     * inside {@code lock} ({@link #load}, which runs single-threaded at start-up); the CRUD methods
     * inline the same two steps because a reentrant synchronized would silently put the write back
     * under the lock they hold.
     */
    private void writeIndexNow() {
        Map<String, Object> json;
        synchronized (lock) {
            json = indexJson();
            indexDirty = false;
        }
        JsonStore.write(root.resolve("index.json"), json);
    }

    private void load() {
        Map<String, Object> index = JsonStore.read(root.resolve("index.json"));
        if (index == null) {
            // No index, or one too broken to use: one default profile, empty state. See the class
            // comment for why this is the migration path and not a data-loss event.
            Profile p = new Profile(nextId(), "default");
            profiles.add(p);
            states.put(p.id(), new ProfileState());
            activeId = p.id();
            writeIndexNow();
            System.out.println("[profile] no usable profile index; starting with a fresh 'default' profile");
            return;
        }

        Object list = index.get("profiles");
        if (list instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> e)) continue;
                String id = text(e.get("id")), name = text(e.get("name"));
                if (id == null || states.containsKey(id)) continue;    // duplicate/blank id: keep the first
                if (profiles.size() >= MAX_PROFILES) {
                    // An index past the cap is either hand-edited or from a build that allowed more;
                    // the bridge cannot carry them, so the tail is dropped with a line saying so
                    // rather than frozen out of the panel wholesale.
                    System.out.println("[profile] index lists more than " + MAX_PROFILES
                            + " profiles; keeping the first " + MAX_PROFILES);
                    break;
                }
                Profile p = new Profile(id, name == null ? id : name);
                profiles.add(p);
                states.put(id, loadState(p));
            }
        }
        if (profiles.isEmpty()) {
            Profile p = new Profile(nextId(), "default");
            profiles.add(p);
            states.put(p.id(), new ProfileState());
        }
        String active = text(index.get("active"));
        activeId = active != null && states.containsKey(active) ? active : profiles.get(0).id();
        Object pinObj = index.get("pins");
        if (pinObj instanceof Map<?, ?> pm) {
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                if (Boolean.TRUE.equals(e.getValue()) && e.getKey() != null) {
                    pins.put(String.valueOf(e.getKey()), Boolean.TRUE);
                }
            }
        }
    }

    /** A profile's config.json, or an empty state when it is missing or corrupt (logged by the store). */
    private ProfileState loadState(Profile p) {
        Map<String, Object> json = JsonStore.read(root.resolve(p.id()).resolve("config.json"));
        ProfileState s = new ProfileState();
        if (json == null) return s;

        Object enabled = json.get("enabled");
        if (enabled instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() instanceof Boolean b) {
                    s.enabled.put(String.valueOf(e.getKey()), b);
                }
            }
        }
        Object settings = json.get("settings");
        if (settings instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String pluginId = String.valueOf(e.getKey());
                if (!(e.getValue() instanceof Map<?, ?> values)) continue;
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> v : values.entrySet()) {
                    if (v.getKey() != null) out.put(String.valueOf(v.getKey()), v.getValue());
                }
                s.settings.put(pluginId, out);
            }
        }
        return s;
    }

    // --------------------------------------------------------------------------- plumbing

    private static final class ProfileState {
        final Map<String, Boolean> enabled = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> settings = new LinkedHashMap<>();

        boolean isEmpty() { return enabled.isEmpty() && settings.isEmpty(); }

        ProfileState copy() {
            ProfileState c = new ProfileState();
            c.enabled.putAll(enabled);
            for (Map.Entry<String, Map<String, Object>> e : settings.entrySet()) {
                c.settings.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
            }
            return c;
        }
    }

    private ProfileState state(String id) { return id == null ? null : states.get(id); }

    /** The state object to mutate, creating one for a profile whose file never said anything. */
    private ProfileState stateForWriting(String id) {
        ProfileState s = state(id);
        if (s != null) return s;
        Profile p = active();
        if (p == null) return null;
        s = new ProfileState();
        states.put(id, s);
        return s;
    }

    private Profile at(int index) {
        return index >= 0 && index < profiles.size() ? profiles.get(index) : null;
    }

    /** A JSON string field, or null when it is absent, not a string, or blank. */
    private static String text(Object o) {
        return o instanceof String s && !s.isBlank() ? s : null;
    }

    /** A name that does not collide with an existing one, so the panel's list stays unambiguous. */
    private String uniqueName(String base) {
        String b = base == null || base.isBlank() ? "profile" : base.trim();
        String name = b;
        for (int i = 2; ; i++) {
            boolean taken = false;
            for (Profile p : profiles) if (p.name().equalsIgnoreCase(name)) taken = true;
            if (!taken) return name;
            name = b + " " + i;
        }
    }

    private static int counter;

    /** Stable-per-profile ids: a counter keeps them readable, a random suffix keeps them unique. */
    private static String nextId() {
        return "p" + (++counter) + "-" + Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt());
    }

    /** Rebuild the Setting -> plugin-id map from the live registry. Cheap; called on registration. */
    private void rebuildOwners() {
        owners.clear();
        PluginManager mgr = PluginManager.instance();
        if (mgr == null) return;
        for (Plugin p : mgr.plugins()) {
            for (Setting s : p.config.all()) owners.put(s, p.id());
        }
    }

    private void bump() { generation++; }

    /** For the test suite: the directory a profile's config lives in. */
    Path profileDir(Profile p) { return root.resolve(p.id()); }
}

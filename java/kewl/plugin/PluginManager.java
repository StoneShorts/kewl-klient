package kewl.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import kewl.Plugin;
import kewl.config.Setting;

/**
 * The one place a plugin is switched on or off.
 *
 * <p>Before this class existed the transition lived in {@link Plugin#setEnabled}: flip the flag, run
 * the hook, swallow whatever the hook threw. That was fine when the only caller was the Java panel.
 * Now four things toggle plugins (the hotkey loop, the Java2D panel, the ImGui launcher's edit ring,
 * and the profile switch that re-states a whole profile at once), a plugin can fail to come up at
 * all, and the enabled state has to reach the profile store. Five call sites each improvising that
 * is how the tick loop eventually dies, so the transition moved here and {@link Plugin#setEnabled}
 * became a hand-off to it.</p>
 *
 * <p>The rules the manager enforces, in one place:</p>
 * <ul>
 *   <li><b>Idempotence.</b> Asking for a state the plugin is already in does nothing -- no second
 *       {@code onEnable}, no spurious persistence write, no revision churn.</li>
 *   <li><b>Exception isolation.</b> A plugin whose {@code onEnable} throws is logged, recorded as
 *       failed via {@link #failure}, and left <b>disabled</b> -- it did not come up, so claiming it
 *       is running would mean every frame after this one throws too. A plugin whose {@code onDisable}
 *       throws is still marked off (it must not keep ticking) but keeps the failure recorded. Either
 *       way the caller -- and the tick loop -- survives.</li>
 *   <li><b>One thread.</b> Every transition that happens outside start-up is queued through
 *       {@link Plugin#later} by the caller, so hooks never race the tick loop. Registration during
 *       start-up is the exception and runs inline, on the frame thread, before the first tick.</li>
 * </ul>
 *
 * <h2>Registry</h2>
 *
 * <p>{@link KewlKlient} keeps the deterministic built-in list and hands it to {@link #install}; this
 * class owns the live list, which is that list plus anything {@link #register} adds later -- external
 * plugins from the hub land here. Order is insertion order and never re-sorted: the panel-bridge
 * model indexes plugins by position ({@code KewlKlient.plugins().get(i)} is how an edit record names
 * its plugin), so the index of a plugin must stay valid for a whole session. Removing one shifts the
 * rest, which is exactly why the model revision moves on unregister -- the launcher re-reads the
 * whole model rather than trusting a stale index.</p>
 */
public final class PluginManager {

    /**
     * What the manager tells the profile store about. Deliberately two methods and no more: the store
     * cares about a plugin appearing and about its switch moving, and nothing else.
     */
    public interface Listener {
        /** A plugin joined the registry. The listener applies whatever state is stored for it. */
        void onRegistered(Plugin p);

        /** A plugin left the registry -- the hub's remove path. The listener forgets it. */
        void onUnregistered(Plugin p);

        /** A plugin's switch moved for real (idempotent no-ops are not reported). */
        void onEnabledChanged(Plugin p, boolean on);
    }

    private static volatile PluginManager instance;

    /** The installed manager, or null when {@link #install} has not run (the bare test suite). */
    public static PluginManager instance() { return instance; }

    /**
     * The most plugins the live registry holds. Must match {@code MAX_PLUGINS} in {@code
     * client/bridge.hpp} (restated in {@code launcher/bridge_layout.hpp}) -- see {@link #register}'s
     * comment for what an over-cap snapshot costs the panel.
     */
    public static final int MAX_PLUGINS = 64;

    private final List<Plugin> plugins = new ArrayList<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private volatile Listener listener;
    private boolean shutDown;

    private PluginManager() {}

    /**
     * Install the manager over the built-in registry. Calling it twice replaces the manager -- which
     * the test suite does, and nothing else should.
     *
     * @param builtIns the deterministic list from {@code KewlKlient.PLUGINS}; copied, so a caller
     *                 holding the original list cannot mutate what the manager walks
     */
    public static PluginManager install(List<Plugin> builtIns, Listener listener) {
        PluginManager m = new PluginManager();
        m.listener = listener;
        m.plugins.addAll(builtIns);
        instance = m;
        for (Plugin p : builtIns) notifyRegistered(p);
        return m;
    }

    /** Test hook: drop the manager so {@link Plugin#setEnabled} falls back to its inline transition. */
    public static void uninstall() {
        PluginManager m = instance;
        if (m != null) m.shutdown();
        instance = null;
    }

    private static void notifyRegistered(Plugin p) {
        Listener l = instance.listener;
        if (l == null) return;
        try {
            l.onRegistered(p);
        } catch (Throwable t) {
            // A store that cannot load its own state must not stop the plugin from existing.
            System.out.println("[plugin-manager] registration listener threw for " + p.name() + ": " + t);
        }
    }

    /** Every plugin, in registry order -- the order the panel-bridge model is written in. */
    public List<Plugin> plugins() { return Collections.unmodifiableList(plugins); }

    /** The plugin at a panel-bridge index, or null. */
    public Plugin byIndex(int idx) {
        return idx >= 0 && idx < plugins.size() ? plugins.get(idx) : null;
    }

    /** The plugin whose {@link Plugin#id()} matches, or null. */
    public Plugin byId(String id) {
        if (id == null) return null;
        for (Plugin p : plugins) if (id.equals(p.id())) return p;
        return null;
    }

    /** The last thing a plugin failed at, or null when it is fine. Surfaced, not swallowed. */
    public String failure(Plugin p) { return p == null ? null : failures.get(p.id()); }

    // --------------------------------------------------------------------------- the transition

    /**
     * The transition point. See the class comment for the rules. Safe from any thread, but the hook
     * still runs on <i>this</i> thread: the callers queue through {@link Plugin#later} (the panels,
     * the bridge) or call it during start-up (the profile load), and both are the frame thread.
     *
     * @return whether the plugin is in the requested state now -- false means the hook threw and the
     *         plugin was left disabled
     */
    public boolean setEnabled(Plugin p, boolean on) {
        if (p == null || shutDown) return false;
        if (on == p.isEnabled()) return true;              // idempotent: no hook, no persistence, no churn

        try {
            p.markEnabled(on);
            p.runHook(on);
            failures.remove(p.id());
        } catch (Throwable t) {
            String what = on ? "onEnable" : "onDisable";
            System.out.println("[" + p.name() + "] " + what + " threw: " + t);
            failures.put(p.id(), what + " threw: " + t);
            if (on) {
                // The hook died part-way through coming up. Whatever it half-built is the plugin's
                // problem (its own onDisable may clean it up on the next attempt), but it is not
                // running: tick and render would throw from here on, every frame.
                p.markEnabled(false);
            }
            // Off is off even if teardown complained -- the listener below still hears it, so the
            // profile records the state the plugin is actually in.
        }

        Listener l = listener;
        if (l != null) {
            try {
                l.onEnabledChanged(p, p.isEnabled());
            } catch (Throwable t) {
                System.out.println("[plugin-manager] state listener threw for " + p.name() + ": " + t);
            }
        }
        return p.isEnabled() == on;
    }

    // --------------------------------------------------------------------------- config reset

    /**
     * Put one setting back to its declared value. Part of the manager rather than a naked
     * {@code Setting.reset()} call so it goes through the same owner as everything else that changes
     * plugin state -- and so the {@code null} checks live in one place.
     */
    public void resetSetting(Plugin p, String key) {
        Setting s = p == null ? null : p.config.get(key);
        if (s == null) {
            System.out.println("[plugin-manager] reset: no setting \"" + key + "\" on "
                    + (p == null ? "?" : p.name()));
            return;
        }
        s.reset();
    }

    /** Put every setting of one plugin back to its declared values. The config panel's "reset all". */
    public void resetPlugin(Plugin p) {
        if (p == null) return;
        for (Setting s : p.config.all()) s.reset();
    }

    // --------------------------------------------------------------------------- registry changes

    /**
     * Add a plugin to the live registry -- the hub's entry point for an external plugin. The stored
     * state is applied through the listener, which is what makes an installed plugin come back with
     * the settings and enabled state it had when it was removed from the process.
     *
     * @return false when the plugin is null or already registered (by identity or by id, since a
     *         duplicated id would make profile state ambiguous)
     */
    public boolean register(Plugin p) {
        if (p == null) return false;
        if (plugins.contains(p) || byId(p.id()) != null) {
            System.out.println("[plugin-manager] refusing to register \"" + p.name()
                    + "\": id \"" + p.id() + "\" is already taken");
            return false;
        }
        if (plugins.size() >= MAX_PLUGINS) {
            // The panel bridge names plugins by index into this list over a fixed-count model region
            // (MAX_PLUGINS in client/bridge.hpp); a snapshot over the cap is rejected WHOLE, which
            // would freeze every tab. Sixty-four plugins is far past anything a real session loads --
            // the five built-ins plus hub installs -- so refusing the 65th with a line is the honest
            // ceiling, not a design limit anyone is expected to meet.
            System.out.println("[plugin-manager] refusing to register \"" + p.name()
                    + "\": the panel bridge carries at most " + MAX_PLUGINS + " plugins");
            return false;
        }
        plugins.add(p);
        notifyRegistered(p);
        return true;
    }
    /**
     * Take a plugin back out -- the hub's remove path. Its classloader is about to be closed, so it
     * is disabled first; a plugin that refuses to disable is removed anyway (the jar is going away
     * either way) with the failure recorded.
     *
     * @return false when there was nothing to remove
     */
    public boolean unregister(Plugin p) {
        if (p == null || !plugins.remove(p)) return false;
        if (p.isEnabled()) {
            try {
                p.markEnabled(false);
                p.runHook(false);
            } catch (Throwable t) {
                System.out.println("[" + p.name() + "] onDisable threw while being removed: " + t);
                failures.put(p.id(), "onDisable threw while being removed: " + t);
            }
        }
        Listener l = listener;
        if (l != null) {
            try {
                l.onUnregistered(p);
            } catch (Throwable t) {
                System.out.println("[plugin-manager] unregister listener threw for " + p.name() + ": " + t);
            }
        }
        return true;
    }

    /**
     * Stop everything and drop the registry. Called by {@link #uninstall} and from the client's
     * shutdown path: hooks that clean up game state get their last chance, and the persistence
     * listener is not consulted (the store flushes itself on its own schedule).
     */
    public void shutdown() {
        if (shutDown) return;
        shutDown = true;
        for (Plugin p : plugins) {
            if (!p.isEnabled()) continue;
            try {
                p.markEnabled(false);
                p.runHook(false);
            } catch (Throwable t) {
                System.out.println("[" + p.name() + "] onDisable threw during shutdown: " + t);
            }
        }
        plugins.clear();
        failures.clear();
    }
}

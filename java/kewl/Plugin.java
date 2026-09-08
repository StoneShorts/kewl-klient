package kewl;

import java.awt.Graphics2D;
import java.util.concurrent.ConcurrentLinkedQueue;

import kewl.config.Config;

/**
 * Something that runs, draws, or both.
 *
 * <p>Extend it, override what you need, add one line to {@link KewlKlient#PLUGINS}. There is no
 * scanning, no annotations and no manifest -- the list is the registry, and you can read it.</p>
 *
 * <pre>{@code
 *   public final class Waver extends Plugin {
 *
 *       public Waver() {
 *           config.number("radius", "Radius", "How far to look", 5, 1, 15);
 *       }
 *
 *       @Override public String name()        { return "Waver"; }
 *       @Override public String description() { return "Says hello to the nearest npc."; }
 *
 *       @Override
 *       public void tick() {
 *           Entity npc = Npcs.nearestWithin(config.number("radius"));
 *           if (npc != null) System.out.println("hello " + npc);
 *       }
 *
 *       @Override
 *       public void render(Graphics2D g) {
 *           g.drawString("waving", 20, 100);
 *       }
 *   }
 * }</pre>
 *
 * <h2>The two methods, and why they are separate</h2>
 *
 * <p>{@link #tick()} decides things and acts. {@link #render(Graphics2D)} draws and must decide nothing.
 * Both run every frame, tick first, on the same thread. Keeping them apart means you can turn the
 * drawing off without changing behaviour, and it stops the classic bug where a bot's logic silently
 * depends on something only the drawing code worked out.</p>
 *
 * <p><b>Neither may block.</b> They run on the overlay thread about thirty times a second. Sleeping in
 * one freezes the overlay. For "do something every few seconds", keep a timestamp field and compare it
 * -- {@code kewl.plugins.Woodcutter} shows the pattern.</p>
 */
public abstract class Plugin {

    /** This plugin's settings. Declare them in your constructor; the control panel draws them for you. */
    public final Config config = new Config();

    // The frame thread and the Swing event thread both touch this; the panel reads it from the EDT
    // while the frame loop writes it. Without volatile a checkbox can show a stale state forever.
    private volatile boolean enabled;

    // Bumped on every real enable/disable across all plugins. Part of the panel-bridge's model
    // revision (see kewl.panel.PanelBridge): the launcher must republish its plugin list when a switch
    // flips, and a flip changes no Setting -- without a counter of its own the launcher would never
    // hear about it.
    private static volatile int enableVersion;

    /** How many times any plugin has been switched on or off since start-up. */
    public static int enableVersion() { return enableVersion; }

    // Runnables handed over from other threads (the Swing panel), run at the top of the next frame.
    private static final ConcurrentLinkedQueue<Runnable> NEXT_FRAME = new ConcurrentLinkedQueue<>();

    /**
     * Run {@code r} at the top of the next frame, on the overlay thread. The control panel uses this
     * for everything that touches plugin state, so enable/disable and tick never run concurrently.
     */
    public static void later(Runnable r) {
        NEXT_FRAME.add(r);
    }

    /** Drained by the frame loop; package-private so only KewlKlient runs it. */
    static void drainLater() {
        Runnable r;
        while ((r = NEXT_FRAME.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                System.out.println("[kewl] queued action threw: " + t);
            }
        }
    }

    /** The name shown in the control panel. */
    public abstract String name();

    /** One line describing what it does, shown under the name. */
    public String description() { return ""; }

    // -- metadata. All defaulted, so the simple plugin at the top of this file stays exactly as simple
    //    as it was: the panel and the persistence layer need an id, version, author and tags, and a
    //    plugin that does not care should not have to spell out five empty strings to say so.

    /**
     * The stable identifier the persistence layer and the panel key this plugin by. Derived from the
     * name by default, which is right for everything that ships with the client: the name is already
     * the plugin's identity in the registry, and a derived id means "rename the class, keep the name"
     * does not silently orphan a profile's saved state.
     *
     * <p>Override only when two plugins would otherwise derive the same id, or when the name is
     * something a file path should not carry. Whatever you return must be stable across sessions --
     * it is the key a profile stores this plugin's state under.</p>
     */
    public String id() { return idOf(name()); }

    /** Same rule as {@link #id()}, usable before a {@link Plugin} instance exists. */
    public static String idOf(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toLowerCase().toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') sb.append(c);
            else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') sb.append('-');
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        return sb.length() == 0 ? "plugin" : sb.toString();
    }

    /** This plugin's own version string. Empty for built-ins, which version with the client. */
    public String version() { return ""; }

    /** Who wrote it. Empty for built-ins. */
    public String author() { return ""; }

    /** Search words for the plugin list, beyond the name and description. */
    public String[] tags() { return new String[0]; }

    /**
     * Which function key toggles this, as an index: 0 is F1, 7 is F8. Return -1 for none.
     *
     * <p>Nothing stops two plugins claiming the same key -- they will both toggle, which is
     * occasionally what you want.</p>
     */
    public int hotkey() { return -1; }

    // -- developer scaffolding. The panel lists ten plugins and four of them are test rigs or worked
    //    examples the RuneLite ports have replaced; the user asked for them out of the way, not gone.

    // Written once, while KewlKlient builds its registry, and read every frame from the bridge's
    // snapshot thread -- volatile for the same reason `enabled` is: two threads, no lock.
    private volatile boolean developerFlag;

    /**
     * Whether this is developer scaffolding: a smoke test, a probe, or a worked example kept for
     * reference rather than use. Both panels sort these last and put them under a "Developer"
     * heading; nothing else about the plugin changes, and it stays as enable-able as any other.
     *
     * <p>Defaulted, so no existing plugin has to say anything. Two ways to say yes: override this to
     * return {@code true} (a plugin that knows it is a test rig), or call {@link #markDeveloper()}
     * from the registry (a plugin whose own class should not have to know how the panel groups it --
     * {@code kewl.rl.RlitePlugin} wraps four different plugins and only two of them are tests).</p>
     */
    public boolean developer() { return developerFlag; }

    /**
     * Mark this instance as developer scaffolding, returning it so the registry line stays one line:
     * {@code new kewl.plugins.NpcVisuals().markDeveloper()}. Call it before the plugin is published
     * -- {@link KewlKlient}'s list is built on one thread before anything reads it.
     */
    public final Plugin markDeveloper() {
        developerFlag = true;
        return this;
    }

    /** Whether it is currently running. */
    public final boolean isEnabled() { return enabled; }

    /**
     * Turn it on or off, firing {@link #onEnable()} / {@link #onDisable()} on a real change.
     *
     * <p>When a {@link kewl.plugin.PluginManager} is installed this is a one-line hand-off to it --
     * the manager is the only thing allowed to transition a plugin, and this method exists so the
     * many existing callers (the hotkey loop, both panels, the bridge's ENABLE_KEY edit) all arrive at
     * that one place without having to know the manager exists. Before a manager is installed -- the
     * bare test suite, the instant before {@code KewlKlient.start} wires one up -- it falls back to
     * the inline transition this method has always been, with exactly today's semantics.</p>
     */
    public final void setEnabled(boolean on) {
        kewl.plugin.PluginManager m = kewl.plugin.PluginManager.instance();
        if (m != null) {
            m.setEnabled(this, on);
            return;
        }
        legacyTransition(on);
    }

    /** The pre-manager transition, kept verbatim so nothing that runs before start-up changes. */
    private void legacyTransition(boolean on) {
        if (on == enabled) return;
        enabled = on;
        enableVersion++;
        try {
            if (on) onEnable(); else onDisable();
        } catch (Throwable t) {
            // A plugin misbehaving on a toggle must not take the client with it.
            System.out.println("[" + name() + "] " + (on ? "onEnable" : "onDisable") + " threw: " + t);
        }
    }

    /**
     * Flip the flag without running the lifecycle hook. Only {@code kewl.plugin.PluginManager} calls
     * this: it is the half of a transition the manager has to own separately, because a plugin whose
     * {@link #onEnable} throws must be left <b>disabled</b>, and the flag has to be settable again
     * after the hook has already run. Package-private would be the right visibility, but the manager
     * lives in {@code kewl.plugin} -- so it is public, final, and documented as not for you.
     */
    public final void markEnabled(boolean on) {
        enabled = on;
        enableVersion++;
    }

    /**
     * Run {@link #onEnable} or {@link #onDisable}, for {@code kewl.plugin.PluginManager} only -- the
     * hooks are {@code protected} so that a plugin's neighbours cannot poke each other's lifecycle,
     * and the manager is not a neighbour, it is the owner. Same visibility note as {@link
     * #markEnabled}: public because the package boundary says so, final because nothing else should.
     */
    public final void runHook(boolean on) {
        if (on) onEnable(); else onDisable();
    }

    /** Flip it. */
    public final void toggle() { setEnabled(!enabled); }

    /** Called when it is switched on. Reset your state here, not in the constructor. */
    protected void onEnable() {}

    /** Called when it is switched off. */
    protected void onDisable() {}

    /** Called every frame while enabled. Decide and act here. Must not block. */
    public void tick() {}

    /**
     * Called every frame while enabled, after every plugin has ticked. Draw here and nothing else.
     *
     * <p>The graphics context is a normal {@link Graphics2D} over the whole game window, already set up
     * for antialiasing, with (0,0) at the top-left of the game's client area. Anything Java2D can do
     * works: shapes, gradients, alpha, fonts, images.</p>
     */
    public void render(Graphics2D g) {}

    /** A short line for the control panel's status column. Empty for none. */
    public String status() { return ""; }
}

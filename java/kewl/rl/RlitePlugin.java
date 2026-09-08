// Adapter that runs a RuneLite-style plugin inside kewl.
//
// One RlitePlugin wraps one net.runelite.client.plugins.Plugin. The object graph is built eagerly in
// the constructor -- that is when the config proxy declares its settings, so the control panel can
// show them before the plugin is ever enabled -- but startUp() waits for the user to switch it on,
// matching RuneLite's own lifecycle.
//
// Threading: everything here runs on kewl's overlay frame thread. That thread is registered as the
// shim's "client thread", so PathfinderConfig.refresh()'s thread guard passes and ClientThread
// callbacks from the pathfinder worker queue up and drain at the top of the next frame.
package kewl.rl;

import java.awt.Graphics2D;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import kewl.Plugin;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;

public class RlitePlugin extends Plugin
{
	private final String displayName;
	private final String displayDescription;
	private final Supplier<net.runelite.client.plugins.Plugin> factory;

	private EventBus eventBus;
	private OverlayManager overlayManager;
	private net.runelite.client.plugins.Plugin plugin;
	private Events events;
	/** The right-click popup menu: the fallback for the not-yet-readable game menu (see MenuPopup). */
	private MenuPopup menuPopup;
	/** Overlays startUp added, so onDisable can take exactly those back out. */
	private Set<Overlay> ownedOverlays = Set.of();
	/** The auto-walk driver, wired in build() when the wrapped plugin produces paths. */
	private AutoWalk autoWalk;

	/** Whether to echo the walker's status line to the log; see the tick site for why. */
	private static final boolean LOG_STATUS = System.getenv("KEWL_LOG") != null;
	/** Kept from build() so key edges can be dispatched to the plugin's registered hotkeys. */
	private KeyManager keyManager;
	/** Synthetic source for dispatched KeyEvents; a KeyEvent refuses a null source. */
	private static final java.awt.Component KEY_EVENT_SOURCE = new java.awt.Panel();

	/**
	 * @param name        shown in the control panel
	 * @param description one line under the name
	 * @param factory     builds the RuneLite-style plugin; called once, in the constructor
	 */
	public RlitePlugin(String name, String description, Supplier<net.runelite.client.plugins.Plugin> factory)
	{
		this.displayName = name;
		this.displayDescription = description;
		this.factory = factory;
		build();
	}

	@Override
	public String name()
	{
		return displayName;
	}

	@Override
	public String description()
	{
		return displayDescription;
	}

	/** Build the object graph now so the panel sees the settings; startUp waits for enable. */
	private void build()
	{
		eventBus = new EventBus();
		ConfigManager configManager = new ConfigManager(eventBus, config);
		overlayManager = new OverlayManager();

		Injector injector = new Injector(eventBus, configManager, config);
		injector.bind(Client.class, Client.get());
		injector.bind(EventBus.class, eventBus);
		injector.bind(OverlayManager.class, overlayManager);
		injector.bind(ConfigManager.class, configManager);
		injector.bind(ClientThread.class, new ClientThread());
		keyManager = new KeyManager();
		injector.bind(KeyManager.class, keyManager);
		injector.bind(SpriteManager.class, new SpriteManager());

		try
		{
			net.runelite.client.plugins.Plugin built = factory.get();
			// Auto-walk is Shortest Path's toggle, not the adapter's: declared here, BEFORE injection
			// declares the plugin's own config items, so Shortest Path's panel order is unchanged --
			// and declared ONLY for it, so NPC/Player Indicators and the smoke tests do not grow a
			// toggle that does nothing. Default ON, unlike upstream, which never issues a single
			// step: its path is guidance and the user walks it. Kewl's user asked for the client to
			// walk the path, and a "Set target" that draws tiles but never moves reads as the plugin
			// being broken -- the first run should end with the character arriving.
			if (built instanceof shortestpath.ShortestPathPlugin)
			{
				config.bool("autoWalk", "Auto-walk",
					"Walk the computed path automatically. Stops at plane changes (boats, stairs, teleports).",
					true);
			}
			plugin = injector.build(built);
		}
		catch (Throwable t)
		{
			System.out.println("[" + displayName + "] construction failed: " + t);
			t.printStackTrace();
		}

		events = new Events(eventBus);
		menuPopup = new MenuPopup(eventBus);

		// The auto-walk driver needs the plugin's path. Until a second ported plugin produces paths,
		// this is wired straight to ShortestPathPlugin -- see AutoWalk.
		if (plugin instanceof shortestpath.ShortestPathPlugin shortestPath)
		{
			autoWalk = new AutoWalk(shortestPath);
		}
	}

	@Override
	protected void onEnable()
	{
		if (plugin == null)
		{
			return;
		}
		// The overlay frame thread is the shim's client thread for the lifetime of the client.
		ClientThread.setGameThread(Thread.currentThread());

		plugin.setEventBus(eventBus);
		// Normalize to exactly one subscription: Injector registered the plugin at construction, and
		// an enable/disable/enable cycle must not stack a second copy of every subscriber.
		eventBus.unregister(plugin);
		eventBus.register(plugin);
		Set<Overlay> before = new HashSet<>(overlayManager.getOverlays());
		invokeLifecycle("startUp");
		Set<Overlay> after = new HashSet<>(overlayManager.getOverlays());
		after.removeAll(before);
		ownedOverlays = after;
	}

	@Override
	protected void onDisable()
	{
		if (plugin == null)
		{
			return;
		}
		invokeLifecycle("shutDown");
		// RuneLite's plugin manager only delivers events to enabled plugins; without this a disabled
		// plugin keeps receiving GameTick and ConfigChanged and quietly keeps acting on them.
		eventBus.unregister(plugin);
		if (autoWalk != null)
		{
			autoWalk.reset();
		}
		// tick() only runs the popup while enabled, so a menu up when the user switches the plugin
		// off would never see another tick: force-close it, or isMenuOpen() stays true (and the
		// captured tile stays parked) until the plugin is enabled and ticked again.
		menuPopup.close();
		autoWalkStatus = null;
		// remove(), not getOverlays().removeAll(): getOverlays() hands back an unmodifiable view, and
		// removeAll on it throws -- the overlays would only ever leave by the plugin's own shutDown
		// happening to remove them.
		overlayManager.remove(ownedOverlays);
		ownedOverlays = Set.of();
	}

	/**
	 * startUp/shutDown are protected on net.runelite.client.plugins.Plugin, so plugins can override
	 * them but kewl.rl cannot call them directly. Reflection is the bridge -- and the lookup must walk
	 * the hierarchy with getDeclaredMethod, because getMethod only finds PUBLIC methods and would
	 * NoSuchMethodException on every plugin whose lifecycle is the upstream protected one.
	 */
	private void invokeLifecycle(String method)
	{
		try
		{
			java.lang.reflect.Method m = null;
			for (Class<?> c = plugin.getClass(); c != null && c != Object.class; c = c.getSuperclass())
			{
				try
				{
					m = c.getDeclaredMethod(method);
					break;
				}
				catch (NoSuchMethodException e)
				{
					// keep walking up
				}
			}
			if (m == null)
			{
				System.out.println("[" + displayName + "] has no " + method + "() to call");
				return;
			}
			m.setAccessible(true);
			m.invoke(plugin);
		}
		catch (Throwable t)
		{
			System.out.println("[" + displayName + "] " + method + " threw: " + t);
			t.printStackTrace();
		}
	}

	@Override
	public void tick()
	{
		// Push the raw Win32 input snapshot before anything reads it this frame -- getMouseCanvasPosition
		// and isKeyPressed(KC_SHIFT) both come off this, and plugins want the state of THIS frame.
		Client.get().state().setInputState(kewl.Natives.input());

		if (plugin == null)
		{
			return;
		}
		logPlaneUnreadableOnce();
		logShimGapsOnce();
		ClientThread.drain();
		dispatchKeyEdges();
		events.fire();
		// After events: a right-click fires MenuOpened/MenuEntryAdded synchronously, and the plugin's
		// handlers (which read this frame's input state) must see the same snapshot events.fire()
		// would have left.
		if (isEnabled())
		{
			menuPopup.tick();
		}

		if (autoWalk != null)
		{
			if (config.bool("autoWalk"))
			{
				String before = autoWalkStatus;
				autoWalkStatus = autoWalk.tick();
				// The walker's decision reaches the SIDE PANEL and nowhere else, so a stand-down is
				// invisible to anyone reading a log -- which is how a live test on 2026-09-07 got as far
				// as "the path is drawn, the character never moves" with nothing to say why. One line per
				// CHANGE (never per frame), and only under KEWL_LOG.
				if (LOG_STATUS && autoWalkStatus != null && !autoWalkStatus.equals(before))
				{
					System.out.println("[autowalk] " + autoWalkStatus);
				}
			}
			else if (autoWalkStatus != null)
			{
				// The toggle went off. Without this the panel keeps the last line the driver wrote
				// ("walking to 3200,3200 (step 4/20)") for the rest of the session, which reads as a
				// walk still running -- and the driver would resume with the old path's cursor and
				// "already walking toward" target the next time it is switched back on (review
				// 2026-09-06). Cleared once, not every frame, so status() falls back to the hosted
				// plugin's own line.
				autoWalkStatus = null;
				autoWalk.reset();
			}
		}
	}

	/**
	 * One line per login when the native could not read the local player's plane (-1: ENTITY_PLANE
	 * is SUSPECT this build, client/offsets.hpp). The RuneLite shim's WorldView.getPlane() then
	 * assumes the ground floor so pathing still works; this makes that assumption visible in the
	 * KEWL_LOG trace instead of silent. Re-arms once a real plane is read (e.g. after a hop), so a
	 * relog that loses the plane again is logged again.
	 *
	 * <p>The flag is STATIC (review 2026-09-06): the plane is a property of the client, not of a
	 * plugin, and every enabled RlitePlugin runs this check every frame -- with a per-instance flag,
	 * three hosted plugins printed three identical lines per login, all tagged [shortestpath]. One
	 * line per login now, whichever instance happens to reach it first. The tag stays as-is because
	 * PROGRESS.md's plane-debugging notes quote it verbatim.</p>
	 */
	private void logPlaneUnreadableOnce()
	{
		kewl.api.Local me = kewl.api.Game.me();
		if (!me.exists())
		{
			return;
		}
		if (me.plane() < 0)
		{
			if (!planeUnreadableLogged)
			{
				planeUnreadableLogged = true;
				System.out.println("[shortestpath] plane unreadable (-1 from the client), assuming ground floor");
			}
		}
		else
		{
			planeUnreadableLogged = false;
		}
	}

	private static boolean planeUnreadableLogged;

	/**
	 * The shim's own account of what it faked, printed ONCE per session into the KEWL_LOG trace,
	 * after the hosted plugins have had a few seconds to actually call things.
	 *
	 * <p>Why a delay rather than a line at startUp: {@link net.runelite.api.ShimSupport} only knows
	 * about accessors that have been CALLED, which is what keeps the report scoped to what this
	 * session really depends on. Printed immediately it would say "nothing stubbed" and be useless;
	 * printed after a few hundred frames of overlays rendering, it is the list of every placeholder
	 * the running plugins actually hit -- the document a report like "the camera shows zeros" gets
	 * answered from. Each accessor has already logged its own line the first time it was reached;
	 * this is the summary, and it is deliberately the only place the whole list appears at once.</p>
	 *
	 * <p>STATIC, like the plane flag above and for the same reason: the gaps belong to the shim, not
	 * to a plugin, so three enabled RlitePlugins must not print three copies.</p>
	 */
	private static void logShimGapsOnce()
	{
		if (shimGapsLogged || kewl.KewlKlient.frame() < SHIM_REPORT_FRAME)
		{
			return;
		}
		shimGapsLogged = true;
		System.out.println(net.runelite.api.ShimSupport.report());
	}

	/**
	 * Frames to wait before the shim-gap summary. ~10 seconds at kewl's frame pace: long enough for
	 * a login and a few hundred overlay renders, short enough to be in the log before the user has
	 * finished doing whatever they are about to report.
	 */
	private static final int SHIM_REPORT_FRAME = 500;

	private static boolean shimGapsLogged;

	/**
	 * Hand this frame's key edges to the plugin's registered hotkey listeners as ordinary KeyEvents --
	 * the same shape Keybind.matches consumes in RuneLite. Sources from a dummy component; KeyEvent
	 * refuses a null one.
	 */
	private void dispatchKeyEdges()
	{
		if (keyManager == null)
		{
			return;
		}
		int[] edges = Client.get().state().keyEdges();
		if (edges.length == 0)
		{
			return;
		}
		net.runelite.api.ClientState state = Client.get().state();
		int mods = 0;
		if (state.isKeyPressed(net.runelite.api.KeyCode.KC_SHIFT))
		{
			mods |= java.awt.event.InputEvent.SHIFT_DOWN_MASK;
		}
		if (state.isKeyPressed(net.runelite.api.KeyCode.KC_CONTROL))
		{
			mods |= java.awt.event.InputEvent.CTRL_DOWN_MASK;
		}
		if (state.isKeyPressed(net.runelite.api.KeyCode.KC_ALT))
		{
			mods |= java.awt.event.InputEvent.ALT_DOWN_MASK;
		}
		long when = System.currentTimeMillis();
		for (int vk : edges)
		{
			keyManager.dispatchPressed(new java.awt.event.KeyEvent(KEY_EVENT_SOURCE,
				java.awt.event.KeyEvent.KEY_PRESSED, when, mods, vk, java.awt.event.KeyEvent.CHAR_UNDEFINED));
		}
	}

	@Override
	public void render(Graphics2D g)
	{
		if (plugin == null)
		{
			return;
		}
		OverlayRenderer.render(g, overlayManager);
		menuPopup.render(g);                 // on top of every overlay: a menu over a menu is a menu
	}

	@Override
	public String status()
	{
		if (plugin == null)
		{
			return "failed to construct";
		}
		if (autoWalkStatus != null)
		{
			return autoWalkStatus;
		}
		// A hosted plugin that publishes its own status line (TestActors); see StatusSource.
		if (plugin instanceof StatusSource s)
		{
			String st = s.status();
			if (st != null && !st.isEmpty())
			{
				return st;
			}
		}
		// Nothing else to say: name the shim placeholders this session has actually hit, rather than
		// leaving the line blank. A blank line reads as "all well", which is the exact impression
		// that made "the camera shows zeros" impossible to act on -- and this line only ever appears
		// when a running plugin has genuinely read a faked value, so on a clean session it is still
		// empty. See net.runelite.api.ShimSupport.
		return net.runelite.api.ShimSupport.status();
	}

	/** Last non-null status from the auto-walk driver, for the control panel. */
	private String autoWalkStatus;
}

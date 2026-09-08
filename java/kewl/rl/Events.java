// The shim's event source: turns kewl's frame loop into the RuneLite events the plugin subscribes to.
//
// GameTick and PostClientTick fire on a real game-tick boundary -- the client's cycle counter counts
// 20ms frames, so a tick is thirty of them. GameStateChanged comes from the client's own state field
// (client+0x2160, LIVE as of client-240-6), WorldChanged from the despawn/respawn transition, and the
// container/widget events from diffs of the natives that Phase D landed. The menu events still wait on
// a hook-and-log of the minimenu path -- see offsets.hpp DO_ACTION for why that one is call-protected.
package kewl.rl;

import net.runelite.api.ActorTable;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.events.WorldChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.eventbus.EventBus;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Events
{
	/** The client's cycle counter is a frame counter (+1 per 20ms); a game tick is 600ms of them. */
	private static final int FRAMES_PER_TICK = 30;

	/** The interface group the world map lives in; its component ids are (group << 16) | index. */
	private static final int WORLDMAP_GROUP = InterfaceID.Worldmap.MAP_CONTAINER >>> 16;

	/** The containers whose changes the ported plugin actually reacts to. */
	/** Highest component index in the world-map group worth dumping; the table ends around 0x26. */
	private static final int WORLDMAP_MAX_COMPONENT = 0x2a;

	private static final int[] WATCHED_CONTAINERS = { 93, 95 };  // InventoryID.INV, InventoryID.BANK

	private final EventBus eventBus;
	private int lastTick = Integer.MIN_VALUE;
	private int lastBaseX = Integer.MIN_VALUE, lastBaseY = Integer.MIN_VALUE;
	private boolean playerWasPresent;
	private GameState lastState;
	private final int[][] lastContainers = new int[WATCHED_CONTAINERS.length][];
	private int[] lastLoadedGroups = new int[0];
	/** One "world map open" scale line per hosted plugin's bus; see logWorldMapScaleOnce. */
	private boolean loggedWorldMapScale;
	/**
	 * Actors this bus has been told about. Per Events (= per hosted plugin), not global, so two hosted
	 * plugins each get a complete spawn/despawn diff regardless of which one ticked first.
	 */
	private final Set<NPC> knownNpcs = new HashSet<>();
	private final Set<Player> knownPlayers = new HashSet<>();
	/** One "[actors] first frame" KEWL_LOG line per login, shared across hosted plugins. */
	private static boolean firstActorFrameLogged;

	Events(EventBus eventBus)
	{
		this.eventBus = eventBus;
	}

	/** Called once per frame, before the plugin's own tick. */
	void fire()
	{
		GameState state = Client.get().getGameState();
		if (state != lastState)
		{
			GameStateChanged ev = new GameStateChanged();
			ev.setGameState(state);
			eventBus.post(ev);
			lastState = state;
			if (state != GameState.LOGGED_IN)
			{
				// Logged out (or never in): the next login prints its "[actors] first frame" line again.
				// Keyed on the state, not on the actor lists, so a transient empty frame in-game does not
				// re-print it.
				firstActorFrameLogged = false;
			}
		}

		// WorldChanged means "the world changed" -- a hop, a logout, a respawn. The respawn half of the
		// despawn/respawn transition is the trigger: it does NOT happen on the scene re-centres that
		// fire constantly while walking, and a stationary world hop (same coordinates, scene reloaded)
		// still goes through it. (The scene base moving is useless here: walking re-centres it every
		// few dozen tiles.) The first frame never fires -- there is no "was" to compare against.
		boolean playerPresent = kewl.api.Game.me().exists();
		if (lastBaseX != Integer.MIN_VALUE && !playerWasPresent && playerPresent)
		{
			eventBus.post(new WorldChanged());
		}
		lastBaseX = kewl.api.Game.sceneBaseX();
		lastBaseY = kewl.api.Game.sceneBaseY();
		playerWasPresent = playerPresent;

		fireContainerChanges();
		fireWidgetChanges();
		pushWorldMap();
		// Spawns before the tick, as upstream: a GameTick handler may already iterate what spawned.
		fireActorChanges();

		int tick = kewl.api.Game.me().cycle() / FRAMES_PER_TICK;
		if (tick != lastTick)
		{
			eventBus.post(new GameTick());
			eventBus.post(new PostClientTick());
			lastTick = tick;
		}
	}

	/**
	 * NpcSpawned/NpcDespawned/PlayerSpawned/PlayerDespawned, by diffing this frame's ActorTable against
	 * the set this bus already knows. The table keeps one object per handle across frames, so the sets
	 * are identity sets and a plugin holding an NPC from NpcSpawned sees the same object despawn. When
	 * Game.ready() is false the table is empty, so every known actor despawns -- upstream's logout.
	 */
	private void fireActorChanges()
	{
		ActorTable.refresh(kewl.KewlKlient.frame());
		List<NPC> npcs = ActorTable.npcs();
		List<Player> players = ActorTable.players();
		diffActors(npcs, players);

		if (!firstActorFrameLogged && !(npcs.isEmpty() && players.isEmpty()))
		{
			firstActorFrameLogged = true;
			int named = 0;
			for (NPC n : npcs)
			{
				if (!n.getName().isEmpty())
				{
					named++;
				}
			}
			System.out.println("[actors] first frame: " + npcs.size() + " npcs, " + players.size()
				+ " players (local included), " + named + " names non-empty");
		}
	}

	/** The testable core of fireActorChanges: post the diff between {@code known} and these lists. */
	void diffActors(List<NPC> npcs, List<Player> players)
	{
		for (NPC n : npcs)
		{
			if (knownNpcs.add(n))
			{
				eventBus.post(new NpcSpawned(n));
			}
		}
		if (knownNpcs.size() != npcs.size())
		{
			Set<NPC> present = new HashSet<>(npcs);
			List<NPC> gone = new ArrayList<>();
			for (NPC n : knownNpcs)
			{
				if (!present.contains(n))
				{
					gone.add(n);
				}
			}
			for (NPC n : gone)
			{
				knownNpcs.remove(n);
				eventBus.post(new NpcDespawned(n));
			}
		}

		for (Player p : players)
		{
			if (knownPlayers.add(p))
			{
				eventBus.post(new PlayerSpawned(p));
			}
		}
		if (knownPlayers.size() != players.size())
		{
			Set<Player> present = new HashSet<>(players);
			List<Player> gone = new ArrayList<>();
			for (Player p : knownPlayers)
			{
				if (!present.contains(p))
				{
					gone.add(p);
				}
			}
			for (Player p : gone)
			{
				knownPlayers.remove(p);
				eventBus.post(new PlayerDespawned(p));
			}
		}
	}

	/**
	 * ItemContainerChanged, by diffing each watched container's flat snapshot. A container read is one
	 * native call, so this is two per frame; a deep-equals on the arrays is the change test, and the
	 * same snapshot is what the event carries (as fresh Item objects -- the arrays must not be shared
	 * or a listener mutating one would corrupt the next diff).
	 */
	private void fireContainerChanges()
	{
		for (int i = 0; i < WATCHED_CONTAINERS.length; i++)
		{
			int id = WATCHED_CONTAINERS[i];
			int[] flat = kewl.Natives.container(id);
			if (Arrays.equals(flat, lastContainers[i]))
			{
				continue;
			}
			lastContainers[i] = flat;
			eventBus.post(new ItemContainerChanged(id, Client.get().getItemContainer(id)));
		}
	}

	/** WidgetLoaded/WidgetClosed, by diffing the set of groups whose component data is loaded. */
	private void fireWidgetChanges()
	{
		int[] groups = kewl.Natives.loadedGroups();
		Set<Integer> now = new HashSet<>();
		for (int g : groups)
		{
			now.add(g);
		}
		Set<Integer> before = new HashSet<>();
		for (int g : lastLoadedGroups)
		{
			before.add(g);
		}
		for (int g : groups)
		{
			if (!before.contains(g))
			{
				WidgetLoaded ev = new WidgetLoaded();
				ev.setGroupId(g);
				eventBus.post(ev);
			}
		}
		for (int g : lastLoadedGroups)
		{
			if (!now.contains(g))
			{
				// modalMode 0 / unload true: the shim never builds modal widgets, and the group's data
				// going away is exactly what RuneLite's "unload" closed event means.
				eventBus.post(new WidgetClosed(g, 0, true));
			}
		}
		lastLoadedGroups = groups;
	}

	/**
	 * Push the world map's centre into the shim's WorldMap each frame, in world tiles.
	 *
	 * The native array is {level, originX, originZ, centreX, centreZ}. The centre ints are the map's
	 * scroll position in units of 8 tiles, and the origin is that same centre shifted -48 tiles (the
	 * corner of the map-square load window -- derived from FUN_1401ce8b0/FUN_1401cefe0, which write
	 * origin = 8*centre - 48 and load squares over centre-6..centre+6). So the centre tile is
	 * 8*centreScroll = origin + 48; both encodings were checked live at the GE (scroll 398,429 ->
	 * origin 3136,3384 = 8*398-48, 8*429-48), and the origin read is used here because it is the one
	 * verified across sessions. One caveat, stated where the value is consumed: the +48 (centre vs
	 * load-window corner) follows from the symmetric +-6 load window, not from a live "which tile is
	 * under the widget centre" measurement -- worth one probe with the map open before trusting
	 * on-map click targets to the exact tile.
	 *
	 * The client provably has no zoom FIELD, so the scale is not pushed from here at all. It is
	 * MEASURED instead, off the user's own map drags, by calibrateZoomFromDrag below -- see WorldMap's
	 * header for the arithmetic and for the assumptions that measurement rests on. That measurement is
	 * REACHABLE as of 2026-09-07: it needs the container's canvas rectangle, and the parent-link walk
	 * is proven to produce one for a loaded group (the minimap resolved at (1156,8) 152x152 on a
	 * 1356-wide canvas), while opening the map is what loads the world-map group.
	 */
	private void pushWorldMap()
	{
		int[] wm = kewl.Natives.worldMap();
		if (wm.length == 5)
		{
			// wm[1]/wm[2] are WM_ORIGIN_X/Z; +48 turns the load-window corner into the centre tile.
			// The zoom is NOT pushed here any more: routing it through getWorldMapZoom() just handed
			// the same number back, while firing the uncalibrated-scale note on the way, and the drag
			// calibrator below writes the scale on its own schedule.
			WorldMap.INSTANCE.setPosition(new net.runelite.api.Point(wm[1] + 48, wm[2] + 48));
			logWorldMapScaleOnce();
			// wm[3]/wm[4] are WM_CENTRE_X/Z, the scroll ints. They were coming back from the native
			// and being thrown away; they are the only thing in the client that moves by a KNOWN
			// number of world tiles while the user does something whose PIXEL size we can also see.
			calibrateZoomFromDrag(wm[3], wm[4]);
		}
		else
		{
			// No world-map object (pre-login or the client dropped it): whatever was pushed before is
			// stale, so drop liveness rather than let overlays and map clicks act on dead data.
			WorldMap.INSTANCE.clear();
			endDrag();
		}
	}

	// -- the map scale, measured off a drag --------------------------------------------------------
	//
	// See WorldMap's header for the arithmetic and, more importantly, for the three ASSUMPTIONS this
	// rests on (the drag is 1:1 pixel-to-map, there is no inertia after the release, the zoom did not
	// change mid-drag). None of them is derived from the client; this is a MEASUREMENT OF BEHAVIOUR,
	// and it is written that way on purpose -- a value that arrives here is trusted enough to switch
	// every world-map surface on, so the way it can be wrong has to be visible in the code.
	//
	// THIS IS THE ONLY THING IN THE PROGRAM THAT CAN EVER SET isZoomCalibrated(), so its preconditions
	// are the bootstrap for every world-map surface, and one of them used to make that bootstrap
	// impossible to reach. The gate was:
	//
	//     map == null || map.isHidden() || !map.isCanvasAbsolute() || no rectangle
	//
	// Two of those four are now settled by measurement and one had to go:
	//
	//   * isCanvasAbsolute() STAYS, and it is no longer the blocker it was assumed to be. Live
	//     2026-09-07: "[shim] minimap widget resolved at (1156,8) 152x152" on a 1356-wide canvas --
	//     the parent-link walk runs and returns complete=1 for a LOADED group. Opening the map loads
	//     the world-map group ("[shim] widgetAbs WORLDMAP 595:7 is not loaded" is what it says
	//     BEFORE the first open), so by the time a drag can happen the rectangle is absolute.
	//
	//   * isHidden() GOES. It reads one component's own IFTYPE_HIDDEN byte at +0x78, which
	//     offsets.hpp does not claim to have verified, and it does not consult the parent chain the
	//     way upstream's isHidden() does. If it reads "hidden" while the map is open, this method
	//     returns before it ever starts a drag, the scale can never be measured, every map surface
	//     refuses forever, and nothing in the log says why. That is the deadlock.
	//
	// Removing it costs nothing, because THE MEASUREMENT IS SELF-EVIDENCING: a drag is only accepted
	// when WM_CENTRE actually moved MIN_DRAG_SCROLL_UNITS or more over it, and WM_CENTRE does not
	// move unless the map is open and being panned. A press inside the container's rectangle with the
	// map shut starts a provisional drag that measures nothing and is thrown away at the release. So
	// the unverified byte is replaced by the client's own behaviour, which is strictly better
	// evidence than a byte nobody has proven.
	//
	// Everything below is deliberately conservative in the same direction: a drag that might not have
	// been a map pan is DISCARDED rather than averaged in. A wrong scale is silently wrong everywhere
	// on the map, which is exactly the state this whole task exists to get out of.

	private boolean dragActive;
	private boolean dragSpoiled;
	private boolean lastLeftDown;
	private int dragStartMouseX, dragStartMouseY;
	private int dragStartCentreX, dragStartCentreZ;
	private Rectangle dragStartRect;
	/** Printed once, for the drag that looked like a pan and did not qualify -- see the release path. */
	private boolean loggedShortDrag;

	private void calibrateZoomFromDrag(int centreX, int centreZ)
	{
		boolean left = Client.get().state().lbuttonDown();
		// Nothing is happening and nothing was: bail BEFORE the widget lookup. This runs every frame
		// for the rest of the session once the map group loads (the group stays loaded while the map
		// is closed on this build), and resolving a widget is two JNI round trips plus a string --
		// not something to spend on every idle frame forever.
		if (!left && !lastLeftDown && !dragActive)
		{
			return;
		}
		if (!isGroupLoaded(WORLDMAP_GROUP))
		{
			endDrag();
			lastLeftDown = false;
			return;
		}
		Widget map = Client.get().getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		// The rectangle has to be a CANVAS rectangle for "the cursor is over the map" to mean anything,
		// and it has to be a rectangle at all. That is WorldMap.containmentRefusalFor's question minus
		// its isHidden() term, which is deliberately not asked here -- see the block above: it is the
		// one unverified read that could make the calibration unreachable, and the centre-moved test at
		// the release is stronger evidence of an open map than that byte is.
		if (map == null || !map.isCanvasAbsolute() || map.getWidth() <= 0 || map.getHeight() <= 0)
		{
			endDrag();
			lastLeftDown = false;
			return;
		}

		net.runelite.api.Point mouse = Client.get().getMouseCanvasPosition();
		Rectangle rect = map.getBounds();

		if (left && !lastLeftDown)
		{
			// A press on ZOOM_IN/ZOOM_OUT invalidates whatever was measured: the number that was
			// measured describes a zoom level that is about to stop being on screen. Dropping it puts
			// every map surface back into "no scale" until the next drag, which is honest -- far
			// better than drawing a path at the previous zoom's scale.
			if (pressedZoomButton(mouse))
			{
				WorldMap.INSTANCE.clearCalibration();
				System.out.println("[shim] world map zoom button clicked -- the map scale in force"
					+ " described the PREVIOUS zoom level, so it was dropped and every world-map"
					+ " overlay stands down until the next drag re-measures it. A value passed with"
					+ " -D" + net.runelite.api.worldmap.WorldMap.ZOOM_PROPERTY + " is dropped the same"
					+ " way and for the same reason: it too described one zoom level");
				endDrag();
			}
			else if (rect.contains(mouse.getX(), mouse.getY()) && !pressedMapFurniture(mouse))
			{
				dragActive = true;
				dragSpoiled = false;
				dragStartMouseX = mouse.getX();
				dragStartMouseY = mouse.getY();
				dragStartCentreX = centreX;
				dragStartCentreZ = centreZ;
				dragStartRect = rect;
			}
		}
		else if (left && dragActive)
		{
			// The viewport moved or resized under the drag (the window was resized, a panel opened):
			// the pixel travel and the tile travel are no longer being measured against one geometry.
			if (!rect.equals(dragStartRect))
			{
				dragSpoiled = true;
			}
		}
		else if (!left && lastLeftDown && dragActive)
		{
			double pixels = Math.hypot(mouse.getX() - dragStartMouseX, mouse.getY() - dragStartMouseY);
			int units = (int) Math.round(Math.hypot(centreX - dragStartCentreX,
				centreZ - dragStartCentreZ));
			// The two reference points, built BEFORE the store so the log can quote the number that
			// was accepted alongside the pair of readings it was divided out of. A scale is the one
			// value here that is wrong invisibly -- a path at half scale starts right, points right and
			// ends somewhere else -- so the line has to be enough to redo the division by hand.
			String provenance = WorldMap.dragMeasurementNote(dragStartMouseX, dragStartMouseY,
				mouse.getX(), mouse.getY(), dragStartCentreX, dragStartCentreZ, centreX, centreZ,
				pixels, units, WorldMap.pixelsPerTileFromDrag(pixels, units));
			if (!dragSpoiled && WorldMap.INSTANCE.calibrateFromDrag(pixels, units, provenance))
			{
				loggedWorldMapScale = false;  // a re-measurement is worth re-printing
				System.out.println("[shim] world map scale MEASURED: " + provenance);
				logWorldMapScaleOnce();
			}
			else if (!loggedShortDrag && units > 0 && pixels > 0)
			{
				// Only for a drag that DID pan the map (units > 0 is the proof it was a real map drag)
				// and still did not qualify. Silence here is what makes the feature look broken: the
				// user drags, nothing changes, and there is nothing to read. Once per session, because
				// the second short drag teaches nothing the first did not.
				loggedShortDrag = true;
				System.out.println("[shim] world map drag NOT used as a scale: " + Math.round(pixels)
					+ " px of cursor travel moved WM_CENTRE by " + units + " scroll unit(s), and at"
					+ " least " + WorldMap.MIN_DRAG_SCROLL_UNITS + " are needed -- the centre is"
					+ " quantised to " + WorldMap.TILES_PER_SCROLL_UNIT + " tiles, so a short drag"
					+ " measures that quantisation rather than the scale"
					+ (dragSpoiled ? " (and the map's rectangle changed mid-drag, which spoils it"
						+ " regardless)" : "")
					+ ". " + WorldMap.humanBootstrapAction(WorldMap.INSTANCE.pixelsPerTileQuiet()));
			}
			endDrag();
		}
		lastLeftDown = left;
	}

	private void endDrag()
	{
		dragActive = false;
		dragSpoiled = false;
		dragStartRect = null;
	}

	/** Whether a press at {@code mouse} landed on one of the map's zoom buttons. */
	private boolean pressedZoomButton(net.runelite.api.Point mouse)
	{
		return hitsShownWidget(InterfaceID.Worldmap.ZOOM_IN, mouse)
			|| hitsShownWidget(InterfaceID.Worldmap.ZOOM_OUT, mouse);
	}

	/**
	 * Whether a press landed on something drawn OVER the map viewport rather than on the map itself --
	 * the overview panel and the map-list box, the same two rectangles the path overlay cuts out of
	 * its clip. A drag that starts on either is not a map pan and must not be measured as one.
	 */
	private boolean pressedMapFurniture(net.runelite.api.Point mouse)
	{
		return hitsShownWidget(InterfaceID.Worldmap.OVERVIEW_CONTAINER, mouse)
			|| hitsShownWidget(InterfaceID.Worldmap.MAPLIST_BOX_GRAPHIC0, mouse);
	}

	/**
	 * Present, SHOWN, absolutely placed, and under the cursor. This one keeps its {@code isHidden()}
	 * term where {@link #calibrateZoomFromDrag}'s container test dropped it, and the asymmetry is
	 * deliberate: here the byte guards against a FALSE POSITIVE (a scene click landing where a closed
	 * map's zoom button would be, wiping a good calibration), and the world-map group stays loaded
	 * with the map shut, so without it those rectangles would be live targets all session. Its failure
	 * mode is mild in the other direction too -- if it reads "hidden" on a button that is really
	 * shown, a zoom change goes unnoticed and a stale scale survives it until the next drag
	 * re-measures. That is a wrong scale for a while; the container test's failure mode was no scale
	 * ever.
	 */
	private boolean hitsShownWidget(int packedId, net.runelite.api.Point mouse)
	{
		Widget w = Client.get().getWidget(packedId);
		return w != null && !w.isHidden() && w.isCanvasAbsolute()
			&& w.getBounds().contains(mouse.getX(), mouse.getY());
	}

	/**
	 * Say, once per session and only when the map is actually open, what the world-map overlays are
	 * drawing at. PathMapOverlay places every tile at {@code tilesFromCentre * pixelsPerTile} from
	 * the middle of MAP_CONTAINER, and that scale is a PLACEHOLDER (see WorldMap's header: the client
	 * has no zoom field, and the widget rectangle plus the fixed +-48 tile load window cannot recover
	 * one). Anchored right, scaled wrong is a hard fault to spot by eye -- a path can look plausible
	 * and end in the wrong place -- so it is stated rather than left to be discovered.
	 *
	 * <p>Deferred to the map being open both because that is when it matters and because the widget
	 * rectangle is what makes the line concrete: it prints the tile span the placeholder implies, so
	 * a glance at the open map is enough to judge it.</p>
	 */
	private void logWorldMapScaleOnce()
	{
		// Gated on the group being loaded, from the set fireWidgetChanges already read this frame, so
		// a session where the map is never opened pays nothing: resolving a widget costs a native
		// call per frame, and this one would otherwise make two of them forever.
		if (loggedWorldMapScale || !isGroupLoaded(WORLDMAP_GROUP))
		{
			return;
		}
		Widget map = Client.get().getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map == null)
		{
			return; // the group is loaded but this component is not resolving yet
		}
		Rectangle r = map.getBounds();
		if (r.width <= 0 || r.height <= 0)
		{
			return; // the widget resolved without a rectangle -- wait for a frame where it has one
		}
		loggedWorldMapScale = true;
		// isHidden() is REPORTED here rather than used as a gate. It reads one component's own
		// IFTYPE_HIDDEN byte at +0x78, which offsets.hpp does not claim to have verified and which does
		// not consult the parent chain the way upstream's isHidden() does -- and it is the interlock
		// AutoWalk uses to stop itself walking onto the open map. Nothing had ever printed what it
		// actually reads at a moment when the map's state is known from outside, so this line is the
		// cheapest way to find out: open the map and compare "isHidden=false" against your own eyes.
		System.out.println("[shim] world map container " + r.x + "," + r.y + " " + r.width + "x"
			+ r.height + " (isHidden=" + map.isHidden() + ", canvasAbsolute=" + map.isCanvasAbsolute()
			+ "): " + WorldMap.scaleNote(r.width, r.height,
			WorldMap.INSTANCE.pixelsPerTileQuiet(), WorldMap.INSTANCE.isZoomCalibrated()));
		net.runelite.api.Point centre = WorldMap.INSTANCE.getWorldMapPosition();
		System.out.println("[shim] world map: " + WorldMap.centreQuantisationNote(centre.getX(),
			centre.getY()));
		dumpWorldMapGroupOnce();
	}

	/** Set once per session; the group dump is a diagnostic, not something to print per map open. */
	private static boolean dumpedWorldMapGroup;

	/**
	 * Every component of the world-map interface group, once per session under {@code KEWL_LOG},
	 * while the map is OPEN. Read-only: it calls the same {@code getWidget} everything else does.
	 *
	 * <p>It exists because four separate questions about the world map are all answered by one look
	 * at this table, and none of them can be answered without the game running:</p>
	 * <ol>
	 *   <li>WHICH COMPONENT IS THE VIEWPORT. Upstream draws on MAP_CONTAINER (7); on this build the
	 *       drawn area might be MAP_DISPLAY (8) or WINDOW (5) instead. The one whose rectangle matches
	 *       where the map visibly is, is the one every projection here should be using.</li>
	 *   <li>WHETHER THE RECTANGLES ARE ABSOLUTE. Each line says {@code abs} or {@code REL}. This was
	 *       written as the open question and it is now largely answered: live on 2026-09-07 the
	 *       minimap resolved canvas-absolute at (1156,8) 152x152 on a 1356-wide canvas, so the
	 *       parent-link walk works for a LOADED group, and the world-map group loads when the map is
	 *       first opened (before that the chain simply reports "is not loaded"). An all-REL table
	 *       here would therefore be a fact about THIS group -- its root parented through the client's
	 *       component table -- and not, as this comment used to say, a derivation that never
	 *       ran.</li>
	 *   <li>WHETHER THEY FORM A PLAUSIBLE PARENT CHAIN, by the same reading that caught the minimap:
	 *       compare the numbers against where the map visibly sits on the canvas.</li>
	 *   <li>A COINCIDENCE-CHECK ON THE SCALE, and only that. If the viewport measures about 384 px
	 *       across then 96 tiles map to 384 px and the 4.0 placeholder happens to be right at ONE zoom
	 *       level. That is not a derivation and must never be shipped as one -- the load window is 96
	 *       tiles at every zoom.</li>
	 * </ol>
	 */
	private void dumpWorldMapGroupOnce()
	{
		if (dumpedWorldMapGroup || System.getenv("KEWL_LOG") == null)
		{
			return;
		}
		dumpedWorldMapGroup = true;
		int[] vp = kewl.Natives.viewport();      // {x, y, width, height}
		System.out.println("[shim] world map group dump, canvas "
			+ (vp != null && vp.length == 4 ? vp[2] + "x" + vp[3] : "unknown")
			+ " -- 'abs' means the parent chain reached a root and the x,y is a CANVAS position;"
			+ " 'REL' means it is relative to a parent nobody could walk up to");
		for (int i = 0; i <= WORLDMAP_MAX_COMPONENT; i++)
		{
			int id = (WORLDMAP_GROUP << 16) | i;
			Widget w = Client.get().getWidget(id);
			if (w == null)
			{
				continue;
			}
			Rectangle b = w.getBounds();
			System.out.println("[shim]   " + WORLDMAP_GROUP + ":" + i + " " + b.x + "," + b.y
				+ " " + b.width + "x" + b.height + (w.isHidden() ? " hidden" : " shown")
				+ (w.isCanvasAbsolute() ? " abs depth=" + w.getChainDepth() : " REL"));
		}
	}

	/** Whether an interface group is loaded, per the snapshot fireWidgetChanges took this frame. */
	private boolean isGroupLoaded(int group)
	{
		for (int g : lastLoadedGroups)
		{
			if (g == group)
			{
				return true;
			}
		}
		return false;
	}
}

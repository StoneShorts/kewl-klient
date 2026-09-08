// The auto-walk driver: turns the plugin's computed path into actual movement.
//
// Upstream Shortest Path is guidance-only -- nothing under java/shortestpath issues a single click.
// Kewl's user asked for the client to walk the path, so this watches the pathfinder's output and
// clicks the ground, which is how a human walks and the only way that works on client-240-6 (the
// client's menu-action function is not derived: DO_ACTION == 0, client/offsets.hpp; posted clicks are
// honoured, proven live 2026-09-06 by the autologin plugin's Login and "CLICK HERE TO PLAY" clicks).
//
// WHY THIS WAS REWRITTEN, 2026-09-07. The user's report was "walking is a little buggy ... make it
// seamless and flawless, like a human". The old driver stepped: it refused to re-aim until the player
// was within 2 tiles of the last waypoint AND THEN waited out a fixed pace of N game ticks, two gates
// in series, so the character reached the tile, STOPPED, and stood still for up to ~1.8 s before the
// next click. On top of that its clock was `cycle / 30` -- a RENDER FRAME counter divided by an
// assumed 30 frames per tick (client/offsets.hpp:195-207 says CYCLE is bumped once per frame callback
// and that the 20 ms cadence "was not re-proven"), so "every 3 ticks" was anywhere from ~0.9 s to ~3 s
// depending on the user's frame rate.
//
// HOW IT MOVES NOW. A human re-clicks while still moving, three to six tiles before arriving, so the
// character never decelerates. That is the whole design:
//
//   * The clock is WALL-CLOCK MILLISECONDS, taken once per frame. Every interval here is a real
//     duration and no longer moves with the frame rate.
//   * The path is prepared each frame: nothing is driven until the search is done (a running
//     Pathfinder hands back a churning partial best-effort route), the ANCHOR -- the index of the
//     path tile nearest the PLAYER -- only ever moves forward, and the run is CUT at the first
//     transport edge (two consecutive steps more than one tile apart, or a plane change), which the
//     player takes themselves.
//   * The target is the FURTHEST step that is still clickable within a reach we can actually support,
//     scanning DOWN toward the anchor so an off-screen far step costs a nearer candidate in the same
//     frame instead of a wasted interval.
//   * The re-click is a CONDITION, not a metronome: distance-to-target down to a re-roll of 3-6 tiles
//     (plus a couple more while running, which is 2 tiles a tick), or a stall, or the path changing
//     under us. The configured delay is a flood FLOOR, not the pace.
//   * It stands down while the user is driving their own mouse, and gives up for good rather than
//     clicking at an unreachable tile forever.
//
// THE WORLD-MAP INTERLOCK IS A SAFETY DEVICE AND IT FAILS CLOSED, 2026-09-07. Because this driver
// posts REAL mouse messages, "may I click right now" is not a rendering question, it is a question
// about someone else's mouse and someone else's open window. The interlock this file shipped with
// leaned entirely on one component's own hidden byte -- an offset client/offsets.hpp never claimed to
// have verified -- and so failed OPEN: read it wrong once and a click goes out onto the open map,
// which pans it, while the mouse-move in front of that click drags the cursor off the map's CLOSE
// button between the user's button-down and button-up, so the map cannot even be shut. That is the
// user's report ("World map when im trying to close it i cant it show random places"). It is now four
// signals and a settling window, UNCERTAINTY MEANS DO NOT CLICK, and the three states are CLOSED,
// OPEN and UNKNOWN with the last two treated alike: see worldMapState and mapState.
//
// WHY THE REACH IS 12 AND NOT "as far as you can see". kewl.api.Game.heightNear falls back to the
// PLAYER'S OWN ground height for any tile more than a tile from an entity -- there is no terrain
// heightmap on this build -- so a projected pixel drifts with distance on any slope and a far click
// lands on a different tile than the one chosen. RuneLite's walkers aim at the furthest on-screen
// step because RuneLite has a heightmap; we do not, so the reach is capped where the guess still
// holds. Lifting it needs the heightmap, not a bigger number.
//
// THE MINIMAP would be the better click surface -- camera-independent, never occluded, no height guess
// at all -- and is blocked by the parent-relative widget-coordinate problem (see PROGRESS/offsets).
// Target selection below is written so a minimap route can be slotted in ahead of the viewport route:
// everything up to and including "which world tile" is independent of how the click is delivered.
//
// TESTABILITY. tick() reads the game; drive() decides. Everything drive() needs about the world
// arrives as a World record, every click leaves through a WalkSink, and every random choice comes from
// an Rng -- the same seam shape the login sequence uses (kewl.plugins.autologin.InputSink), so step
// selection, pacing, the stand-downs and the refusals are tested offline with no native loaded. See
// java-test/kewl/rl/AutoWalkTest.java.
package kewl.rl;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import kewl.Natives;
import kewl.api.Actions;
import kewl.api.Game;
import kewl.api.Input;
import kewl.api.Local;
import shortestpath.ShortestPathPlugin;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.Pathfinder;
import shortestpath.transport.Transport;

class AutoWalk
{
	/**
	 * How far ahead of the player, in tiles, a single click may aim. NOT the RuneLite "furthest step
	 * on screen": see the heightNear note in the file header -- past ~12-14 tiles the ground-height
	 * guess drifts and the click lands on a different tile than the one we chose.
	 */
	static final int DEFAULT_REACH = 12;

	/** The re-click lead, in tiles, re-rolled per click: click again with this many tiles still to go. */
	static final int RECLICK_MIN = 3;
	static final int RECLICK_MAX = 6;

	/** Extra lead while running. Running is 2 tiles a tick, so the same distance is half the time. */
	static final int RUNNING_LEAD_BONUS = 2;

	/** The flood floor between posted clicks, re-rolled each time. A guard, never the pace. */
	static final long FLOOR_MIN_MS = 350;
	static final long FLOOR_MAX_MS = 700;

	/** The floor the recovery branches (stalled, path replaced) are allowed to cut down to. */
	static final long RECOVERY_FLOOR_MS = 600;

	/** No movement for this long with a destination outstanding: click again. */
	static final long STATIONARY_MS = 1200;

	/** The user's own cursor moved within this window: their mouse, not ours. */
	static final long USER_MOUSE_WINDOW_MS = 800;

	/**
	 * How long a CLOSED reading of the world map has to hold before the walker clicks again.
	 *
	 * <p>The only per-component evidence that an interface is shut is its hidden byte, and that offset
	 * has never been verified on this build (client/offsets.hpp). A byte that is really something else
	 * can read "hidden" for a frame here and there; a map that is really shut reads hidden on every
	 * frame from the moment it closes. Requiring the reading to HOLD is the cheapest way to tell those
	 * two apart, and it costs one settling period after a real close, once.</p>
	 */
	static final long MAP_SETTLE_MS = 400;

	/**
	 * How soon a release that the game would not take is tried again. Nothing is clicked while one is
	 * outstanding: a button we posted down and could not let up is a button the game believes the user
	 * is holding, and every mouse-move posted while it is down is a DRAG.
	 */
	static final long RELEASE_RETRY_MS = 30;

	/**
	 * How many world-map components have to be READABLE before their agreement counts as evidence the
	 * map is shut. One component's byte is an assertion; several of them agreeing is a reading.
	 */
	static final int MIN_AGREEING_PROBES = 2;

	/** How long after the user stops touching the mouse before we start clicking again. */
	static final long USER_COOLOFF_MS = 1200;

	/**
	 * How long our own posted click is allowed to read back as "the left button is down".
	 *
	 * <p>THE TRAP this exists for: client/jvm.hpp installs a WH_MOUSE hook that latches
	 * WM_LBUTTONDOWN, and its own comment says it is there to catch "a fast human right-click, or any
	 * synthetic one" -- a WH_MOUSE hook fires for PostMessageW'd messages too. So every walk click WE
	 * post reads back as lbuttonDown on the next snapshot, and a naive "is the user clicking?" guard
	 * would stand down forever after its own first click. The physical cursor POSITION is clean
	 * (jvm.hpp fills it from GetCursorPos + ScreenToClient), only the button latch is contaminated.</p>
	 */
	static final long SELF_CLICK_MS = 150;

	/** Cursor movement smaller than this is noise, not a hand on the mouse. */
	static final int USER_MOUSE_SLOP_PX = 6;

	/**
	 * Within this many tiles of the last step the walk is over.
	 *
	 * <p>ONE, and it must stay small. It was 5, to mirror ShortestPathConfig.reachedDistance()'s
	 * default -- the distance at which ShortestPathPlugin.onGameTick CLEARS the target -- so that the
	 * driver would say "arrived" rather than "no path". That reasoning conflated two different
	 * distances and made the driver refuse to walk at all: the plugin's radius is measured to the
	 * TARGET, this one is measured to the END OF THE PATH, and a path shorter than the radius starts
	 * inside it. Seen live 2026-09-07: a 6-step path was set, the tiles drew correctly on the scene,
	 * and the driver answered "arrived (5 from target)" on its first tick and never posted a click.
	 * Every short walk was a no-op, and every long one stopped five tiles early -- which is what
	 * "walking is little buggy" looks like from the outside.</p>
	 *
	 * <p>Losing the nicer status line is the whole cost: when the plugin clears the target first the
	 * driver now says "no path", which is exactly what has happened and is not a lie.</p>
	 */
	static final int ARRIVED_DISTANCE = 1;

	/** Further than this from the anchor tile and the anchor is rescanned over the whole path. */
	static final int RESCAN_DISTANCE = 10;

	/**
	 * How far forward the monotone anchor scan looks. Bounded because a path can pass close to itself
	 * (around a wall, through a doorway and back), and an unbounded "nearest tile on the whole path"
	 * would happily skip the section in between. The player never advances more than 2 tiles a tick.
	 */
	static final int FORWARD_SCAN = 64;

	/** Candidate steps tried in one frame before giving the frame back. */
	static final int MAX_CANDIDATES = 8;

	/** A candidate this close to the tile we already aimed at is the same destination; skip it. */
	static final int SAME_TARGET_SLOP = 2;

	/** Re-aims with no net progress toward the path end before the give-up latch closes. */
	static final int GIVE_UP_AIMS = 3;
	static final long GIVE_UP_MS = 5000;

	/** After a whole candidate scan found nothing clickable, wait this long before scanning again. */
	static final long SCAN_RETRY_MS = 150;

	/** How often the "still closing on the target" test takes a sample. */
	static final long CLOSING_SAMPLE_MS = 200;

	/**
	 * The standard OSRS run-toggle varp (0 = walk, 1 = run). UNVERIFIED on client-240-6 -- toggle run
	 * in game and watch kewl.Natives.varp(173) to confirm. Nothing breaks if it reads wrong: the only
	 * thing it buys is {@link #RUNNING_LEAD_BONUS} tiles of extra lead, and 0/unknown means "walking",
	 * which is the earlier, more conservative re-click.
	 */
	static final int RUN_VARP = 173;

	/** The client's game-state value for "in the world". Nothing is clicked at any other state. */
	private static final int STATE_LOGGED_IN = 30;

	/** The loaded scene is 104x104 tiles; a tile outside it cannot be projected, let alone clicked. */
	private static final int SCENE_SIZE = 104;

	/**
	 * Where a walk click goes. The seam that makes {@link #drive} testable offline: tests hand it a
	 * recording implementation, the plugin hands it {@link #NATIVE}.
	 *
	 * <p>A REFUSAL POSTS NOTHING. OFF_SCREEN / OFF_CANVAS / NOT_LOADED / NOT_LOGGED_IN are all
	 * decided before any message is queued, which is what lets the driver walk a candidate list down
	 * toward the player inside one frame: at most one click ever actually goes out.</p>
	 */
	interface WalkSink
	{
		/** A whole click on a tile: move, down, up. The shape proven in-game on client-240-6. */
		Actions.WalkResult walk(int worldX, int worldY);

		/** Move and hold the button down, for the split click. Default: the proven whole click. */
		default Actions.WalkResult press(int worldX, int worldY)
		{
			return walk(worldX, worldY);
		}

		/** Let a {@link #press} up. Default: nothing was left held, so nothing to do. */
		default boolean release()
		{
			return true;
		}
	}

	/** The real thing. Prefers the client's action function, falls back to a posted ground click. */
	static final WalkSink NATIVE = new WalkSink()
	{
		@Override
		public Actions.WalkResult walk(int worldX, int worldY)
		{
			return Actions.walk(worldX, worldY);
		}

		@Override
		public Actions.WalkResult press(int worldX, int worldY)
		{
			return Actions.press(worldX, worldY);
		}

		@Override
		public boolean release()
		{
			return Actions.release();
		}
	};

	/** Every random choice this driver makes, so a test can pin them. Bounds are inclusive. */
	interface Rng
	{
		int between(int lo, int hi);
	}

	private static final Rng DEFAULT_RNG = new Rng()
	{
		private final java.util.Random random = new java.util.Random();

		@Override
		public int between(int lo, int hi)
		{
			return hi <= lo ? lo : lo + random.nextInt(hi - lo + 1);
		}
	};

	/** Names the transport on a path edge, for the status line. Null when there is nothing to name. */
	interface TransportNamer
	{
		String name(PathStep from, PathStep to);
	}

	/**
	 * The knobs. Constants today rather than config reads, because this file does not own
	 * ShortestPathConfig: when {@code autoWalkReach} / {@code autoWalkHumanise} / the cool-off land
	 * there with getters on the plugin, build this from them in {@link #tick} and nothing else moves.
	 *
	 * @param reach      how far ahead one click may aim, in tiles
	 * @param floorMs    the configured minimum gap between clicks (autoWalkClickDelay, in ticks, as
	 *                   milliseconds) -- a floor, not a pace
	 * @param splitClick post the button down and the button up on different frames, with a human dwell
	 *                   between. OFF by default: only the single-call shape has been seen working
	 *                   in-game (client/jvm.hpp leaves NXT's GetAsyncKeyState use as an open
	 *                   question), and a split click that loses its release leaves the game believing
	 *                   the user is holding the button down.
	 */
	record Settings(int reach, long floorMs, boolean splitClick)
	{
	}

	/**
	 * Everything {@link #drive} needs to know about this frame, as one value.
	 *
	 * @param loggedIn          client game state is 30; false means the title screen, a hop or a load,
	 *                          and nothing may be clicked
	 * @param plane             the player's floor, or -1 when the client could not read one
	 *                          (ENTITY_PLANE is SUSPECT this build) -- which is not fatal, see
	 *                          {@link #advanceAnchor}
	 * @param millis            wall clock, taken once per frame. The ONLY clock this driver has: the
	 *                          client's cycle counter is a render-frame counter and the client's own
	 *                          tick field (client+0x31A8, named in offsets.hpp) is underived
	 * @param fineX             the player's RENDER position in fine units (128 to a tile). Used only
	 *                          as a corroborating "mid-step" signal -- offsets.hpp flags the fine
	 *                          coordinates as not verified while moving, so the tile delta is
	 *                          authoritative and this only ever adds movement, never removes it
	 * @param running           run is toggled on and there is energy for it; see {@link #RUN_VARP}
	 * @param menuOpen          a menu is on screen (ours, or another plugin's)
	 * @param userMouseX        the USER'S physical cursor in canvas coordinates (GetCursorPos +
	 *                          ScreenToClient in client/jvm.hpp) -- not the game's cursor, which our
	 *                          own posted moves drag around
	 * @param userMouseInCanvas their cursor is over the game window rather than the launcher strip
	 * @param userLeftDown      the left button latch, which OUR posted clicks also set: see
	 *                          {@link #SELF_CLICK_MS}
	 * @param userMiddleDown    the middle button, which is how a player drags the camera around. A
	 *                          posted mouse-move during that drag swings their camera; nothing we post
	 *                          ever sets this one, so it needs no self-suppression at all
	 * @param worldMap          what we know about the world map this frame -- three states, not two.
	 *                          See {@link #standDownReason}: this driver walks by POSTING A REAL LEFT
	 *                          CLICK at a projected scene pixel, and the game receives that click
	 *                          whatever is drawn over the scene, so anything but a confident CLOSED is
	 *                          a reason not to click
	 */
	record World(boolean loggedIn, int playerX, int playerY, int plane, int baseX, int baseY,
				 long millis, int fineX, int fineY, boolean running,
				 boolean menuOpen, int userMouseX, int userMouseY, boolean userMouseInCanvas,
				 boolean userLeftDown, boolean userRightDown, boolean userMiddleDown,
				 MapState worldMap)
	{
	}

	/**
	 * What the driver knows about the world map. THREE states on purpose: the old boolean could only
	 * say "open" or "not open", and every way of failing to read the map collapsed into "not open",
	 * which is the fail-OPEN this file was rewritten to remove.
	 */
	enum MapState
	{
		/** Several independent readings agree the map is not on screen. The only state that clicks. */
		CLOSED,
		/** Something says the map is on screen. */
		OPEN,
		/** The readings are missing or disagree. Treated exactly like OPEN: the map MIGHT be up. */
		UNKNOWN
	}

	/**
	 * One world-map component as this build can be asked about it. A record so {@link #mapState} is a
	 * pure function of readings and is tested without a game behind it.
	 *
	 * @param present       the client's interface manager resolved the component id at all
	 * @param hidden        the component's own IFTYPE_HIDDEN byte -- an offset client/offsets.hpp has
	 *                      never claimed to have verified, which is why it is never trusted alone
	 * @param width         its rectangle's width; only the AREA is ever used, never the position
	 * @param height        its rectangle's height
	 * @param chainResolved the parent chain reached a root, so the rectangle is a CANVAS rectangle.
	 *                      Recorded rather than required: the world map is parented ACROSS interface
	 *                      groups on this build and the shim has no offset for that, so this is
	 *                      expected to be false forever here (Widget.getCanvasLocation says so in as
	 *                      many words). Requiring it would stand the walker down permanently; what it
	 *                      does tell us is that the rectangle's POSITION is meaningless, which is why
	 *                      only its area is read below
	 */
	record MapProbe(boolean present, boolean hidden, int width, int height, boolean chainResolved)
	{
		static final MapProbe ABSENT = new MapProbe(false, false, 0, 0, false);

		boolean hasArea()
		{
			return width > 0 && height > 0;
		}
	}

	private final ShortestPathPlugin plugin;
	private final WalkSink sink;
	private final Rng rng;
	private TransportNamer namer;

	AutoWalk(ShortestPathPlugin plugin)
	{
		this(plugin, NATIVE, DEFAULT_RNG);
		this.namer = this::namePluginTransport;
	}

	AutoWalk(ShortestPathPlugin plugin, WalkSink sink)
	{
		this(plugin, sink, DEFAULT_RNG);
	}

	AutoWalk(ShortestPathPlugin plugin, WalkSink sink, Rng rng)
	{
		this.plugin = plugin;
		this.sink = sink;
		this.rng = rng;
	}

	/** Override how a transport edge is named in the status line (tests, and the plugin-backed one). */
	void setTransportNamer(TransportNamer namer)
	{
		this.namer = namer;
	}

	/**
	 * Called every frame while the plugin is enabled and auto-walk is switched on.
	 *
	 * @return a one-line status for the control panel. Never null and never silent: if it is not
	 *     walking it says why, and it names the numbers behind the decision so a live report is
	 *     diagnosable without a rebuild.
	 */
	String tick()
	{
		long millis = System.nanoTime() / 1_000_000L;
		Local me = Game.me();
		// Not in the world (or the scene has not loaded): say so and click nothing. Actions.walk
		// refuses this too, but a driver that leaves the decision to its sink cannot report it.
		if (me == null || !me.exists() || !Game.ready() || Natives.gameState() != STATE_LOGGED_IN)
		{
			return drive(offline(millis), null, null, false, settings());
		}

		net.runelite.api.ClientState state = net.runelite.api.Client.get().state();
		net.runelite.api.Point mouse = state.getMouseCanvasPosition();
		int mx = mouse == null ? -1 : mouse.getX();
		int my = mouse == null ? -1 : mouse.getY();
		// The canvas bound matters: the launcher's control strip docks to the RIGHT of the game
		// window (client/dllmain.cpp), so a cursor sitting over the panel is at a canvas coordinate
		// past the width -- the user reading the status line is not the user playing, and standing
		// down for it would mean the walk stops whenever they look at the panel.
		Input.Target target = Input.target(false);
		boolean inCanvas = target.exists() && mx >= 0 && my >= 0 && mx < target.w() && my < target.h();

		World w = new World(true, me.worldX(), me.worldY(), me.plane(),
			Game.sceneBaseX(), Game.sceneBaseY(), millis, me.fineX(), me.fineY(),
			running(me), state.isMenuOpen(), mx, my, inCanvas,
			state.lbuttonDown(), state.rbuttonDown(), state.mbuttonDown(), worldMapState(state));

		Pathfinder finder = plugin == null ? null : plugin.getPathfinder();
		List<PathStep> path = finder == null ? null : finder.getPath();
		return drive(w, path, finder, finder != null && finder.isDone(), settings());
	}

	/** The interface group the world map lives in; its component ids are {@code (group << 16) | i}. */
	private static final int WORLDMAP_GROUP =
		net.runelite.api.gameval.InterfaceID.Worldmap.MAP_CONTAINER >>> 16;

	/**
	 * The world-map components this driver asks about, and why these four.
	 *
	 * <p>WINDOW is the whole map window, MAP_CONTAINER the viewport upstream draws on, MAP_DISPLAY the
	 * surface inside it, and CLOSE the button the user was pressing when the walker dragged the cursor
	 * off it. They are separate records in the interface table, so a hidden byte read from the wrong
	 * offset has to lie the same way four times to pass for a shut map -- while ANY ONE of them
	 * reading "shown with a rectangle" is enough to stand the walker down.</p>
	 */
	private static final int[] MAP_PROBE_IDS = {
		net.runelite.api.gameval.InterfaceID.Worldmap.MAP_CONTAINER,
		net.runelite.api.gameval.InterfaceID.Worldmap.WINDOW,
		net.runelite.api.gameval.InterfaceID.Worldmap.MAP_DISPLAY,
		net.runelite.api.gameval.InterfaceID.Worldmap.CLOSE,
	};

	/** Set once if the DLL predates the loadedGroups native, so a stale DLL degrades instead of throwing. */
	private static boolean loadedGroupsMissing;

	/**
	 * Read every signal we have about the world map, and hand the verdict to {@link #mapState}.
	 *
	 * <p>WHY MORE THAN ONE SIGNAL, and why this is the safety device rather than a nicety. This driver
	 * has no menu action to issue (DO_ACTION is 0 on this build), so every re-aim is a REAL
	 * WM_MOUSEMOVE + WM_LBUTTONDOWN + WM_LBUTTONUP posted at a projected scene pixel. The game takes
	 * that click whatever is drawn over the scene: with the map up it pans the map to somewhere else,
	 * and the move that precedes it drags the cursor off the CLOSE button between the user's own
	 * button-down and button-up, so the map will not even close. That is the user's report, verbatim.
	 * A wrong "the map is shut" is therefore not a missed frame of walking -- it is the bug.</p>
	 *
	 * <p>THE SIGNALS, weakest last:</p>
	 * <ol>
	 *   <li>THE GROUP LOAD LIST ({@code Natives.loadedGroups}, the client's own list of interface
	 *       groups whose component data is loaded). Authoritative in one direction and cheap: the
	 *       client cannot be drawing an interface whose components it has not loaded. Live evidence
	 *       today: with the map never opened, {@code widgetAbs WORLDMAP 595:7 is not loaded}. This is
	 *       the signal that lets a session that never touches the map walk at full speed while paying
	 *       one native call a frame and no widget lookups at all.</li>
	 *   <li>WHETHER THE COMPONENTS RESOLVE AT ALL. If the group list says loaded and not one component
	 *       comes back, the two disagree, and a disagreement is not a shut map -- it is UNKNOWN.</li>
	 *   <li>THE RECTANGLE, area only. A component that claims to be shown and has no rectangle is a
	 *       contradiction, not a closed map. The POSITION is never read: see
	 *       {@link MapProbe#chainResolved()} -- the world map is parented across groups, the chain does
	 *       not resolve, and the numbers are parent-relative.</li>
	 *   <li>THE HIDDEN BYTE, of four components, all of which must agree. The one signal the old
	 *       interlock had, and the one nobody has verified. It can only ever be a corroborator here:
	 *       it decides CLOSED only when the group is loaded, at least
	 *       {@link #MIN_AGREEING_PROBES} components were readable, and every one of them said hidden
	 *       -- and even then the reading has to HOLD for {@link #MAP_SETTLE_MS} before a click goes out
	 *       (see {@link #standDownReason}).</li>
	 * </ol>
	 *
	 * <p>THE COMBINATION IS SAFER THAN ANY ONE because each signal fails in a different direction and
	 * the verdict takes the pessimistic one every time. The group list can only say "certainly not
	 * loaded", never "shown". The hidden byte can be garbage, so it is outvoted by the rectangle, by
	 * its own siblings, and by time. Nothing can produce CLOSED on its own; every one of them can
	 * produce OPEN or UNKNOWN on its own, and both of those stand the walker down. The cost of being
	 * wrong the safe way is a status line saying which signal refused, which is diagnosable live; the
	 * cost of being wrong the other way is the user's mouse being fought while their map flies around.</p>
	 */
	private static MapState worldMapState(net.runelite.api.ClientState state)
	{
		Boolean loaded = groupLoaded(WORLDMAP_GROUP);
		if (loaded == null)
		{
			return MapState.UNKNOWN;
		}
		if (!loaded)
		{
			// Not loaded is the only cheap answer, and it is the common one: nothing below runs, so a
			// session that never opens the map never pays for a widget lookup here. Once the map HAS
			// been opened the group stays loaded, and from then on this costs four widget resolutions
			// a frame -- deliberately, and only while auto-walk is switched on (RlitePlugin only ticks
			// the driver then). Four native reads a frame is the price of not clicking on the map.
			return MapState.CLOSED;
		}
		List<MapProbe> probes = new java.util.ArrayList<>(MAP_PROBE_IDS.length);
		for (int id : MAP_PROBE_IDS)
		{
			net.runelite.api.widgets.Widget widget;
			try
			{
				widget = state.getWidget(id);
			}
			catch (RuntimeException e)
			{
				return MapState.UNKNOWN; // could not ask: that is not an answer of "shut"
			}
			probes.add(widget == null ? MapProbe.ABSENT
				: new MapProbe(true, widget.isHidden(), widget.getWidth(), widget.getHeight(),
					widget.isCanvasAbsolute()));
		}
		return mapState(true, true, probes);
	}

	/**
	 * The verdict, as a pure function of the readings. UNCERTAINTY MEANS DO NOT CLICK: every path that
	 * is not "the group is not loaded" or "every component that answered said hidden" ends in UNKNOWN,
	 * and {@link #standDownReason} treats UNKNOWN exactly like OPEN.
	 *
	 * @param groupKnown  the loaded-group list could be read at all
	 * @param groupLoaded the world-map group is in it
	 * @param probes      one reading per component of {@link #MAP_PROBE_IDS}
	 */
	static MapState mapState(boolean groupKnown, boolean groupLoaded, List<MapProbe> probes)
	{
		if (!groupKnown)
		{
			return MapState.UNKNOWN;
		}
		if (!groupLoaded)
		{
			return MapState.CLOSED;
		}
		int present = 0;
		int hidden = 0;
		boolean contradiction = false;
		for (MapProbe p : probes)
		{
			if (!p.present())
			{
				continue;
			}
			present++;
			if (!p.hidden() && p.hasArea())
			{
				return MapState.OPEN; // one component drawn on screen is an open map
			}
			if (p.hidden())
			{
				hidden++;
			}
			else
			{
				// Shown, with no rectangle. Two readings of the same component disagree, so neither of
				// them is evidence: this is the "I cannot tell" the whole tri-state exists for.
				contradiction = true;
			}
		}
		if (contradiction || present < MIN_AGREEING_PROBES || hidden != present)
		{
			return MapState.UNKNOWN;
		}
		return MapState.CLOSED;
	}

	/**
	 * Is an interface group's component data loaded? {@code null} means the question could not be
	 * asked -- an older DLL without the native, or an empty answer, which is not a reading of "no
	 * groups are loaded" but a reading of nothing at all.
	 */
	private static Boolean groupLoaded(int group)
	{
		if (loadedGroupsMissing)
		{
			return null;
		}
		int[] groups;
		try
		{
			groups = kewl.Natives.loadedGroups();
		}
		catch (UnsatisfiedLinkError | RuntimeException e)
		{
			loadedGroupsMissing = true;
			System.out.println("[autowalk] loadedGroups is not in this DLL -- the world-map interlock"
				+ " has lost its strongest signal and now refuses to walk at all. Rebuild the DLL");
			return null;
		}
		if (groups == null || groups.length == 0)
		{
			return null;
		}
		for (int g : groups)
		{
			if (g == group)
			{
				return true;
			}
		}
		return false;
	}

	/** The world at a frame where nothing may be clicked. */
	private static World offline(long millis)
	{
		return new World(false, 0, 0, 0, 0, 0, millis, 0, 0, false, false, -1, -1, false, false, false,
			false, MapState.UNKNOWN);
	}

	/**
	 * Is the player running? See {@link #RUN_VARP}: the id is standard but unconfirmed on this build,
	 * so anything but a positive read is treated as walking -- the conservative answer, which only
	 * costs a slightly earlier re-click.
	 */
	private static boolean running(Local me)
	{
		return Natives.varp(RUN_VARP) > 0 && me.runEnergy() > 0;
	}

	/**
	 * The pace setting, floored at one tick, as milliseconds. autoWalkClickDelay is a FLOOD FLOOR now
	 * rather than the pace -- the pace is the re-click condition -- so this is the minimum gap between
	 * posted clicks and nothing else. A game tick is 600 ms.
	 */
	private Settings settings()
	{
		int ticks = plugin == null ? 3 : Math.max(1, plugin.getAutoWalkClickDelay());
		return new Settings(DEFAULT_REACH, ticks * 600L, false);
	}

	/**
	 * The whole decision, with no native in it. One ordered pass, every branch of which returns a
	 * status line naming what it decided and the numbers behind it.
	 *
	 * @param pathIdentity the object the path belongs to (the {@code Pathfinder}); a new one means the
	 *                     plugin recomputed and the anchor must be re-anchored
	 * @param searchDone   the pathfinder has finished. A running one hands back a partial best-effort
	 *                     route rebuilt from the current best node, which changes on nearly every call
	 *                     -- driving it means clicking at a half-finished path that may point the
	 *                     wrong way
	 */
	String drive(World w, List<PathStep> path, Object pathIdentity, boolean searchDone, Settings s)
	{
		// A split click owes the game a button-up before anything else is decided, including on the
		// frames where we are about to return early: a release that never goes out is a button the
		// game believes the user is still holding -- and every mouse-move posted while it is down is a
		// DRAG, which is the failure being fixed here. A release the game would not take used to clear
		// the debt anyway and leave the button down for ever; it is retried now, and nothing is
		// clicked while one is outstanding (see click()).
		if (releaseDueAt != 0 && w.millis() >= releaseDueAt)
		{
			releaseDueAt = sink.release() ? 0 : w.millis() + RELEASE_RETRY_MS;
		}

		if (!w.loggedIn())
		{
			reset();
			return "not logged in";
		}

		trackMotion(w);
		trackUser(w);
		trackMap(w);

		if (path == null || path.isEmpty())
		{
			// The identity goes too: whatever comes back next must be re-anchored on the player
			// rather than resumed from an index that belonged to a path we no longer have.
			forgetWalk();
			lastPathIdentity = null;
			return "no path";
		}

		if (pathIdentity != lastPathIdentity)
		{
			lastPathIdentity = pathIdentity;
			forgetWalk();
			anchor = nearestIndexToPlayer(path, w);
			anchorTile = path.get(anchor).getPackedPosition();
			bestToEnd = Integer.MAX_VALUE;
			lastProgressMillis = w.millis();
		}

		if (!searchDone)
		{
			return "waiting: path still computing";
		}

		// Done? Checked before anything is aimed at, so the driver stops the moment the walk is over
		// rather than re-clicking the tile the player is standing on.
		int end = path.get(path.size() - 1).getPackedPosition();
		int toEnd = chebyshev(w, end);
		if (toEnd <= ARRIVED_DISTANCE)
		{
			forgetWalk();
			return "arrived (" + toEnd + " from target)";
		}

		String standDown = standDownReason(w);
		if (standDown != null)
		{
			return standDown;
		}

		if (gaveUpLine != null)
		{
			return gaveUpLine;
		}

		int at = advanceAnchor(path, w);
		int limit = limitIndex(path, at);

		// Nothing walkable before the cut: the next thing on the path is a transport or a staircase,
		// and it is the player's to take. Once they cross it the anchor jumps past and the run
		// resumes on its own.
		if (limit <= at + 1)
		{
			if (limit >= path.size())
			{
				return "waiting: nothing further on the path to walk to";
			}
			return crossingLine(path, limit);
		}

		int remaining = lastTarget == WorldPointUtil.UNDEFINED ? -1 : chebyshev(w, lastTarget);
		boolean closing = sampleClosing(w, remaining);
		boolean stalled = lastMoveMillis != Long.MIN_VALUE
			&& w.millis() - lastMoveMillis > STATIONARY_MS;

		// The one ordered decision. A human re-clicks with tiles still to go, so the character never
		// decelerates; everything else here is recovery.
		boolean recovery = pathReplaced || (stalled && lastTarget != WorldPointUtil.UNDEFINED);
		boolean due = lastTarget == WorldPointUtil.UNDEFINED
			|| remaining <= reclickAt
			|| recovery;
		if (!due)
		{
			return (closing ? "holding: closing on " : "holding: ") + tile(lastTarget)
				+ " (" + remaining + " left)";
		}

		// The floor is a guard against a flood, never the pace. A refused click never reaches it,
		// because lastClickMillis is only written after something was actually posted.
		long floor = recovery ? Math.min(clickFloorMs, RECOVERY_FLOOR_MS) : clickFloorMs;
		long since = w.millis() - lastClickMillis;
		if (lastClickMillis != Long.MIN_VALUE && since >= 0 && since < floor)
		{
			return "holding: " + tile(lastTarget) + " (" + remaining + " left, click floor "
				+ floor + "ms)";
		}

		// A whole scan that found nothing clickable is not worth repeating thirty times a second:
		// the camera has not moved in 30 ms either.
		if (scanFailedLine != null && w.millis() - scanFailedMillis < SCAN_RETRY_MS)
		{
			return scanFailedLine;
		}

		return click(w, path, at, limit, remaining, toEnd, stalled, s);
	}

	/**
	 * Pick a target and post one click at it. Candidates run from the furthest step the reach allows
	 * DOWN toward the anchor, and the first one that actually projects wins -- scanning down rather
	 * than giving up is what stops an off-screen far step from costing a whole interval.
	 */
	private String click(World w, List<PathStep> path, int at, int limit, int remaining, int toEnd,
						 boolean stalled, Settings s)
	{
		// A button we posted down is still down. The next thing a click does is post a MOUSE-MOVE, and
		// a move with the button down is a drag -- of the game's cursor, across whatever is under it.
		// Wait for the release to go out; drive() is retrying it every frame.
		if (releaseDueAt != 0)
		{
			return "holding: the button we posted is still down -- waiting for its release";
		}

		int top = Math.min(at + s.reach(), limit - 1);
		boolean sawCandidate = false;
		int tried = 0;
		int nearestTried = WorldPointUtil.UNDEFINED;
		Actions.WalkResult refusal = null;

		for (int i = top; i > at && tried < MAX_CANDIDATES; i--)
		{
			int packed = path.get(i).getPackedPosition();
			if (w.plane() >= 0 && WorldPointUtil.unpackWorldPlane(packed) != w.plane())
			{
				continue;
			}
			if (!inScene(w, packed))
			{
				continue;
			}
			if (chebyshev(w, packed) > s.reach())
			{
				continue;
			}
			sawCandidate = true;
			// Re-issuing the destination we are already walking to buys nothing and costs a click --
			// unless we are stalled, in which case retrying the same tile is the entire point.
			if (!stalled && lastTarget != WorldPointUtil.UNDEFINED
				&& chebyshev2D(packed, lastTarget) <= SAME_TARGET_SLOP)
			{
				continue;
			}

			tried++;
			nearestTried = packed;
			int x = WorldPointUtil.unpackWorldX(packed);
			int y = WorldPointUtil.unpackWorldY(packed);
			Actions.WalkResult r = s.splitClick() ? sink.press(x, y) : sink.walk(x, y);
			if (r.moved())
			{
				if (s.splitClick() && r == Actions.WalkResult.CLICKED)
				{
					// The button is down; let it up on a later frame, after a human dwell.
					releaseDueAt = w.millis() + rng.between(60, 140);
				}
				lastTarget = packed;
				lastClickMillis = w.millis();
				lastPostedDownMillis = w.millis();
				clickFloorMs = Math.max(s.floorMs(),
					rng.between((int) FLOOR_MIN_MS, (int) FLOOR_MAX_MS));
				reclickAt = lead(w, chebyshev(w, packed));
				closingDistance = chebyshev(w, packed);
				closingMillis = w.millis();
				closingNow = true;
				scanFailedLine = null;
				// Counted per POSTED click, not per frame: a scan that could not find a clickable
				// step is the camera's fault, and giving up on the path for it would be wrong.
				noteProgress(w, toEnd);
				return "walking -> " + tile(packed) + " (step " + (i + 1) + "/" + path.size()
					+ ", " + chebyshev(w, packed) + " tiles, re-click at " + reclickAt + ")";
			}
			refusal = r;
			if (r == Actions.WalkResult.NOT_POSTED || r == Actions.WalkResult.NOT_LOGGED_IN)
			{
				break; // the window is gone or we are not in the world: nearer tiles will not help
			}
		}

		if (!sawCandidate)
		{
			return remember(w, "waiting: no step of the path is in the loaded scene");
		}
		if (refusal == null)
		{
			// Every candidate was the destination we are already walking to. Not a refusal: a hold.
			return "holding: " + tile(lastTarget) + " (" + remaining + " left)";
		}
		String where = tile(nearestTried);
		switch (refusal)
		{
			case OFF_SCREEN:
				// Behind the camera or past the horizon. Nothing here can fix that; the user turning
				// the camera can, so say it rather than failing silently.
				return remember(w, "no step clickable: nearest candidate " + where
					+ " is off screen -- turn the camera");
			case OFF_CANVAS:
				return remember(w, "no step clickable: nearest candidate " + where
					+ " is off canvas -- refusing to click outside the game window");
			case NOT_LOADED:
				return remember(w, "step not loaded yet");
			case NOT_POSTED:
				// The point was fine and the message did not reach the game window (it closed, or its
				// queue refused). Silence here would read as a walk in progress forever.
				return "click not delivered -- the game window did not take it";
			case NOT_LOGGED_IN:
			default:
				return "not logged in";
		}
	}

	/**
	 * How many tiles short of the destination we click again, for the click just posted.
	 *
	 * <p>A rolled 3-6 tiles (a couple more while running, which is 2 tiles a tick rather than 1) is
	 * the human part. The floor part is what keeps the setting honest: the configured minimum gap
	 * between clicks is a real constraint, so the lead must be at least as many tiles as the player
	 * covers while that gap runs out -- otherwise a long autoWalkClickDelay puts the character back to
	 * arriving, stopping and waiting, which is the exact complaint this rewrite answers. Running is
	 * ~3.33 tiles a second, walking ~1.67 (1.67 game ticks a second, 2 tiles and 1 tile a tick).</p>
	 *
	 * <p>Capped just under the distance we actually clicked: a lead as long as the trip would re-click
	 * on the very next frame, which the flood floor would then refuse all the way to the destination.</p>
	 */
	private int lead(World w, int clickedDistance)
	{
		int rolled = rng.between(RECLICK_MIN, RECLICK_MAX) + (w.running() ? RUNNING_LEAD_BONUS : 0);
		int forFloor = (int) Math.ceil(clickFloorMs * (w.running() ? 10 : 5) / 3000.0);
		return Math.min(Math.max(rolled, forFloor), Math.max(RECLICK_MIN, clickedDistance - 2));
	}

	/** Park a failed-scan line so the next 150 ms of frames repeat it instead of rescanning. */
	private String remember(World w, String line)
	{
		scanFailedLine = line;
		scanFailedMillis = w.millis();
		return line;
	}

	/**
	 * The give-up latch, and the answer to "it clicked forever and fought my mouse": when three
	 * consecutive re-aims spanning more than five seconds make no net progress toward the END of the
	 * path, stop clicking for this path entirely. An unreachable last step (ShortestPathPlugin
	 * .isPathUnreachable) is the case that used to click for ever, because the player never gets
	 * within the arrival distance of it.
	 *
	 * <p>The latch closes on the frame of the click that broke the camel's back, so that click still
	 * goes out and the line appears from the next frame. It clears on a new Pathfinder identity, on
	 * arrival, and on the auto-walk toggle being cycled -- never by itself.</p>
	 */
	private void noteProgress(World w, int toEnd)
	{
		if (toEnd < bestToEnd)
		{
			bestToEnd = toEnd;
			lastProgressMillis = w.millis();
			noProgressAims = 0;
			return;
		}
		noProgressAims++;
		long stuckFor = w.millis() - lastProgressMillis;
		if (noProgressAims >= GIVE_UP_AIMS && stuckFor > GIVE_UP_MS)
		{
			gaveUpLine = "gave up: no progress for "
				+ String.format(Locale.ROOT, "%.1f", stuckFor / 1000.0) + "s";
		}
	}

	/**
	 * Why we are not clicking this frame because the user is using their own mouse, or null.
	 *
	 * <p>The complaint this answers is the walker fighting the user for the cursor. A plain cursor
	 * movement counts -- someone moving the mouse across the screen without meaning to play trips it
	 * too, and that is the right trade -- and the cool-off keeps us stood down for a moment after
	 * they stop, so grabbing the mouse mid-run is not immediately fought.</p>
	 */
	private String standDownReason(World w)
	{
		if (w.menuOpen())
		{
			return "standing by: menu open";
		}
		// THE USER'S REPORT, LITERALLY: "worldmap setting target somehow clicks on the worldmap and
		// flies to another area of the map." This is where it comes from, and it is nothing to do with
		// the map maths. This driver does not issue a menu action -- DO_ACTION is 0 on this build, so
		// Actions.walk PROJECTS the next path tile to a scene pixel and POSTS A REAL WM_LBUTTONDOWN
		// there (see kewl.api.Actions). The game receives that click regardless of what is drawn over
		// the scene, so with the world map open every re-aim was a genuine left click landing on the
		// map, which pans it. Setting a target on the map and watching it fly away IS this loop.
		//
		// A per-pixel containment test would be finer, but the rectangle it needs is only meaningful
		// once the map's parent chain resolves -- and while it has not, we do not know where the map
		// is at all. So the whole map is the obstruction: refuse while it is up, say so, and resume
		// once it has been shut for a moment (nothing is latched, and the lead re-click picks the run
		// back up). UNKNOWN is refused the same way OPEN is, and says so differently, because a driver
		// that cannot see the map is a driver that must not post clicks at it: see worldMapState.
		if (w.worldMap() == MapState.OPEN)
		{
			return "standing by: the world map is open -- a walk click would land on the map";
		}
		if (w.worldMap() != MapState.CLOSED)
		{
			return "standing by: cannot tell whether the world map is open -- not clicking";
		}
		if (mapNotClosedMillis != Long.MIN_VALUE && w.millis() - mapNotClosedMillis < MAP_SETTLE_MS)
		{
			return "standing by: the world map has only just read as closed ("
				+ (w.millis() - mapNotClosedMillis) + "ms of " + MAP_SETTLE_MS + ")";
		}
		if (w.userRightDown())
		{
			return "standing by: right button held";
		}
		// The camera drag. Held for a second or two at a time, and a posted move in the middle of it
		// drags the camera rather than the map -- the same class of failure, a different surface.
		if (w.userMiddleDown())
		{
			return "standing by: middle button held";
		}
		// A HELD left button, not just the instant of the press. The live failure was our posted
		// mouse-move dragging the cursor off the map's CLOSE button BETWEEN the user's button-down and
		// their button-up -- so what has to be covered is the whole span the button is down, and the
		// self-click suppression must never swallow it. See userLeftDown.
		if (userLeftDown(w))
		{
			return "standing by: left button held";
		}
		if (userRecent(w))
		{
			return "standing by: your mouse";
		}
		return null;
	}

	/**
	 * Did the user touch their mouse recently enough that we should keep out of the way? The window
	 * is the movement window plus the cool-off: someone who grabs the mouse mid-run gets a moment
	 * after they stop before the walker starts clicking again, rather than being fought for it.
	 */
	private boolean userRecent(World w)
	{
		return lastUserMillis != 0
			&& w.millis() - lastUserMillis < USER_MOUSE_WINDOW_MS + USER_COOLOFF_MS;
	}

	/**
	 * The left button is down and it is not one of ours -- for as long as it is held, not just at the
	 * press.
	 *
	 * <p>WHAT THE SNAPSHOT ACTUALLY CONTAINS, because the whole test turns on it. client/jvm.hpp's
	 * nInput reports {@code GetAsyncKeyState(VK_LBUTTON) || lbLatched}. GetAsyncKeyState is the
	 * PHYSICAL button and a PostMessageW never touches it, so a finger on the button reads down on
	 * every frame it is held. The latch is set by the WH_MOUSE hook (which does see our posted
	 * messages) and is read-and-CLEARED by the same call, so our own click reads down for exactly ONE
	 * snapshot.</p>
	 *
	 * <p>So the two are told apart by the EDGE and by DURATION, not by a blanket window after our own
	 * click. The old test was "any down within {@link #SELF_CLICK_MS} of our last posted down is ours",
	 * which blinded the driver to a real hold for that whole window every time it clicked -- and that
	 * window is exactly when a posted mouse-move would drag the user's held cursor. Now: a down whose
	 * rising edge did not land on a click of ours is theirs immediately, and a down that is STILL down
	 * {@link #SELF_CLICK_MS} after its edge is theirs whatever caused the edge, because ours cannot
	 * survive its own snapshot.</p>
	 */
	private boolean userLeftDown(World w)
	{
		if (!w.userLeftDown())
		{
			return false;
		}
		if (leftDownSince == Long.MIN_VALUE)
		{
			return true; // down before we ever saw an edge: not attributable to us, so it is theirs
		}
		boolean edgeWasOurs = lastPostedDownMillis != Long.MIN_VALUE
			&& leftDownSince >= lastPostedDownMillis
			&& leftDownSince - lastPostedDownMillis < SELF_CLICK_MS;
		return !edgeWasOurs || w.millis() - leftDownSince >= SELF_CLICK_MS;
	}

	/** Movement history, from the authoritative signal (the server tile) plus a corroborating one. */
	private void trackMotion(World w)
	{
		int tile = WorldPointUtil.packWorldPoint(w.playerX(), w.playerY(), Math.max(0, w.plane()));
		if (tile != lastTileSeen)
		{
			lastTileSeen = tile;
			lastMoveMillis = w.millis();
			return;
		}
		if (lastMoveMillis == Long.MIN_VALUE)
		{
			lastMoveMillis = w.millis();
			return;
		}
		// Bonus signal only: the render tile differs from the server tile, so the character is
		// mid-step even though its tile has not changed yet. offsets.hpp flags ENTITY_FINE_X/Y as not
		// verified while moving, so this may only ADD movement, never withdraw it.
		if ((w.fineX() != 0 || w.fineY() != 0)
			&& ((w.fineX() >> 7) != w.playerX() - w.baseX()
			|| (w.fineY() >> 7) != w.playerY() - w.baseY()))
		{
			lastMoveMillis = w.millis();
		}
	}

	/** The user's own input history: their cursor, their buttons, and when they last touched either. */
	private void trackUser(World w)
	{
		if (w.userMouseInCanvas())
		{
			if (lastUserMouseX != Integer.MIN_VALUE
				&& (Math.abs(w.userMouseX() - lastUserMouseX) > USER_MOUSE_SLOP_PX
				|| Math.abs(w.userMouseY() - lastUserMouseY) > USER_MOUSE_SLOP_PX))
			{
				lastUserMillis = w.millis();
				lastUserMouseX = w.userMouseX();
				lastUserMouseY = w.userMouseY();
			}
			else if (lastUserMouseX == Integer.MIN_VALUE)
			{
				// First sight of the cursor is a baseline, not a movement.
				lastUserMouseX = w.userMouseX();
				lastUserMouseY = w.userMouseY();
			}
		}
		else
		{
			// Over the launcher strip, or outside the window: re-baseline without counting it, so
			// coming back to the canvas is one jump rather than a permanent stand-down.
			lastUserMouseX = w.userMouseX();
			lastUserMouseY = w.userMouseY();
		}
		// The left button's rising EDGE, kept so a held button can be told from our own one-snapshot
		// latch by how long it has been down. Recorded before anything asks userLeftDown() this frame.
		if (w.userLeftDown())
		{
			if (leftDownSince == Long.MIN_VALUE)
			{
				leftDownSince = w.millis();
			}
		}
		else
		{
			leftDownSince = Long.MIN_VALUE;
		}
		if (w.menuOpen() || w.userRightDown() || w.userMiddleDown() || userLeftDown(w))
		{
			lastUserMillis = w.millis();
		}
	}

	/**
	 * When the world map last read as anything other than CLOSED. The settling window this feeds (see
	 * {@link #standDownReason}) is what stops a single flickering hidden byte from unlocking the
	 * walker for one frame -- one frame is one posted click, which is one map pan.
	 */
	private void trackMap(World w)
	{
		if (w.worldMap() != MapState.CLOSED)
		{
			mapNotClosedMillis = w.millis();
		}
	}

	/**
	 * Is the player still closing on the tile we aimed at? Sampled on a timer rather than per frame:
	 * the distance is a tile count and a per-frame comparison of it flaps.
	 */
	private boolean sampleClosing(World w, int remaining)
	{
		if (remaining < 0)
		{
			closingNow = false;
			closingDistance = Integer.MAX_VALUE;
			return false;
		}
		if (w.millis() - closingMillis < CLOSING_SAMPLE_MS)
		{
			return closingNow;
		}
		closingNow = remaining < closingDistance;
		closingDistance = remaining;
		closingMillis = w.millis();
		return closingNow;
	}

	/** Chebyshev distance from the player to a packed world tile (plane ignored). */
	private static int chebyshev(World w, int packed)
	{
		return Math.max(Math.abs(w.playerX() - WorldPointUtil.unpackWorldX(packed)),
			Math.abs(w.playerY() - WorldPointUtil.unpackWorldY(packed)));
	}

	/** Chebyshev distance between two packed tiles, ignoring their planes. */
	private static int chebyshev2D(int a, int b)
	{
		return Math.max(Math.abs(WorldPointUtil.unpackWorldX(a) - WorldPointUtil.unpackWorldX(b)),
			Math.abs(WorldPointUtil.unpackWorldY(a) - WorldPointUtil.unpackWorldY(b)));
	}

	private static boolean inScene(World w, int packed)
	{
		int x = WorldPointUtil.unpackWorldX(packed);
		int y = WorldPointUtil.unpackWorldY(packed);
		return x >= w.baseX() && x < w.baseX() + SCENE_SIZE
			&& y >= w.baseY() && y < w.baseY() + SCENE_SIZE;
	}

	private static String tile(int packed)
	{
		if (packed == WorldPointUtil.UNDEFINED)
		{
			return "nowhere";
		}
		return WorldPointUtil.unpackWorldX(packed) + "," + WorldPointUtil.unpackWorldY(packed);
	}

	/**
	 * Where the anchor is: the index of the path tile nearest the PLAYER, never moving backwards.
	 *
	 * <p>Anchoring on the player rather than on the last click is what stops the driver aiming at
	 * tiles already walked past -- the path itself never shrinks as it is consumed
	 * ({@code Node.getPathSteps} builds the list from the SEARCH START, not from the player), so
	 * something has to track how much of it is behind us. Monotone, with one full rescan when the
	 * player is further than {@link #RESCAN_DISTANCE} from the anchor tile (a teleport, a boat, being
	 * dragged) -- and the rescan covers the WHOLE path, not the first hundred steps, because on a
	 * long route the player is at index 400.</p>
	 *
	 * <p>plane &lt; 0 means the client could not read a plane (ENTITY_PLANE is SUSPECT this build; the
	 * native range-guards obvious garbage to -1). With no plane to match, filtering would reject every
	 * tile and wedge the walk, so it is skipped entirely and the scene bound is the only filter. The
	 * shim's WorldView normalises -1 to the ground floor for the pathfinder, so the path is on plane 0
	 * and an unfiltered walk over it is self-consistent.</p>
	 */
	private int advanceAnchor(List<PathStep> path, World w)
	{
		pathReplaced = false;
		anchor = Math.max(0, Math.min(anchor, path.size() - 1));
		int here = path.get(anchor).getPackedPosition();

		if (anchorTile != WorldPointUtil.UNDEFINED && here != anchorTile)
		{
			// Same Pathfinder, different steps: the search rebuilt its list when it found a better
			// route and this index now denotes a different tile. Re-anchor rather than walking toward
			// a tile the old route happened to touch.
			pathReplaced = true;
			anchor = nearestIndexToPlayer(path, w);
		}
		else if (chebyshev(w, here) > RESCAN_DISTANCE)
		{
			anchor = nearestIndexToPlayer(path, w);
		}
		else
		{
			int best = anchor;
			int bestDist = chebyshev(w, here);
			int stop = Math.min(path.size(), anchor + FORWARD_SCAN);
			for (int i = anchor + 1; i < stop; i++)
			{
				int d = chebyshev(w, path.get(i).getPackedPosition());
				if (d < bestDist)
				{
					bestDist = d;
					best = i;
				}
			}
			anchor = best;
		}

		// After the player takes a staircase themselves, the nearest tile in 2D is still the one they
		// left on the old floor. Skip forward to their own plane rather than stopping there forever.
		while (w.plane() >= 0 && anchor + 1 < path.size()
			&& WorldPointUtil.unpackWorldPlane(path.get(anchor).getPackedPosition()) != w.plane())
		{
			anchor++;
		}

		anchorTile = path.get(anchor).getPackedPosition();
		return anchor;
	}

	/**
	 * The first index past {@code from} that must not be walked into: a transport edge, or a plane
	 * change. Both are the same test -- {@code WorldPointUtil.distanceBetween} is Chebyshev and
	 * returns MAX_VALUE across planes -- because a transport IS an edge: a PathStep carries only a
	 * position, so a boat, a fairy ring, a shortcut or a teleport whose destination happens to land in
	 * the loaded scene appears as two consecutive steps more than one tile apart. The old driver only
	 * looked at the plane, which is why every same-plane transport was clicked straight past.
	 *
	 * @return {@code path.size()} when the rest of the path is walkable
	 */
	private static int limitIndex(List<PathStep> path, int from)
	{
		for (int i = Math.max(1, from + 1); i < path.size(); i++)
		{
			if (WorldPointUtil.distanceBetween(path.get(i - 1).getPackedPosition(),
				path.get(i).getPackedPosition()) > 1)
			{
				return i;
			}
		}
		return path.size();
	}

	/** The status line for a transport or staircase the player has to take themselves. */
	private String crossingLine(List<PathStep> path, int limit)
	{
		PathStep from = path.get(limit - 1);
		PathStep to = path.get(limit);
		int a = from.getPackedPosition();
		int b = to.getPackedPosition();
		if (WorldPointUtil.unpackWorldPlane(a) != WorldPointUtil.unpackWorldPlane(b))
		{
			return "waiting: next floor at step " + (limit + 1) + " -- use the stairs yourself";
		}
		// Named lazily and at most once a second: transportsForEdge walks the pathfinder config's
		// transport collections, which is not something to do on a per-frame path.
		String name = namedTransport(limit, from, to);
		return "waiting: transport at step " + (limit + 1) + " (" + tile(a) + " -> " + tile(b)
			+ (name == null ? "" : ", " + name) + ") -- take it yourself";
	}

	private String namedTransport(int limit, PathStep from, PathStep to)
	{
		if (namer == null)
		{
			return null;
		}
		long now = System.nanoTime() / 1_000_000L;
		if (limit != namedIndex || now - namedMillis > 1000)
		{
			namedIndex = limit;
			namedMillis = now;
			namedTransport = namer.name(from, to);
		}
		return namedTransport;
	}

	/** The plugin-backed namer: the transport type, and the option to pick where there is one. */
	private String namePluginTransport(PathStep from, PathStep to)
	{
		if (plugin == null)
		{
			return null;
		}
		try
		{
			Set<Transport> transports = plugin.transportsForEdge(from, to);
			for (Transport t : transports)
			{
				String type = t.getType() == null ? "transport"
					: t.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
				return t.getDisplayInfo() == null ? type : type + " " + t.getDisplayInfo();
			}
		}
		catch (RuntimeException e)
		{
			// A status line is never worth an exception out of the frame callback.
			return null;
		}
		return null;
	}

	/** Path index of the tile nearest the player, over the WHOLE path. */
	private static int nearestIndexToPlayer(List<PathStep> path, World w)
	{
		int best = 0;
		int bestDist = Integer.MAX_VALUE;
		for (int i = 0; i < path.size(); i++)
		{
			int d = chebyshev(w, path.get(i).getPackedPosition());
			if (d < bestDist)
			{
				bestDist = d;
				best = i;
			}
		}
		return best;
	}

	/** Forget the walk in progress but keep what we know about the user and their mouse. */
	private void forgetWalk()
	{
		lastTarget = WorldPointUtil.UNDEFINED;
		anchor = 0;
		anchorTile = WorldPointUtil.UNDEFINED;
		lastClickMillis = Long.MIN_VALUE;
		clickFloorMs = FLOOR_MIN_MS;
		reclickAt = RECLICK_MIN;
		closingDistance = Integer.MAX_VALUE;
		closingNow = false;
		closingMillis = 0;
		scanFailedLine = null;
		gaveUpLine = null;
		noProgressAims = 0;
		bestToEnd = Integer.MAX_VALUE;
		pathReplaced = false;
	}

	/** Forget everything: new path, or the plugin was disabled. */
	void reset()
	{
		// A pending split click is released here on purpose. Being switched off mid-click must not
		// leave the game believing the user is holding the left button down.
		if (releaseDueAt != 0)
		{
			sink.release();
			releaseDueAt = 0;
		}
		forgetWalk();
		lastPathIdentity = null;
		lastTileSeen = WorldPointUtil.UNDEFINED;
		lastMoveMillis = Long.MIN_VALUE;
		lastUserMillis = 0;
		lastUserMouseX = Integer.MIN_VALUE;
		lastUserMouseY = Integer.MIN_VALUE;
		lastPostedDownMillis = Long.MIN_VALUE;
		lastProgressMillis = 0;
		leftDownSince = Long.MIN_VALUE;
		// NOT cleared: whether the map was open a moment ago is a fact about the screen, not about
		// this walk, and clearing it would let a toggle-off/toggle-on skip the settling window.
		// mapNotClosedMillis stays.
	}

	/** Packed position of the tile the last click aimed at; UNDEFINED before the first one. */
	private int lastTarget = WorldPointUtil.UNDEFINED;
	/** Path index nearest the player; monotone. The next aim scans forward from here. */
	private int anchor;
	/** The packed tile that was at {@link #anchor} last frame; a change means the path was replaced. */
	private int anchorTile = WorldPointUtil.UNDEFINED;
	/** Set for the frame the path was replaced under the same Pathfinder. */
	private boolean pathReplaced;
	/** Wall clock of the last click that was actually posted; MIN_VALUE means "none on this path". */
	private long lastClickMillis = Long.MIN_VALUE;
	/** Wall clock of the last button-DOWN we posted, for suppressing our own click in the user guard. */
	private long lastPostedDownMillis = Long.MIN_VALUE;
	/** This click's flood floor, re-rolled per click. */
	private long clickFloorMs = FLOOR_MIN_MS;
	/** Tiles left when we re-click, re-rolled per click: the lead that keeps the character moving. */
	private int reclickAt = RECLICK_MIN;
	/** When the split click owes the game a button-up; 0 when nothing is held. */
	private long releaseDueAt;
	/** The last tile the player was seen on, and when they last moved. */
	private int lastTileSeen = WorldPointUtil.UNDEFINED;
	/** When the player last moved. MIN_VALUE is "never seen", which is NOT the same as "at time 0". */
	private long lastMoveMillis = Long.MIN_VALUE;
	/** The closing sampler: the distance at the last sample, when it was taken, and its verdict. */
	private int closingDistance = Integer.MAX_VALUE;
	private long closingMillis;
	private boolean closingNow;
	/** The user's cursor baseline, and when they last touched their mouse. */
	private int lastUserMouseX = Integer.MIN_VALUE;
	private int lastUserMouseY = Integer.MIN_VALUE;
	private long lastUserMillis;
	/** When the left button's current down-run began; MIN_VALUE when the button is up. */
	private long leftDownSince = Long.MIN_VALUE;
	/** When the world map last read as OPEN or UNKNOWN; MIN_VALUE means it never has. */
	private long mapNotClosedMillis = Long.MIN_VALUE;
	/** A whole candidate scan that found nothing, and when: repeated rather than redone for a moment. */
	private String scanFailedLine;
	private long scanFailedMillis;
	/** The give-up latch: non-null means stop clicking at this path and keep saying why. */
	private String gaveUpLine;
	private int noProgressAims;
	private int bestToEnd = Integer.MAX_VALUE;
	private long lastProgressMillis;
	/** Identity of the pathfinder the anchor belongs to; a new instance re-anchors. */
	private Object lastPathIdentity;
	/** The transport name cache, so the status line does not walk the transport tables per frame. */
	private int namedIndex = -1;
	private long namedMillis;
	private String namedTransport;
}

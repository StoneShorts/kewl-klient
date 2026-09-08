// The auto-walk driver's decisions, with no game behind them: AutoWalk.drive() takes the world as a
// record, every click leaves through a WalkSink and every random choice comes from an Rng, so step
// selection, the re-click condition, the stand-downs and every refusal are exercised here offline.
// Nothing in this file touches a native -- the same trick LoginSequenceTest plays with InputSink, for
// the same reason: an input driver you can only test by playing the game is an input driver nobody
// tests.
//
// The behaviour these cases exist to protect is "seamless": the driver must issue the next
// destination while the character is STILL MOVING (the lead re-click), must never wait out a fixed
// pace after arriving at a waypoint, must never walk into a transport, and must keep its hands off
// the mouse while the user has theirs on it.
package kewl.rl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import kewl.api.Actions;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.PathStep;

public class AutoWalkTest
{
	/** A scene base far from the origin, so an "out of scene" tile is unambiguous. */
	private static final int BASE_X = 3168, BASE_Y = 3168;

	/** The shipped shape of the settings: reach 12, a 3-tick (1800 ms) flood floor, no split click. */
	private static final AutoWalk.Settings PACE =
		new AutoWalk.Settings(AutoWalk.DEFAULT_REACH, 1800, false);

	/** Records what was asked for, and answers with whatever result the test wants. */
	static final class Sink implements AutoWalk.WalkSink
	{
		final List<String> clicks = new ArrayList<>();
		final List<String> presses = new ArrayList<>();
		final Map<String, Actions.WalkResult> perTile = new HashMap<>();
		Actions.WalkResult answer = Actions.WalkResult.CLICKED;
		int releases;

		@Override
		public Actions.WalkResult walk(int worldX, int worldY)
		{
			String tile = worldX + "," + worldY;
			clicks.add(tile);
			return perTile.getOrDefault(tile, answer);
		}

		@Override
		public Actions.WalkResult press(int worldX, int worldY)
		{
			String tile = worldX + "," + worldY;
			presses.add(tile);
			return perTile.getOrDefault(tile, answer);
		}

		@Override
		public boolean release()
		{
			releases++;
			return true;
		}
	}

	/** Every roll takes its lowest value, so the lead is RECLICK_MIN and the dwell is 60 ms. */
	private static final AutoWalk.Rng LOW = (lo, hi) -> lo;

	private static AutoWalk walker(Sink sink)
	{
		// drive() never touches the plugin; only tick() does, and tick() is the part that needs a game.
		return new AutoWalk(null, sink, LOW);
	}

	/** A straight path east: {@code count} tiles starting at (x,y). */
	private static List<PathStep> line(int x, int y, int count)
	{
		List<PathStep> steps = new ArrayList<>();
		for (int i = 0; i < count; i++)
		{
			steps.add(new PathStep(WorldPointUtil.packWorldPoint(x + i, y, 0), false));
		}
		return steps;
	}

	private static List<PathStep> path(int... xy)
	{
		List<PathStep> steps = new ArrayList<>();
		for (int i = 0; i + 1 < xy.length; i += 2)
		{
			steps.add(new PathStep(WorldPointUtil.packWorldPoint(xy[i], xy[i + 1], 0), false));
		}
		return steps;
	}

	private static AutoWalk.World at(int x, int y, long millis)
	{
		return new AutoWalk.World(true, x, y, 0, BASE_X, BASE_Y, millis, 0, 0, false,
			false, -1, -1, false, false, false, false, AutoWalk.MapState.CLOSED);
	}

	/** The same frame with the user's hands on their own mouse. */
	private static AutoWalk.World user(int x, int y, long millis, boolean menu, int mouseX,
									   boolean left, boolean right)
	{
		return new AutoWalk.World(true, x, y, 0, BASE_X, BASE_Y, millis, 0, 0, false,
			menu, mouseX, 100, true, left, right, false, AutoWalk.MapState.CLOSED);
	}

	/** The same frame with the world map on screen and the user's hands nowhere near the mouse. */
	private static AutoWalk.World mapOpen(int x, int y, long millis)
	{
		return map(x, y, millis, AutoWalk.MapState.OPEN);
	}

	/** The same frame with the world map in whatever state the interlock managed to read. */
	private static AutoWalk.World map(int x, int y, long millis, AutoWalk.MapState state)
	{
		return new AutoWalk.World(true, x, y, 0, BASE_X, BASE_Y, millis, 0, 0, false,
			false, -1, -1, false, false, false, false, state);
	}

	/** A world-map component that resolved and reads hidden, with a rectangle it keeps while shut. */
	private static AutoWalk.MapProbe hidden()
	{
		return new AutoWalk.MapProbe(true, true, 480, 320, false);
	}

	/** A world-map component that resolved and reads shown, at the given size. */
	private static AutoWalk.MapProbe shown(int width, int height)
	{
		return new AutoWalk.MapProbe(true, false, width, height, false);
	}

	/** A component id the interface manager would not resolve at all. */
	private static AutoWalk.MapProbe absent()
	{
		return AutoWalk.MapProbe.ABSENT;
	}

	/** A path object identity, standing in for the Pathfinder instance the plugin hands over. */
	private static final Object PATH_ID = new Object();

	@Test
	public void noPathSaysSoRatherThanNothing()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		assertEquals("no path", w.drive(at(3200, 3200, 0), null, null, true, PACE));
		assertEquals("no path", w.drive(at(3200, 3200, 0), List.of(), PATH_ID, true, PACE));
		assertTrue(sink.clicks.isEmpty());
	}

	@Test
	public void neverClicksWhileNotLoggedIn()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		AutoWalk.World out = new AutoWalk.World(false, 0, 0, 0, 0, 0, 0, 0, 0, false,
			false, -1, -1, false, false, false, false, AutoWalk.MapState.UNKNOWN);
		assertEquals("not logged in", w.drive(out, line(3200, 3200, 20), PATH_ID, true, PACE));
		assertTrue(sink.clicks.isEmpty());
	}

	/**
	 * A running Pathfinder rebuilds its step list from the current best node on nearly every call, so
	 * the path it hands back while searching is a partial that can point the wrong way. The old
	 * driver treated a fresh Pathfinder as "click at once" and fired straight at it.
	 */
	@Test
	public void anUnfinishedSearchIsNeverDriven()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		assertEquals("waiting: path still computing",
			w.drive(at(3200, 3200, 0), line(3200, 3200, 20), PATH_ID, false, PACE));
		assertTrue(sink.clicks.isEmpty());
	}

	@Test
	public void clicksTheFurthestReachableStepAndNamesIt()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		assertEquals("walking -> 3212,3200 (step 13/20, 12 tiles, re-click at 3)",
			w.drive(at(3200, 3200, 0), line(3200, 3200, 20), PATH_ID, true, PACE));
		assertEquals(List.of("3212,3200"), sink.clicks);
	}

	/**
	 * THE fix for the "stepping" the user reported. The next destination goes out while the character
	 * is still walking -- at the re-click lead, three tiles short -- so it never decelerates, and it
	 * does NOT go out earlier than that, which would be a click flood.
	 */
	@Test
	public void reClicksAtTheLeadDistanceWhileStillMoving()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		List<PathStep> p = line(3200, 3200, 20);

		assertEquals("walking -> 3212,3200 (step 13/20, 12 tiles, re-click at 3)",
			w.drive(at(3200, 3200, 0), p, PATH_ID, true, PACE));

		// Six tiles still to go: the client is walking, and we keep our hands off.
		assertEquals("holding: closing on 3212,3200 (6 left)",
			w.drive(at(3206, 3200, 2000), p, PATH_ID, true, PACE));
		assertEquals(1, sink.clicks.size());

		// Three to go -- the lead -- and the next destination is queued mid-stride.
		assertEquals("walking -> 3219,3200 (step 20/20, 10 tiles, re-click at 3)",
			w.drive(at(3209, 3200, 3000), p, PATH_ID, true, PACE));
		assertEquals(List.of("3212,3200", "3219,3200"), sink.clicks);
	}

	/**
	 * The old driver's two gates in series: it refused to re-aim until the player was within 2 tiles
	 * of the waypoint AND THEN waited out a fixed tick pace, so the character arrived, stopped and
	 * stood there. Arriving at a waypoint must never be a prerequisite for the next click.
	 */
	@Test
	public void doesNotWaitForArrivalAtTheWaypointBeforeAiming()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		List<PathStep> p = line(3200, 3200, 40);

		w.drive(at(3200, 3200, 0), p, PATH_ID, true, PACE);
		String second = w.drive(at(3209, 3200, 3000), p, PATH_ID, true, PACE);
		assertTrue(second, second.startsWith("walking -> "));
		// Still three tiles from the tile we aimed at when the next one went out.
		assertEquals(2, sink.clicks.size());
		assertNotEquals(sink.clicks.get(0), sink.clicks.get(1));
	}

	/**
	 * The configured delay is a real minimum gap between clicks, so the lead has to grow with it:
	 * with a 3 s floor the character covers five tiles before the next click is even allowed, and a
	 * three-tile lead would have it arrive, stop and wait -- the stepping this rewrite removes.
	 */
	@Test
	public void theLeadGrowsWithTheConfiguredClickFloor()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		AutoWalk.Settings slow = new AutoWalk.Settings(AutoWalk.DEFAULT_REACH, 3000, false);
		List<PathStep> p = line(3200, 3200, 40);

		assertEquals("walking -> 3212,3200 (step 13/40, 12 tiles, re-click at 5)",
			w.drive(at(3200, 3200, 0), p, PATH_ID, true, slow));
		assertEquals("holding: closing on 3212,3200 (6 left)",
			w.drive(at(3206, 3200, 3100), p, PATH_ID, true, slow));
		assertTrue(w.drive(at(3207, 3200, 3200), p, PATH_ID, true, slow).startsWith("walking -> "));
	}

	@Test
	public void arrivingStopsTheClicks()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		List<PathStep> p = line(3200, 3200, 20);

		w.drive(at(3200, 3200, 0), p, PATH_ID, true, PACE);
		assertEquals(1, sink.clicks.size());

		// ARRIVED_DISTANCE is 1: arrival is measured to the END OF THE PATH, not to the target, so a
		// radius as wide as the plugin's reachedDistance would make any path shorter than it a no-op.
		// That is not hypothetical -- it happened live on 2026-09-07 with a 6-step path.
		assertEquals("arrived (0 from target)", w.drive(at(3219, 3200, 9000), p, PATH_ID, true, PACE));
		assertEquals("arrived (1 from target)", w.drive(at(3218, 3200, 9100), p, PATH_ID, true, PACE));
		assertEquals(1, sink.clicks.size());
	}

	/**
	 * A transport is an EDGE, not a step: a PathStep carries only a position, so a boat, a fairy ring
	 * or a shortcut shows up as two consecutive steps more than a tile apart. The old driver only
	 * looked at the plane and walked straight past every same-plane transport.
	 */
	@Test
	public void stopsAtATransportEdgeAndSaysToTakeIt()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		List<PathStep> p = path(3200, 3200, 3201, 3200, 3202, 3200,
			3250, 3250, 3251, 3250, 3252, 3250, 3253, 3250, 3254, 3250);

		// The last step before the edge is the furthest we will ever aim at.
		assertEquals("walking -> 3202,3200 (step 3/8, 2 tiles, re-click at 3)",
			w.drive(at(3200, 3200, 0), p, PATH_ID, true, PACE));
		assertEquals(List.of("3202,3200"), sink.clicks);

		// Standing on it, the crossing is the player's to make and nothing more is clicked.
		assertEquals("waiting: transport at step 4 (3202,3200 -> 3250,3250) -- take it yourself",
			w.drive(at(3202, 3200, 3000), p, PATH_ID, true, PACE));
		assertEquals(1, sink.clicks.size());
	}

	/** The named form, once the plugin's transport tables have been asked. */
	@Test
	public void namesTheTransportWhenItCan()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		w.setTransportNamer((from, to) -> "fairy ring BIP");
		List<PathStep> p = path(3200, 3200, 3250, 3250, 3251, 3250, 3252, 3250,
			3253, 3250, 3254, 3250, 3255, 3250);

		assertEquals("waiting: transport at step 2 (3200,3200 -> 3250,3250, fairy ring BIP)"
				+ " -- take it yourself",
			w.drive(at(3200, 3200, 0), p, PATH_ID, true, PACE));
		assertTrue(sink.clicks.isEmpty());
	}

	/** Plane changes are the player's to make too, and get their own line. */
	@Test
	public void stopsAtAPlaneChange()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		List<PathStep> p = new ArrayList<>(path(3200, 3200, 3201, 3200));
		for (int i = 0; i < 8; i++)
		{
			p.add(new PathStep(WorldPointUtil.packWorldPoint(3202 + i, 3200, 1), false));
		}

		assertEquals("walking -> 3201,3200 (step 2/10, 1 tiles, re-click at 3)",
			w.drive(at(3200, 3200, 0), p, PATH_ID, true, PACE));
		assertEquals("waiting: next floor at step 3 -- use the stairs yourself",
			w.drive(at(3201, 3200, 3000), p, PATH_ID, true, PACE));
		assertEquals(1, sink.clicks.size());
	}

	/**
	 * A step that does not project is not a dead frame: the scan walks DOWN toward the player inside
	 * the SAME drive() call and clicks the furthest one that does. The old driver returned OFF_SCREEN
	 * and stood still for a whole pace interval.
	 */
	@Test
	public void aRefusedStepFallsBackToANearerOneInTheSameCall()
	{
		Sink sink = new Sink();
		sink.perTile.put("3212,3200", Actions.WalkResult.OFF_SCREEN);
		sink.perTile.put("3211,3200", Actions.WalkResult.OFF_SCREEN);
		AutoWalk w = walker(sink);

		assertEquals("walking -> 3210,3200 (step 11/20, 10 tiles, re-click at 3)",
			w.drive(at(3200, 3200, 0), line(3200, 3200, 20), PATH_ID, true, PACE));
		assertEquals(List.of("3212,3200", "3211,3200", "3210,3200"), sink.clicks);
	}

	/**
	 * A refusal posts nothing, so it must not consume the flood floor: the next frame that CAN click
	 * does, rather than waiting out an interval it never used.
	 */
	@Test
	public void aRefusedScanDoesNotConsumeTheFloodFloor()
	{
		Sink sink = new Sink();
		sink.answer = Actions.WalkResult.OFF_SCREEN;
		AutoWalk w = walker(sink);
		List<PathStep> p = line(3200, 3200, 20);

		assertEquals("no step clickable: nearest candidate 3205,3200 is off screen -- turn the camera",
			w.drive(at(3200, 3200, 0), p, PATH_ID, true, PACE));

		// Well inside the 1800 ms floor, and it clicks the moment the camera allows it.
		sink.answer = Actions.WalkResult.CLICKED;
		sink.clicks.clear();
		assertEquals("walking -> 3212,3200 (step 13/20, 12 tiles, re-click at 3)",
			w.drive(at(3200, 3200, 200), p, PATH_ID, true, PACE));
		assertEquals(List.of("3212,3200"), sink.clicks);
	}

	/** A whole failed scan is not repeated thirty times a second; the line is repeated instead. */
	@Test
	public void aFailedScanIsNotRedoneEveryFrame()
	{
		Sink sink = new Sink();
		sink.answer = Actions.WalkResult.OFF_SCREEN;
		AutoWalk w = walker(sink);
		List<PathStep> p = line(3200, 3200, 20);

		w.drive(at(3200, 3200, 0), p, PATH_ID, true, PACE);
		int tried = sink.clicks.size();
		assertEquals("no step clickable: nearest candidate 3205,3200 is off screen -- turn the camera",
			w.drive(at(3200, 3200, 20), p, PATH_ID, true, PACE));
		assertEquals(tried, sink.clicks.size());
	}

	@Test
	public void offCanvasIsRefusedOutLoud()
	{
		Sink sink = new Sink();
		sink.answer = Actions.WalkResult.OFF_CANVAS;
		AutoWalk w = walker(sink);
		assertEquals("no step clickable: nearest candidate 3205,3200 is off canvas"
				+ " -- refusing to click outside the game window",
			w.drive(at(3200, 3200, 0), line(3200, 3200, 20), PATH_ID, true, PACE));
	}

	/**
	 * The point was right and the message never reached the game (the render view closed, or its
	 * queue refused it). That is not a walk: it must say so, and it must not latch onto the target.
	 */
	@Test
	public void anUndeliveredClickIsReportedAndRetried()
	{
		Sink sink = new Sink();
		sink.answer = Actions.WalkResult.NOT_POSTED;
		AutoWalk w = walker(sink);
		List<PathStep> p = line(3200, 3200, 20);

		assertEquals("click not delivered -- the game window did not take it",
			w.drive(at(3200, 3200, 0), p, PATH_ID, true, PACE));
		// One attempt, not eight: a window that will not take a message will not take a nearer one.
		assertEquals(List.of("3212,3200"), sink.clicks);

		sink.answer = Actions.WalkResult.CLICKED;
		assertEquals("walking -> 3212,3200 (step 13/20, 12 tiles, re-click at 3)",
			w.drive(at(3200, 3200, 200), p, PATH_ID, true, PACE));
	}

	/** Tiles the client has not loaded are never aimed at, and the driver says why it is idle. */
	@Test
	public void refusesToAimOutsideTheLoadedScene()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		// Every step is hundreds of tiles past the 104-tile scene the client has loaded.
		assertEquals("waiting: no step of the path is in the loaded scene",
			w.drive(at(3200, 3200, 0), line(3800, 3800, 20), PATH_ID, true, PACE));
		assertTrue(sink.clicks.isEmpty());
	}

	/**
	 * A new path (the user picked another destination, or the plugin recomputed) is clicked at once
	 * rather than after the floor: the old walk is already going the wrong way.
	 */
	@Test
	public void aNewPathIsClickedImmediately()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		w.drive(at(3200, 3200, 0), line(3200, 3200, 20), PATH_ID, true, PACE);
		assertEquals(1, sink.clicks.size());

		Object newPath = new Object();
		List<PathStep> north = new ArrayList<>();
		for (int i = 0; i < 20; i++)
		{
			north.add(new PathStep(WorldPointUtil.packWorldPoint(3200, 3200 + i, 0), false));
		}
		assertEquals("walking -> 3200,3212 (step 13/20, 12 tiles, re-click at 3)",
			w.drive(at(3200, 3200, 10), north, newPath, true, PACE));
		assertEquals(2, sink.clicks.size());
	}

	/**
	 * The path never shrinks as it is consumed -- Node.getPathSteps builds the list from the SEARCH
	 * START -- so the anchor is what tracks how much of it is behind us. It anchors on the PLAYER over
	 * the WHOLE path: the old nearestIndexToPlayer scanned only the first 100 steps, which is useless
	 * when the player is at index 200 of 300.
	 */
	@Test
	public void anchorsOnThePlayerEvenPastTheHundredthStep()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		List<PathStep> p = line(3000, 3200, 300);

		assertEquals("walking -> 3212,3200 (step 213/300, 12 tiles, re-click at 3)",
			w.drive(at(3200, 3200, 0), p, PATH_ID, true, PACE));
	}

	/** The anchor only ever moves forward, so a tile already walked past is never aimed at again. */
	@Test
	public void theAnchorNeverGoesBackwards()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		List<PathStep> p = line(3200, 3200, 40);

		w.drive(at(3200, 3200, 0), p, PATH_ID, true, PACE);
		w.drive(at(3209, 3200, 3000), p, PATH_ID, true, PACE);
		// Nudged one tile back (a shove, a failed step) and then stalled there: the next aim is
		// still measured from the anchor we reached, never from the step behind it.
		w.drive(at(3208, 3200, 9000), p, PATH_ID, true, PACE);
		String line = w.drive(at(3208, 3200, 11000), p, PATH_ID, true, PACE);
		assertEquals("walking -> 3220,3200 (step 21/40, 12 tiles, re-click at 3)", line);
		for (String click : sink.clicks)
		{
			assertTrue(click, Integer.parseInt(click.split(",")[0]) >= 3212);
		}
	}

	@Test
	public void standsDownWhileAMenuIsOpen()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		assertEquals("standing by: menu open",
			w.drive(user(3200, 3200, 5000, true, 100, false, false), line(3200, 3200, 20),
				PATH_ID, true, PACE));
		assertTrue(sink.clicks.isEmpty());
	}

	/**
	 * The user's report, in a test: "worldmap setting target somehow clicks on the worldmap and flies
	 * to another area of the map."
	 *
	 * <p>This driver has no menu action to issue (DO_ACTION is 0 on this build), so every re-aim is a
	 * REAL left click posted at a projected scene pixel. The game receives it whatever is drawn over
	 * the scene, so with the map open those clicks land on the map and pan it. Nothing about the map
	 * projection maths can fix that; only not clicking can.</p>
	 */
	@Test
	public void standsDownWhileTheWorldMapIsOpen()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		assertEquals("standing by: the world map is open -- a walk click would land on the map",
			w.drive(mapOpen(3200, 3200, 5000), line(3200, 3200, 20), PATH_ID, true, PACE));
		assertTrue(sink.clicks.isEmpty());
	}

	/**
	 * FAIL CLOSED. Every way of not knowing whether the map is up is treated as "it might be": the
	 * group list could not be read, the components would not resolve, they contradicted each other.
	 * The old interlock had one boolean and every one of these collapsed into "not open", which is
	 * how a real left click reached an open map.
	 */
	@Test
	public void anUnknownMapStateStandsDown()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		assertEquals("standing by: cannot tell whether the world map is open -- not clicking",
			w.drive(map(3200, 3200, 5000, AutoWalk.MapState.UNKNOWN), line(3200, 3200, 20),
				PATH_ID, true, PACE));
		assertTrue(sink.clicks.isEmpty());
	}

	/**
	 * The group that has never been opened is the common case and it must cost nothing: the client
	 * cannot draw an interface whose components it has not loaded, so this is the one reading that
	 * says CLOSED on its own -- and it is the client's own list, not a byte at an unverified offset.
	 * Live evidence 2026-09-07: "widgetAbs WORLDMAP 595:7 is not loaded" with the map never opened.
	 */
	@Test
	public void aWorldMapGroupThatIsNotLoadedIsAClosedMapAndWalksAtFullSpeed()
	{
		assertEquals(AutoWalk.MapState.CLOSED, AutoWalk.mapState(true, false, List.of()));

		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		assertTrue(w.drive(map(3200, 3200, 5000, AutoWalk.MapState.CLOSED), line(3200, 3200, 20),
			PATH_ID, true, PACE).startsWith("walking -> "));
		assertEquals(1, sink.clicks.size());
	}

	/** One component shown, with a rectangle, is an open map -- whatever the other three say. */
	@Test
	public void aLoadedGroupWithOneShownComponentIsAnOpenMap()
	{
		assertEquals(AutoWalk.MapState.OPEN, AutoWalk.mapState(true, true, List.of(
			hidden(), hidden(), shown(480, 320), hidden())));

		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		assertEquals("standing by: the world map is open -- a walk click would land on the map",
			w.drive(mapOpen(3200, 3200, 5000), line(3200, 3200, 20), PATH_ID, true, PACE));
		assertTrue(sink.clicks.isEmpty());
	}

	/**
	 * The hidden byte only ever decides CLOSED as a chorus, and only with the group known loaded and
	 * the components actually readable. Every thinner reading is UNKNOWN.
	 */
	@Test
	public void theHiddenByteOnlyClosesTheMapWhenEnoughComponentsAgree()
	{
		// The whole interface reading hidden together: the signature of a shut map.
		assertEquals(AutoWalk.MapState.CLOSED, AutoWalk.mapState(true, true, List.of(
			hidden(), hidden(), hidden(), hidden())));
		// One lone readable component is an assertion, not a reading.
		assertEquals(AutoWalk.MapState.UNKNOWN, AutoWalk.mapState(true, true, List.of(
			hidden(), absent(), absent(), absent())));
		// The group list says loaded and nothing resolves: the two signals disagree.
		assertEquals(AutoWalk.MapState.UNKNOWN, AutoWalk.mapState(true, true, List.of(
			absent(), absent(), absent(), absent())));
		// Shown, with no rectangle: one component contradicting itself poisons the whole verdict.
		assertEquals(AutoWalk.MapState.UNKNOWN, AutoWalk.mapState(true, true, List.of(
			hidden(), shown(0, 0), hidden(), hidden())));
		// The loaded-group list could not be read at all.
		assertEquals(AutoWalk.MapState.UNKNOWN, AutoWalk.mapState(false, false, List.of(
			hidden(), hidden(), hidden(), hidden())));
	}

	/**
	 * An unresolved parent chain never turns a shown map into a closed one, and never blocks a closed
	 * one either. The world map is parented across interface groups on this build, so the chain is
	 * expected to stay unresolved forever; making it a requirement would stand the walker down for
	 * good, and making it a licence would be reading a parent-relative rectangle as a canvas one.
	 * Only the rectangle's AREA is read, which is the part the chain flag does not affect.
	 */
	@Test
	public void anUnresolvedWidgetChainChangesNoVerdict()
	{
		assertEquals(AutoWalk.MapState.OPEN, AutoWalk.mapState(true, true, List.of(
			new AutoWalk.MapProbe(true, false, 480, 320, false), hidden(), hidden(), hidden())));
		assertEquals(AutoWalk.MapState.CLOSED, AutoWalk.mapState(true, true, List.of(
			new AutoWalk.MapProbe(true, true, 480, 320, false),
			new AutoWalk.MapProbe(true, true, 480, 320, false), hidden(), hidden())));
	}

	/**
	 * Nothing is latched, but nothing is hasty either: the run picks itself back up once the map has
	 * READ closed for the settling window. A hidden byte nobody has verified can flicker, and one
	 * frame of walking is one posted click, which is one map pan.
	 */
	@Test
	public void resumesOnceTheWorldMapHasReadClosedForTheSettlingWindow()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		List<PathStep> p = line(3200, 3200, 20);

		w.drive(mapOpen(3200, 3200, 5000), p, PATH_ID, true, PACE);
		assertTrue(sink.clicks.isEmpty());

		// One frame of "closed" straight after an open map is not enough to click on.
		assertTrue(w.drive(at(3200, 3200, 5030), p, PATH_ID, true, PACE)
			.startsWith("standing by: the world map has only just read as closed"));
		assertTrue(sink.clicks.isEmpty());

		// It held. Walk.
		assertTrue(w.drive(at(3200, 3200, 5000 + AutoWalk.MAP_SETTLE_MS), p, PATH_ID, true, PACE)
			.startsWith("walking -> "));
		assertEquals(1, sink.clicks.size());
	}

	/** A single flickering frame of "closed" in the middle of an open map buys no click at all. */
	@Test
	public void aOneFrameFlickerOfClosedNeverUnlocksTheWalker()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		List<PathStep> p = line(3200, 3200, 20);

		for (long t = 5000; t < 6000; t += 30)
		{
			// Every tenth frame the byte reads "hidden" for no reason. The map is up the whole time.
			AutoWalk.MapState flicker = (t / 30) % 10 == 0
				? AutoWalk.MapState.CLOSED : AutoWalk.MapState.OPEN;
			w.drive(map(3200, 3200, t, flicker), p, PATH_ID, true, PACE);
		}
		assertTrue(sink.clicks.isEmpty());
	}


	/** An open map outranks every later stand-down, so the status line names the thing to close. */
	@Test
	public void theOpenMapIsReportedAheadOfTheUsersOwnMouse()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		AutoWalk.World w1 = new AutoWalk.World(true, 3200, 3200, 0, BASE_X, BASE_Y, 5000, 0, 0, false,
			false, 100, 100, true, false, true, false, AutoWalk.MapState.OPEN);
		assertEquals("standing by: the world map is open -- a walk click would land on the map",
			w.drive(w1, line(3200, 3200, 20), PATH_ID, true, PACE));
		assertTrue(sink.clicks.isEmpty());
	}

	/**
	 * The camera drag. The user holds the middle button for a second or two and swings the camera;
	 * a posted mouse-move in the middle of that drag swings it somewhere they did not ask for. Ours
	 * never sets this button, so there is nothing to suppress and the whole hold is theirs.
	 */
	@Test
	public void standsDownWhileTheMiddleButtonIsHeld()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		List<PathStep> p = line(3200, 3200, 20);
		AutoWalk.World dragging = new AutoWalk.World(true, 3200, 3200, 0, BASE_X, BASE_Y, 5000, 0, 0,
			false, false, -1, -1, false, false, false, true, AutoWalk.MapState.CLOSED);

		assertEquals("standing by: middle button held", w.drive(dragging, p, PATH_ID, true, PACE));
		assertTrue(sink.clicks.isEmpty());
	}

	@Test
	public void standsDownWhileTheRightButtonIsHeld()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		assertEquals("standing by: right button held",
			w.drive(user(3200, 3200, 5000, false, 100, false, true), line(3200, 3200, 20),
				PATH_ID, true, PACE));
		assertTrue(sink.clicks.isEmpty());
	}

	@Test
	public void standsDownWhileTheUserMovesTheirOwnCursor()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		List<PathStep> p = line(3200, 3200, 20);

		// First sight of the cursor is a baseline, not a movement: this frame still walks.
		assertTrue(w.drive(user(3200, 3200, 5000, false, 100, false, false), p, PATH_ID, true, PACE)
			.startsWith("walking -> "));
		// Now it moves, and we let go of the mouse.
		assertEquals("standing by: your mouse",
			w.drive(user(3200, 3200, 5100, false, 140, false, false), p, PATH_ID, true, PACE));
		// And stay stood down through the cool-off rather than fighting them for it.
		assertEquals("standing by: your mouse",
			w.drive(user(3200, 3200, 6000, false, 140, false, false), p, PATH_ID, true, PACE));
		assertEquals(1, sink.clicks.size());
	}

	/**
	 * THE TRAP. client/jvm.hpp's WH_MOUSE hook latches posted WM_LBUTTONDOWN too -- its own comment
	 * says "or any synthetic one" -- so our own walk click reads back as the left button being down.
	 * A guard that believed it would stand down for ever after its first click.
	 */
	@Test
	public void ourOwnClickIsNotMistakenForTheUsersHand()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		List<PathStep> p = line(3200, 3200, 20);

		assertTrue(w.drive(user(3200, 3200, 5000, false, 100, false, false), p, PATH_ID, true, PACE)
			.startsWith("walking -> "));

		// 100 ms later the latch is still showing OUR click. Not the user.
		assertEquals("holding: closing on 3212,3200 (12 left)",
			w.drive(user(3200, 3200, 5100, false, 100, true, false), p, PATH_ID, true, PACE));

		// Past the self-suppression window it can only be them: ours is cleared by the snapshot that
		// reports it (client/jvm.hpp exchanges the latch), so a down that outlives it is a finger.
		assertEquals("standing by: left button held",
			w.drive(user(3200, 3200, 5300, false, 100, true, false), p, PATH_ID, true, PACE));
	}

	/**
	 * THE LIVE FAILURE, as a test: the user is HOLDING the left button -- on the world map's close
	 * button, in the report -- and the walker must not post the mouse-move that would drag their
	 * cursor off it before they let go. The old guard only asked "is a button down and was our own
	 * click more than 150 ms ago", so every click of ours re-opened a 150 ms hole in the middle of
	 * their hold, and a hold that began before we ever clicked was invisible until we clicked again.
	 */
	@Test
	public void aHeldLeftButtonStandsTheWalkerDownForTheWholeHold()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		List<PathStep> p = line(3200, 3200, 20);

		// They press, and keep the button down for a second and a half -- much longer than a click.
		for (long t = 0; t <= 1500; t += 30)
		{
			assertEquals("standing by: left button held",
				w.drive(user(3200, 3200, t, false, 100, true, false), p, PATH_ID, true, PACE));
		}
		assertTrue(sink.clicks.isEmpty());

		// They let go. The cool-off keeps us off the mouse for a moment, then the run resumes.
		assertEquals("standing by: your mouse",
			w.drive(user(3200, 3200, 1530, false, 100, false, false), p, PATH_ID, true, PACE));
		assertTrue(w.drive(user(3200, 3200, 4000, false, 100, false, false), p, PATH_ID, true, PACE)
			.startsWith("walking -> "));
	}

	/**
	 * The fix for "it clicked forever and fought the mouse": an unreachable last step used to be
	 * re-clicked for ever, because the player never gets within the arrival distance of it.
	 */
	@Test
	public void givesUpAfterRepeatedAimsWithNoProgress()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		List<PathStep> p = line(3200, 3200, 20);

		// The player never moves: every re-aim is a stall, and none of them gets any closer.
		w.drive(at(3200, 3200, 0), p, PATH_ID, true, PACE);
		for (long t = 2000; t <= 8000; t += 2000)
		{
			w.drive(at(3200, 3200, t), p, PATH_ID, true, PACE);
		}
		// Four aims: the first made "progress" (it was the first measurement), then three did not,
		// and the third of those was more than five seconds after the last progress.
		int clicked = sink.clicks.size();
		assertEquals(4, clicked);

		assertEquals("gave up: no progress for 6.0s", w.drive(at(3200, 3200, 8100), p, PATH_ID, true, PACE));
		assertEquals(clicked, sink.clicks.size());

		// The latch does not re-arm by itself -- only a new path, an arrival, or the toggle.
		assertEquals("gave up: no progress for 6.0s", w.drive(at(3200, 3200, 30000), p, PATH_ID, true, PACE));
		assertEquals(clicked, sink.clicks.size());

		assertTrue(w.drive(at(3200, 3200, 31000), p, new Object(), true, PACE).startsWith("walking -> "));
	}

	/** A stall with a destination outstanding re-clicks rather than standing there. */
	@Test
	public void aStalledWalkIsReClicked()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		List<PathStep> p = line(3200, 3200, 20);

		w.drive(at(3200, 3200, 0), p, PATH_ID, true, PACE);
		// Not closing -- the distance has not moved -- but not yet stalled either.
		assertEquals("holding: 3212,3200 (12 left)",
			w.drive(at(3200, 3200, 1000), p, PATH_ID, true, PACE));
		// Past STATIONARY_MS with nothing having moved: aim again at the tile the client ignored.
		assertTrue(w.drive(at(3200, 3200, 2000), p, PATH_ID, true, PACE).startsWith("walking -> "));
		assertEquals(2, sink.clicks.size());
	}

	/**
	 * The split click is off by default and, when on, owes the game a button-up on a later frame.
	 * A release that never goes out is a button the game believes the user is holding down.
	 */
	@Test
	public void theSplitClickReleasesOnALaterFrame()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		AutoWalk.Settings split = new AutoWalk.Settings(AutoWalk.DEFAULT_REACH, 1800, true);
		List<PathStep> p = line(3200, 3200, 20);

		w.drive(at(3200, 3200, 0), p, PATH_ID, true, split);
		assertEquals(List.of("3212,3200"), sink.presses);
		assertTrue(sink.clicks.isEmpty());
		assertEquals(0, sink.releases);

		w.drive(at(3200, 3200, 30), p, PATH_ID, true, split);
		assertEquals(0, sink.releases);
		w.drive(at(3200, 3200, 60), p, PATH_ID, true, split);
		assertEquals(1, sink.releases);
	}

	/** Being switched off mid-click must not leave the button held. */
	@Test
	public void resetReleasesAHeldButton()
	{
		Sink sink = new Sink();
		AutoWalk w = walker(sink);
		AutoWalk.Settings split = new AutoWalk.Settings(AutoWalk.DEFAULT_REACH, 1800, true);

		w.drive(at(3200, 3200, 0), line(3200, 3200, 20), PATH_ID, true, split);
		w.reset();
		assertEquals(1, sink.releases);
	}
}

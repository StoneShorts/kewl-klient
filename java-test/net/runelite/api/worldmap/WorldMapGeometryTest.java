// The world map's refusals -- now FOUR questions instead of one -- and the drag measurement that is
// the only way the strictest of them can ever be satisfied. No client, no natives.
//
// Background (2026-09-07). Drawing on the world map depends on exactly three shim values -- the
// container's bounds, the centre tile, the scale -- and on client-240-6 all three could be wrong at
// once. The surfaces that use them disagreed about when they were usable: PathMapOverlay refused
// unconditionally, PathMapTooltipOverlay did not refuse at all, the marker pass gated on "the widget
// is non-null and the centre is live", and the plugin's menu asked "does the mouse fall inside the
// rectangle" while its resolver asked "is the map open at all". A user right-clicking with the map
// open could therefore be offered "Set target" by one rule and have the target computed by another.
//
// Collapsing all of that into ONE predicate fixed the disagreement and introduced a worse bug: the
// plugin's target resolver asked the FULL map question, and a map refusal made it discard a world
// point the SCENE had already resolved -- so a right-click with the map merely loaded silently
// cleared the plugin. The predicate is now SEPARABLE: containment needs the rectangle, inversion and
// drawing need the rectangle plus the centre plus the scale. These tests pin the split, they pin the
// refusal TEXT (a refusal that does not name its one unblocking action strands the user), and they
// pin the receipts on an accepted measurement.
//
// Two live readings from the running client on 2026-09-07 are quoted in the assertions and are the
// reason several of these messages changed: "[shim] minimap widget resolved at (1156,8) 152x152" on a
// 1356-wide canvas (so the parent-link walk WORKS for a loaded group), and "[shim] widgetAbs WORLDMAP
// 595:7 is not loaded" (so the world-map group is simply absent until the map is first opened).
package net.runelite.api.worldmap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WorldMapGeometryTest
{
	/** Everything right: an open, absolutely-placed container with a live centre and a real scale. */
	private static String refusalWith(boolean present, boolean hidden, boolean absolute, int w, int h,
		boolean live, boolean calibrated)
	{
		return WorldMap.geometryRefusal(present, hidden, absolute, w, h, live, calibrated);
	}

	@Test
	public void everythingRightIsTheOnlyCaseThatDraws()
	{
		assertNull(refusalWith(true, false, true, 640, 480, true, true));
	}

	@Test
	public void aClosedMapRefusesEvenThoughItsWidgetExists()
	{
		// The one that let overlays paint over the scene: on this build the world-map GROUP stays
		// loaded while the map is closed, so the upstream `getWidget(...) == null` test is not a test
		// for "the map is open" at all. The message has to say that, or the next reader re-derives it.
		String why = refusalWith(true, true, true, 640, 480, true, true);
		assertNotNull(why);
		assertTrue(why, why.contains("CLOSED"));
		assertTrue("says why non-null is not enough", why.contains("group stays loaded"));
	}

	@Test
	public void aParentRelativeRectangleRefusesAndSaysWhatItBreaks()
	{
		// The single blocker shared by the drawing and the click inversion: rect.x is ADDED to a
		// canvas pixel in the forward projection and SUBTRACTED in calculateMapPoint, so a relative
		// rectangle is a constant offset injected into both -- the "set a target and it lands
		// somewhere else" half of the user's report.
		String why = refusalWith(true, false, false, 640, 480, true, true);
		assertNotNull(why);
		assertTrue(why, why.contains("PARENT-RELATIVE"));
		assertTrue("names the cross-group cause", why.contains("component table"));
	}

	@Test
	public void anUncalibratedScaleRefusesToo()
	{
		// This is a deliberate CHANGE of behaviour, not an oversight being pinned. The old reading was
		// that a wrong scale still draws the right direction near the centre, so it was worth drawing.
		// That reasoning covers only the drawing: the same scale is the divisor that turns a map click
		// back into a tile, so an uncalibrated map also resolves clicks to the wrong tile -- and with
		// auto-walk on, the client walks there. Half-right drawing is not worth a wrong target.
		String why = refusalWith(true, false, true, 640, 480, true, false);
		assertNotNull(why);
		assertTrue(why, why.contains("PLACEHOLDER"));
		assertTrue("says how to fix it without a rebuild", why.contains(WorldMap.ZOOM_PROPERTY));
		// And -- the point of this whole refusal -- the ONE action a human can take to unblock it,
		// spelled out rather than gestured at. This is the only condition in the predicate that no
		// amount of waiting fixes, so a message that does not name the action strands the user.
		assertTrue("names the one unblocking action", why.contains("OPEN THE WORLD MAP AND DRAG IT"));
	}

	@Test
	public void theUnblockingActionNamesADistanceAndSaysWhyOpeningTheMapIsPartOfIt()
	{
		// "Drag a good distance" is what this used to say, and the threshold is on SCROLL UNITS, a
		// quantity the user cannot see. So the instruction has to convert it into pixels -- 8 units *
		// 8 tiles * 4 px/tile = 256 px at the placeholder -- and has to say the drag must be one
		// unbroken press, because the pixel travel and the tile travel are read against one button.
		String action = WorldMap.humanBootstrapAction(WorldMap.PLACEHOLDER_PIXELS_PER_TILE);
		assertEquals(256, WorldMap.minDragPixels(WorldMap.PLACEHOLDER_PIXELS_PER_TILE));
		assertTrue(action, action.contains("256 px"));
		assertTrue("insists on one motion", action.contains("unbroken"));
		// And why opening the map is not incidental: it is what loads the group, and a loaded group is
		// what makes the container's rectangle absolute. That chain is the deadlock's exit, and it is
		// cited to the live reading rather than asserted.
		assertTrue(action, action.contains("loads its interface group"));
		assertTrue("cites the live evidence that the chain resolves", action.contains("(1156,8)"));
		assertTrue("and the fallback that needs no drag", action.contains(WorldMap.ZOOM_PROPERTY));
	}

	@Test
	public void anUnusableBeliefStillYieldsAnInstructionRatherThanNonsense()
	{
		// minDragPixels is called with whatever scale is currently in force, which before any
		// calibration is the placeholder but after a clearCalibration could momentarily be anything a
		// caller passed. A NaN or zero belief must not produce "drag about 0 px" or "drag about NaN".
		assertEquals(256, WorldMap.minDragPixels(Float.NaN));
		assertEquals(256, WorldMap.minDragPixels(0f));
		assertEquals(512, WorldMap.minDragPixels(8f));
	}

	@Test
	public void aStaleCentreRefuses()
	{
		String why = refusalWith(true, false, true, 640, 480, false, true);
		assertNotNull(why);
		assertTrue(why, why.contains("not live"));
	}

	@Test
	public void anAbsentInterfaceAndAnEmptyRectangleAreDistinctReasons()
	{
		// They need different fixes -- one is "open the map", the other is "the interface resolved but
		// is not built" -- so they must not collapse into one message.
		String absent = refusalWith(false, false, false, 0, 0, false, false);
		String empty = refusalWith(true, false, true, 0, 0, true, true);
		assertNotNull(absent);
		assertNotNull(empty);
		assertFalse(absent.equals(empty));
		assertTrue(absent, absent.contains("not loaded"));
		assertTrue(empty, empty.contains("no rectangle"));
	}

	@Test
	public void theReasonsAreOrderedFromTheOutsideIn()
	{
		// With several things wrong at once the message must name the OUTERMOST one, because that is
		// the one the user can act on: "the map is closed" is actionable, "the scale is a placeholder"
		// is not, if the map is also closed.
		String why = refusalWith(true, true, false, 0, 0, false, false);
		assertTrue(why, why.contains("CLOSED"));
	}

	// -- the drag measurement ----------------------------------------------------------------------

	@Test
	public void aLongDragMeasuresTheScale()
	{
		// 320 px of cursor travel while the centre moved 10 scroll units = 80 world tiles: 4 px/tile.
		assertEquals(4.0f, WorldMap.pixelsPerTileFromDrag(320, 10), 1e-6f);
		// Sign is pan direction, not scale: the map pans the opposite way to the numbers as often as
		// not, and taking the magnitude is the whole reason this does not need to know which.
		assertEquals(4.0f, WorldMap.pixelsPerTileFromDrag(320, -10), 1e-6f);
	}

	@Test
	public void aShortDragMeasuresTheQuantisationRatherThanTheScale()
	{
		// THE reason this has a minimum at all. The centre is quantised to 8 world tiles, so the last
		// unit of any drag is worth +-1 unit: over 2 units that is a 50% error, and the "measurement"
		// is a reading of the block size. A number like that would be silently wrong everywhere on the
		// map -- worse than the placeholder, which at least announces itself.
		assertTrue(Float.isNaN(WorldMap.pixelsPerTileFromDrag(64, 2)));
		assertTrue(Float.isNaN(WorldMap.pixelsPerTileFromDrag(300, WorldMap.MIN_DRAG_SCROLL_UNITS - 1)));
		assertFalse(Float.isNaN(WorldMap.pixelsPerTileFromDrag(300, WorldMap.MIN_DRAG_SCROLL_UNITS)));
	}

	@Test
	public void aClickThatDidNotMoveAnythingMeasuresNothing()
	{
		// A press and release in one place: zero pixels, zero units. Without this it is 0/0.
		assertTrue(Float.isNaN(WorldMap.pixelsPerTileFromDrag(0, 0)));
		assertTrue(Float.isNaN(WorldMap.pixelsPerTileFromDrag(0, 12)));
		assertTrue(Float.isNaN(WorldMap.pixelsPerTileFromDrag(-40, 12)));
		assertTrue(Float.isNaN(WorldMap.pixelsPerTileFromDrag(Double.NaN, 12)));
		assertTrue(Float.isNaN(WorldMap.pixelsPerTileFromDrag(Double.POSITIVE_INFINITY, 12)));
	}

	@Test
	public void anImplausibleDragIsRefusedByTheSanityBoundsRatherThanStored()
	{
		// pixelsPerTileFromDrag is arithmetic; isUsableScale is the judgement. A drag that computes
		// 0.1 px/tile (a huge pan for almost no cursor travel -- inertia, or a drag that started on a
		// panel) must not become the scale every map surface then trusts.
		assertFalse(WorldMap.isUsableScale(WorldMap.pixelsPerTileFromDrag(80, 100)));
		assertTrue(WorldMap.isUsableScale(WorldMap.pixelsPerTileFromDrag(320, 10)));
	}

	@Test
	public void oneScrollUnitIsEightWorldTilesAndTheArithmeticSaysSo()
	{
		// Not a style point: the 8 is the client's own `origin = 8*centre - 48`, and if that constant
		// were ever wrong every measured scale would be wrong by the same factor.
		assertEquals(8, WorldMap.TILES_PER_SCROLL_UNIT);
		assertEquals(1.0f,
			WorldMap.pixelsPerTileFromDrag(WorldMap.TILES_PER_SCROLL_UNIT * 10, 10), 1e-6f);
	}

	// -- the two things that stay honest even when everything above passes -------------------------

	@Test
	public void anImplausibleScaleIsCalledOutAgainstTheLoadWindow()
	{
		// A bound, not a derivation: the client holds ~96 tiles per axis around the centre, so a scale
		// claiming the widget shows 160 is claiming it draws terrain that is not resident.
		String note = WorldMap.scaleNote(640, 480, 4.0f, false);
		assertTrue(note, note.contains("IMPLAUSIBLE"));
		assertTrue("gives the lower bound it implies", note.contains("6.67 px/tile"));
		assertTrue("and says it is a bound", note.contains("not a"));
	}

	@Test
	public void aScaleThatFitsInsideTheLoadWindowIsNotAccused()
	{
		// 640 px at 8 px/tile is 80 tiles across, comfortably inside the window: nothing to say.
		assertFalse(WorldMap.scaleNote(640, 480, 8.0f, true).contains("IMPLAUSIBLE"));
	}

	@Test
	public void theCentreQuantisationIsStatedAsAnOpenQuestion()
	{
		// The residual error that survives a perfect rectangle AND a perfect scale, and the one most
		// likely to be assumed away: what Events pushes is identically 8 * WM_CENTRE. Which of the two
		// answers is true is settled by one glance at the open map, and the note must ASK rather than
		// claim, because nobody has taken that glance.
		String note = WorldMap.centreQuantisationNote(3200, 3400);
		assertTrue(note, note.contains("3200,3400"));
		assertTrue(note, note.contains("multiple of 8"));
		assertTrue("names the error it costs if the map pans smoothly", note.contains("+-4 tiles"));
		assertTrue("and the observation that decides it", note.contains("SMOOTHLY"));
	}

	// -- three questions, not one ------------------------------------------------------------------
	//
	// The conflation these pin apart did real damage. ShortestPathPlugin's target resolver asked the
	// FULL map question and, when the map refused, discarded a world point the SCENE had already
	// produced -- so a right-click with the map merely loaded silently cleared the plugin. Asking
	// whether a canvas point is inside a rectangle needs the rectangle. It does not need a live centre
	// and it certainly does not need a measured scale.

	private static net.runelite.api.widgets.Widget container(boolean hidden, boolean absolute,
		int w, int h)
	{
		net.runelite.api.widgets.Widget widget = new net.runelite.api.widgets.Widget(0x2530007);
		widget.setBounds(100, 50, w, h);
		widget.setHidden(hidden);
		widget.setCanvasAbsolute(absolute);
		return widget;
	}

	/**
	 * The presence question, added 2026-09-07 for the other half of the same report ("when i open the
	 * worldmap and choose a location in it ... it chooses on the gamescreen"). Its whole value is what
	 * it does NOT read: no width, no height, no {@code isCanvasAbsolute}. ShortestPathPlugin gates its
	 * scene fallback on this answer, and that gate must not be reachable by a geometry failure --
	 * a rectangle that will not resolve is exactly the state in which the map IS on screen.
	 */
	@Test
	public void presenceAnswersFromLoadedAndHiddenAloneAndIgnoresTheGeometry()
	{
		assertNull("open, and the rectangle is unusable -- still ON SCREEN",
			WorldMap.presenceRefusalFor(container(false, false, 0, 0)));
		assertNull("open, with a good rectangle", WorldMap.presenceRefusalFor(container(false, true, 640, 480)));
		assertTrue(WorldMap.isOnScreen(container(false, false, 0, 0)));

		String never = WorldMap.presenceRefusalFor(null);
		assertNotNull(never);
		assertTrue(never, never.contains("not loaded"));
		assertFalse(WorldMap.isOnScreen(null));

		String closed = WorldMap.presenceRefusalFor(container(true, true, 640, 480));
		assertNotNull(closed);
		assertTrue(closed, closed.contains("CLOSED"));
		assertFalse("a loaded-but-hidden group is not an open map",
			WorldMap.isOnScreen(container(true, true, 640, 480)));
	}

	/**
	 * One definition of "the map is on screen", not two. The rectangle question starts by asking the
	 * presence question, so a caller gating on presence and a caller gating on the rectangle can never
	 * disagree about whether the map is up -- they disagree only about the rectangle, which is the
	 * distinction the whole split exists to preserve.
	 */
	@Test
	public void theRectangleQuestionReportsTheVeryPresenceRefusalWhenTheMapIsNotOnScreen()
	{
		for (net.runelite.api.widgets.Widget off : new net.runelite.api.widgets.Widget[]{
			null, container(true, true, 640, 480), container(true, false, 0, 0)})
		{
			assertEquals("presence and rectangle must give the same reason while the map is off screen",
				WorldMap.presenceRefusalFor(off), WorldMap.containmentRefusalFor(off));
		}
		// And the converse, which is the asymmetry that matters: on screen, with the rectangle refused.
		net.runelite.api.widgets.Widget relative = container(false, false, 640, 480);
		assertNull(WorldMap.presenceRefusalFor(relative));
		assertNotNull(WorldMap.containmentRefusalFor(relative));
	}

	@Test
	public void containmentIgnoresTheScaleAndTheCentreWhileInversionDoesNot()
	{
		net.runelite.api.widgets.Widget map = container(false, true, 640, 480);
		WorldMap uncalibrated = new WorldMap();
		uncalibrated.setPosition(new net.runelite.api.Point(3200, 3400)); // live centre, no scale

		// The whole point: an open, absolutely-placed map with a live centre and no MEASURED scale can
		// still answer "is this point on the map", and MUST -- that answer costs nothing, and refusing
		// it is what let a map refusal delete a scene answer.
		assertNull(WorldMap.containmentRefusalFor(map));
		String invert = WorldMap.inversionRefusalFor(map, uncalibrated);
		assertNotNull(invert);
		assertTrue(invert, invert.contains("PLACEHOLDER"));
		assertEquals("drawing needs exactly what inverting needs, and must say the same thing",
			invert, WorldMap.drawRefusalFor(map, uncalibrated));

		// And once the scale IS measured, all three open together.
		assertTrue(uncalibrated.calibrateFromDrag(320, 10, "a long drag"));
		assertNull(WorldMap.inversionRefusalFor(map, uncalibrated));
		assertNull(WorldMap.drawRefusalFor(map, uncalibrated));
	}

	@Test
	public void everyQuestionStillRefusesWhenTheRectangleItselfIsUnusable()
	{
		// Separability must not become permissiveness. The rectangle is the FLOOR: when it is missing,
		// relative, or zero-sized, containment is exactly as wrong as inversion, and all three refuse
		// with the same reason.
		for (net.runelite.api.widgets.Widget bad : new net.runelite.api.widgets.Widget[]{
			null, container(true, true, 640, 480), container(false, false, 640, 480),
			container(false, true, 0, 0)})
		{
			String contain = WorldMap.containmentRefusalFor(bad);
			assertNotNull(String.valueOf(bad), contain);
			assertEquals("the rectangle refusal is the same reason for every question",
				contain, WorldMap.inversionRefusalFor(bad, new WorldMap()));
		}
	}

	@Test
	public void aNotLoadedGroupIsCalledTheOrdinaryStateRatherThanAFault()
	{
		// Live 2026-09-07: "[shim] widgetAbs WORLDMAP 595:7 is not loaded" before the map is opened.
		// The world-map group's data is not resident until the first open, so a null container is the
		// normal pre-open state -- the message must not read like a broken parent chain, because that
		// is what sent the last investigation after the wrong thing.
		String why = WorldMap.containmentRefusalFor(null);
		assertNotNull(why);
		assertTrue(why, why.contains("not loaded"));
		assertTrue("says it is expected before the first open", why.contains("ordinary state"));
	}

	@Test
	public void aRelativeRectangleIsNowReportedAsUnexpected()
	{
		// The correction of record. The minimap resolved canvas-absolute live at (1156,8) 152x152 on a
		// 1356-wide canvas, so the parent-link walk demonstrably works for a loaded group. A relative
		// rectangle is therefore no longer "the derivation never ran"; it is a fact about this group.
		String why = WorldMap.containmentRefusalFor(container(false, false, 640, 480));
		assertNotNull(why);
		assertTrue(why, why.contains("PARENT-RELATIVE"));
		assertTrue("cites the live line rather than assuming", why.contains("(1156,8)"));
		assertTrue("and still names the remaining cause", why.contains("component table"));
	}

	// -- the measurement, with its receipts --------------------------------------------------------

	@Test
	public void anAcceptedMeasurementCarriesBothReferencePoints()
	{
		// A wrong scale is invisible: a path drawn at half scale starts in the right place, points the
		// right way, and ends somewhere else. So the log line has to carry enough to redo the division
		// by hand -- both cursor pixels, both WM_CENTRE readings, the tiles they imply, the quotient.
		String note = WorldMap.dragMeasurementNote(412, 300, 712, 300, 398, 429, 408, 429,
			320, 10, WorldMap.pixelsPerTileFromDrag(320, 10));
		assertTrue(note, note.contains("(412,300) -> (712,300)"));
		assertTrue(note, note.contains("(398,429) -> (408,429)"));
		assertTrue("names the tile count the scroll units stand for", note.contains("80 world tiles"));
		assertTrue("and shows the division", note.contains("320 / (10 * 8) = 4.00"));
		assertTrue("and does not let it pass for a reading off the client",
			note.contains("DRAG BEHAVIOUR"));
	}

	@Test
	public void aRefusedDragLeavesTheStoredScaleAndItsProvenanceAlone()
	{
		// A stray click must not be able to wipe out a good calibration, and it must not be able to
		// attach its own provenance to one either -- a stored number whose receipts describe a
		// different measurement is worse than one with none.
		WorldMap map = new WorldMap();
		assertTrue(map.calibrateFromDrag(320, 10, "the good one"));
		assertEquals("the good one", map.calibrationProvenance());
		assertFalse(map.calibrateFromDrag(4, 1, "a stray click"));
		assertEquals("the good one", map.calibrationProvenance());
		assertEquals(4.0f, map.pixelsPerTileQuiet(), 1e-6f);
	}

	@Test
	public void droppingACalibrationDropsItsProvenanceToo()
	{
		// clearCalibration fires when the zoom buttons are used: the number described a zoom level
		// that is no longer on screen. Leaving the old receipts attached to the placeholder would make
		// the next log line claim a measurement that is not in force.
		WorldMap map = new WorldMap();
		assertTrue(map.calibrateFromDrag(320, 10, "measured off a drag"));
		map.clearCalibration();
		assertFalse(map.isZoomCalibrated());
		assertNull(map.calibrationProvenance());
		assertEquals(WorldMap.PLACEHOLDER_PIXELS_PER_TILE, map.pixelsPerTileQuiet(), 1e-6f);
	}
}

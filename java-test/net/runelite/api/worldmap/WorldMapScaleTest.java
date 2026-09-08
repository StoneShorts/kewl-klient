// The pure half of the world-map scale question: what the log says the overlays are drawing at, and
// what counts as a usable scale at all. No client, no natives, and nothing here touches the WorldMap
// singleton other classes read.
//
// Background (2026-09-06): PathMapOverlay places every path tile at tilesFromCentre * pixelsPerTile
// from the middle of MAP_CONTAINER. The centre is derived and live (WM_ORIGIN + 48); the scale is a
// placeholder, because the client has no zoom field AND -- re-checked once widgets started working --
// the map widget's rectangle cannot recover one either: the only other coordinate the map object
// exposes is the origin, which is a FIXED +-48 tile load window and therefore carries no scale. So
// the drawing is anchored right and scaled wrong, and the contract these tests hold is that this is
// stated rather than hidden.
//
// Updated 2026-09-07. Reading that rectangle is no longer hypothetical: the running client resolved
// the minimap canvas-absolute at (1156,8) 152x152 on a 1356-wide canvas, so the parent-link walk
// works for a loaded group and the world-map container's true rectangle is readable while the map is
// open. It STILL does not yield a scale -- one rectangle is one reference point, and the map origin
// is a fixed +-48 tile load window at every zoom -- so the placeholder stays. What did change is that
// the MEASUREMENT is now reachable, which makes the note's job bigger: it has to name the one action
// that takes it, not merely confess to being a placeholder.
package net.runelite.api.worldmap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WorldMapScaleTest
{
	@Test
	public void anUncalibratedScaleSaysSoAndSaysWhatIsWrongWithIt()
	{
		String note = WorldMap.scaleNote(640, 480, WorldMap.PLACEHOLDER_PIXELS_PER_TILE, false);
		assertTrue(note, note.contains("PLACEHOLDER"));
		// The specific failure mode, because "anchored right, scaled wrong" is the one a user cannot
		// diagnose by eye: a path drawn at half scale still starts in the right place and points the
		// right way, and only its far end is somewhere else entirely.
		assertTrue(note, note.contains("right CENTRE") && note.contains("wrong SCALE"));
		// And the way out, since it needs a measurement rather than a new offset.
		assertTrue(note, note.contains(WorldMap.ZOOM_PROPERTY));
	}

	@Test
	public void theUncalibratedNoteCarriesTheSameUnblockingActionTheRefusalsDo()
	{
		// One sentence, one place. The refusal a caller sees and the note the log prints must not drift
		// into two different accounts of what a person has to do, because the log line is the only one
		// most users will ever read.
		String note = WorldMap.scaleNote(640, 480, WorldMap.PLACEHOLDER_PIXELS_PER_TILE, false);
		assertTrue(note,
			note.contains(WorldMap.humanBootstrapAction(WorldMap.PLACEHOLDER_PIXELS_PER_TILE)));
	}

	@Test
	public void aCalibratedNoteDoesNotNagAboutADragThatAlreadyHappened()
	{
		// The mirror of the above: once the scale is measured the instruction is noise, and a log that
		// keeps telling you to do the thing you just did is a log nobody reads.
		String note = WorldMap.scaleNote(640, 480, 8.0f, true);
		assertFalse(note, note.contains("OPEN THE WORLD MAP AND DRAG IT"));
	}

	@Test
	public void anUncalibratedNoteStillReportsTheSpanItIsDrawingAt()
	{
		// Concrete enough to judge against the open map: 640 px at 4 px/tile is a claim that the map
		// shows 160 tiles across, which is either obviously right or obviously wrong at a glance.
		String note = WorldMap.scaleNote(640, 480, 4.0f, false);
		assertTrue(note, note.contains("640x480 px"));
		assertTrue(note, note.contains("160x120 tiles"));
		assertTrue(note, note.contains("4.00 px/tile"));
	}

	@Test
	public void aCalibratedScaleStopsWarning()
	{
		String note = WorldMap.scaleNote(640, 480, 2.5f, true);
		assertFalse(note, note.contains("PLACEHOLDER"));
		assertTrue(note, note.contains("measured"));
		assertTrue(note, note.contains("2.50 px/tile"));
	}

	@Test
	public void onlySaneScalesAreAccepted()
	{
		assertTrue(WorldMap.isUsableScale(WorldMap.PLACEHOLDER_PIXELS_PER_TILE));
		assertTrue(WorldMap.isUsableScale(0.5f));
		assertTrue(WorldMap.isUsableScale(32.0f));
		assertFalse(WorldMap.isUsableScale(0.49f));
		assertFalse(WorldMap.isUsableScale(33.0f));
	}

	@Test
	public void zeroNegativeAndNaNAreRefused()
	{
		// A zero divides by zero inside the plugin's own map maths (pixelsPerTile is a divisor in
		// mapWorldPointToGraphicsPointX/Y), and NaN propagates into every drawn coordinate silently
		// -- both worse than keeping a placeholder that is merely the wrong number.
		assertFalse(WorldMap.isUsableScale(0f));
		assertFalse(WorldMap.isUsableScale(-4f));
		assertFalse(WorldMap.isUsableScale(Float.NaN));
		assertFalse(WorldMap.isUsableScale(Float.POSITIVE_INFINITY));
		assertFalse(WorldMap.isUsableScale(Float.NEGATIVE_INFINITY));
	}
}

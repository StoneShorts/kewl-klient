// The pure half of the minimap-widget check in Perspective.localToMinimap: WHICH of the two failures
// a rectangle represents, since the two need different fixes and the log line has to name the right
// one. No client, no natives.
//
// Background (2026-09-06): the minimap overlays were refused outright because the widget read (0,0).
// The widget native has since been fixed -- the component pointer is the second half of each 16-byte
// shared_ptr entry -- so the refusal is now conditional and lifts by itself once the rectangle is
// usable. These are the cases that decide it.
package net.runelite.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PerspectiveMinimapTest
{
	@Test
	public void aRealRectangleIsAccepted()
	{
		// Resizable mode: the minimap sits in the top RIGHT, so a real x with y == 0 is normal and
		// must NOT be read as unresolved.
		assertNull(Perspective.minimapRefusal(1360, 0, 160, 160));
		assertNull(Perspective.minimapRefusal(561, 4, 146, 151));
	}

	@Test
	public void aZeroSizeSaysTheWidgetDidNotResolveAtAll()
	{
		String why = Perspective.minimapRefusal(0, 0, 0, 0);
		assertNotNull(why);
		assertTrue(why, why.contains("SIZE is 0x0"));
		assertTrue("names the size, not the position", why.contains("did not resolve at all"));
	}

	@Test
	public void aRealSizeAtTheOriginBlamesThePositionOnly()
	{
		// The case that produced the original refusal: bounds came back the right shape, the position
		// did not, because a single packed id's stored x/y is parent-relative and nothing accumulates
		// the parents. Piling every minimap name in the canvas's top-left corner is what this prevents.
		String why = Perspective.minimapRefusal(0, 0, 160, 160);
		assertNotNull(why);
		assertTrue(why, why.contains("SIZE is real (160x160)"));
		assertTrue(why, why.contains("POSITION reads (0,0)"));
		assertTrue("says what would fix it", why.contains("parent offsets"));
	}

	@Test
	public void oneRealCoordinateIsEnoughToBeResolved()
	{
		// Only BOTH coordinates at the origin is impossible for a minimap; either one alone is not.
		assertNull(Perspective.minimapRefusal(1360, 0, 160, 160));
		assertNull(Perspective.minimapRefusal(0, 40, 160, 160));
		assertNotNull(Perspective.minimapRefusal(0, 0, 160, 160));
	}

	@Test
	public void aNegativeOrPartialSizeCountsAsUnresolved()
	{
		assertTrue(Perspective.minimapRefusal(1360, 0, 160, 0).contains("did not resolve at all"));
		assertTrue(Perspective.minimapRefusal(1360, 0, -1, 160).contains("did not resolve at all"));
	}

	// -- the minimap SCALE, which is a different question from the widget resolving ----------------
	//
	// getMinimapZoom() is 4.0 and is not read from the client -- and unlike the camera yaw it cannot
	// be derived from the projection either, because the projection describes the 3D viewport while
	// the minimap is a raster the client draws by itself. The one cross-check the shim does have is
	// the widget's own size, now that widgets resolve: vanilla is 152x152 at 4 px/tile, which is also
	// where upstream's 20-tile minimap draw radius comes from. These pin what the log says about it.

	@Test
	public void theVanillaMinimapCorroboratesFourPixelsPerTile()
	{
		String note = Perspective.minimapScaleNote(152, 152, 4.0);
		assertTrue(note, note.contains("38.0x38.0 tiles"));
		assertTrue(note, note.contains("checks out"));
	}

	@Test
	public void anUnfamiliarMinimapSizeSaysTheScaleIsAnAssumption()
	{
		// A minimap this shim has never measured: the 4.0 may be right, but nothing here says so, and
		// silently drawing at it is exactly the failure mode worth naming.
		String note = Perspective.minimapScaleNote(208, 208, 4.0);
		assertTrue(note, note.contains("52.0x52.0 tiles"));
		assertTrue(note, note.contains("assumption"));
		assertFalse(note, note.contains("checks out"));
	}

	// -- and where it says the minimap IS ----------------------------------------------------------

	@Test
	public void aParentRelativeLookingPositionIsCalledOutEvenThoughItPassesTheRefusal()
	{
		// The live 2026-09-06 read: (53,8) 152x152, which minimapRefusal accepts (it only rejects the
		// exact origin) and which then draws every dot at the top LEFT of a 1606px canvas while the
		// client's own minimap is at the top right.
		assertNull("the refusal does not catch this one", Perspective.minimapRefusal(53, 8, 152, 152));
		String warn = Perspective.minimapPlacementWarning(53, 152, 1606);
		assertNotNull(warn);
		assertTrue(warn, warn.contains("PARENT-RELATIVE"));
		assertTrue(warn, warn.contains("1606px canvas"));
	}

	@Test
	public void aMinimapInTheRightHalfIsWhereAMinimapBelongs()
	{
		assertNull(Perspective.minimapPlacementWarning(1440, 152, 1606));
		assertNull(Perspective.minimapPlacementWarning(570, 152, 765)); // fixed mode, top right
		// Straddling the middle is not proof of anything, so it is not warned about.
		assertNull(Perspective.minimapPlacementWarning(760, 152, 1606));
	}

	@Test
	public void withNoCanvasToCompareAgainstNothingIsClaimed()
	{
		// viewport() comes back empty before the canvas window exists; an unknown canvas must not
		// turn into an accusation.
		assertNull(Perspective.minimapPlacementWarning(53, 152, 0));
		assertNull(Perspective.minimapPlacementWarning(53, 0, 1606));
	}

	@Test
	public void aScaleThatIsNotAScaleIsCalledOut()
	{
		// pixelsPerTile is a MULTIPLIER in localToMinimap and a DIVISOR in the draw-distance cull, so
		// a zero would place every dot on the player and a negative would mirror the whole minimap.
		assertTrue(Perspective.minimapScaleNote(152, 152, 0).contains("is not a scale"));
		assertTrue(Perspective.minimapScaleNote(152, 152, -4).contains("is not a scale"));
	}
}

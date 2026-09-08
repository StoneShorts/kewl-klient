// The pure half of the "Highlight every NPC" range gate: Chebyshev tile distance, the unit the rest
// of the client measures range in (kewl.api.Game.distanceTo and WorldPoint.distanceTo2D are both
// max(|dx|,|dy|)). No client, no natives.
package net.runelite.client.plugins.npchighlight;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.runelite.api.coords.WorldPoint;

public class NpcHighlightRangeTest
{
	private static final WorldPoint ME = new WorldPoint(3200, 3200, 0);

	@Test
	public void insideTheRangeIsInclusive()
	{
		assertTrue(NpcIndicatorsPlugin.withinRange(ME, ME, 0));
		assertTrue(NpcIndicatorsPlugin.withinRange(ME, new WorldPoint(3220, 3200, 0), 20));
		assertTrue(NpcIndicatorsPlugin.withinRange(ME, new WorldPoint(3180, 3180, 0), 20));
	}

	@Test
	public void outsideTheRangeIsRejected()
	{
		assertFalse(NpcIndicatorsPlugin.withinRange(ME, new WorldPoint(3221, 3200, 0), 20));
		assertFalse(NpcIndicatorsPlugin.withinRange(ME, new WorldPoint(3200, 3179, 0), 20));
	}

	@Test
	public void theDistanceIsChebyshevNotEuclidean()
	{
		// A diagonal at 20/20 is 28.3 tiles as the crow flies but 20 as the client counts them: it is
		// in range, the same as a straight 20. Getting this wrong would clip the corners of the box
		// the sweep mode is supposed to draw.
		assertTrue(NpcIndicatorsPlugin.withinRange(ME, new WorldPoint(3220, 3220, 0), 20));
		assertFalse(NpcIndicatorsPlugin.withinRange(ME, new WorldPoint(3221, 3221, 0), 20));
	}

	@Test
	public void anotherPlaneIsOutOfRange()
	{
		// WorldPoint.distanceTo answers Integer.MAX_VALUE across planes. Every actor reports the local
		// player's plane on this build (Actor.getWorldLocation), so this branch cannot fire in game --
		// it is pinned here so it stays "out of range" rather than overflowing into "in range" if a
		// real per-entity plane ever lands.
		assertFalse(NpcIndicatorsPlugin.withinRange(ME, new WorldPoint(3200, 3200, 1), 60));
	}

	@Test
	public void nullsAreOutOfRange()
	{
		assertFalse(NpcIndicatorsPlugin.withinRange(null, ME, 20));
		assertFalse(NpcIndicatorsPlugin.withinRange(ME, null, 20));
	}
}

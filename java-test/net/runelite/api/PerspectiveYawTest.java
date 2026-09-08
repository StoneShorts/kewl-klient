// The pure half of the camera-yaw derivation: recovering the yaw from the screen-x spread of the
// world's north and east basis vectors, and the angle conversion around it. No client, no natives.
//
// Background (2026-09-06): there is no camera-orientation offset on this build, so getCameraYawTarget()
// returned 0 and every minimap overlay drew north-up over a client whose own minimap had turned with
// the camera. The yaw is recoverable from the projection instead -- see the block above
// Perspective.yawFromScreenBasis -- and these are the cases that decide whether the recovery is
// trustworthy: the four cardinal camera headings, the wrap around the circle, the degenerate basis
// that must be refused rather than answered with a plausible-looking zero, and a full sweep against a
// simulated projection which is also what pins the accuracy claim the probe distance is chosen on.
package net.runelite.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PerspectiveYawTest
{
	/** Half the fine offset the real probe uses: two tiles either side of the player. */
	private static final int D = 2 * Perspective.LOCAL_TILE_SIZE;

	@Test
	public void theYawCircleIsTheOneTheOverlayRotatesBy()
	{
		// PathMinimapOverlay (an unchanged upstream port) rotates by yaw * Perspective.UNIT, so a
		// whole circle of yaw units must be exactly one turn. The bug this pins down is the shim
		// indexing a 16384-entry table with a 2048-unit angle, which rotated the minimap by an eighth
		// of the real yaw while the overlay's own marker rotated by all of it.
		assertEquals(2 * Math.PI, Perspective.YAW_UNITS * Perspective.UNIT, 1e-4);
	}

	@Test
	public void cameraFacingNorthReadsZero()
	{
		// Facing north: north runs straight away from the camera, so it has no screen-x spread at all,
		// and east runs to the right.
		assertEquals(0, Perspective.yawFromScreenBasis(0, 100));
	}

	@Test
	public void theFourCardinalHeadings()
	{
		// A quarter turn each, in the direction the projection's own rotation defines: north's
		// screen-x spread is the sine term, east's is the cosine.
		assertEquals(0, Perspective.yawFromScreenBasis(0, 100));    // north spread none, east right
		assertEquals(512, Perspective.yawFromScreenBasis(100, 0));  // north to the right
		assertEquals(1024, Perspective.yawFromScreenBasis(0, -100)); // east to the left
		assertEquals(1536, Perspective.yawFromScreenBasis(-100, 0)); // north to the left
	}

	@Test
	public void anglesWrapIntoZeroToTwoThousandFortySeven()
	{
		// atan2 hands back -pi..pi, so half the circle arrives negative; a raw cast would index the
		// rotation table at -1 and throw, or (worse) be masked into a wrong-quadrant angle.
		assertEquals(0, Perspective.yawUnits(0));
		assertEquals(512, Perspective.yawUnits(Math.PI / 2));
		assertEquals(1024, Perspective.yawUnits(Math.PI));
		assertEquals(1536, Perspective.yawUnits(-Math.PI / 2));
		assertEquals(1024, Perspective.yawUnits(-Math.PI));
		assertEquals(0, Perspective.yawUnits(2 * Math.PI));
		assertEquals(2047, Perspective.yawUnits(-Perspective.UNIT));
		for (int a = 0; a < Perspective.YAW_UNITS; a++)
		{
			int wrapped = Perspective.yawUnits(a * Perspective.UNIT);
			assertTrue("in range: " + wrapped, wrapped >= 0 && wrapped < Perspective.YAW_UNITS);
		}
	}

	@Test
	public void aCollapsedBasisIsRefusedRatherThanAnsweredWithNorth()
	{
		// The dangerous case: atan2(0, 0) is 0.0 in Java, which is a perfectly plausible NORTH. If
		// this returned it, a frame where the projection collapsed (focus on the near plane, camera
		// straight down onto a degenerate point, the leaf returning one pixel for every input) would
		// silently snap the whole minimap back to north-up for that frame.
		assertEquals(-1, Perspective.yawFromScreenBasis(0, 0));
		assertEquals(-1, Perspective.yawFromScreenBasis(1, 0));
		assertEquals(-1, Perspective.yawFromScreenBasis(0, -1));
		// One pixel more than the floor is a direction, not noise.
		assertTrue(Perspective.yawFromScreenBasis(2, 0) >= 0);
	}

	@Test
	public void aPitchedCameraDoesNotTiltTheAnswer()
	{
		// The whole reason the derivation uses the screen-X components of TWO basis vectors rather
		// than the bearing of one: the pitch foreshortening lives entirely in screen Y, so a camera
		// looking flat along the ground and one looking almost straight down must recover the same
		// yaw from the same heading. atan2 of a single north delta would not -- it would read the
		// pitch as a rotation.
		for (int pitch : new int[]{0, 30, 60, 89})
		{
			assertYawRecovers(700, pitch, 8);
		}
	}

	@Test
	public void everyHeadingAndPitchRecoversToWithinAFewUnits()
	{
		// The sweep the two-tile probe distance is chosen on: worst case ~4 units (0.7 degrees) with
		// whole-pixel projection output, which over the minimap's 19-tile draw radius is a fraction
		// of a tile. Ten units of slack, so an implementation that drifted by a visible amount fails.
		int worst = 0;
		for (int pitch : new int[]{0, 15, 30, 45, 60, 75, 89})
		{
			for (int a = 0; a < Perspective.YAW_UNITS; a += 7)
			{
				worst = Math.max(worst, yawError(a, pitch));
			}
		}
		assertTrue("worst error " + worst + " units", worst <= 10);
	}

	private static void assertYawRecovers(int yaw, int pitchDegrees, int tolerance)
	{
		int err = yawError(yaw, pitchDegrees);
		assertTrue("yaw " + yaw + " pitch " + pitchDegrees + " off by " + err, err <= tolerance);
	}

	private static int yawError(int yaw, int pitchDegrees)
	{
		int[] basis = simulateBasis(yaw, pitchDegrees);
		int got = Perspective.yawFromScreenBasis(basis[0], basis[1]);
		assertTrue("a real basis must not be refused (yaw " + yaw + ")", got >= 0);
		int diff = Math.abs(got - yaw);
		return Math.min(diff, Perspective.YAW_UNITS - diff);
	}

	/**
	 * {northScreenDx, eastScreenDx} as the game's projection would produce them, from the shape the
	 * derivation is built on: rotate the offset by the yaw, foreshorten the depth by the pitch,
	 * divide, and round to whole pixels the way the projection native does. The depth base and canvas
	 * scale are a plausible in-game camera; what matters is that the pitch changes the DEPTH (and so
	 * the perspective divide) without ever entering the screen-x numerator.
	 */
	private static int[] simulateBasis(int yawUnits, int pitchDegrees)
	{
		double a = yawUnits * Perspective.UNIT;
		double cos = Math.cos(a), sin = Math.sin(a);
		double pitchCos = Math.cos(Math.toRadians(pitchDegrees));
		final double depthBase = 2500, scale = 512, centreX = 800;
		int[][] offsets = {{0, D}, {0, -D}, {D, 0}, {-D, 0}};
		int[] screenX = new int[4];
		for (int i = 0; i < offsets.length; i++)
		{
			double east = offsets[i][0], north = offsets[i][1];
			double x1 = east * cos + north * sin;
			double y1 = north * cos - east * sin;
			screenX[i] = (int) Math.round(centreX + x1 * scale / (depthBase + y1 * pitchCos));
		}
		return new int[]{screenX[0] - screenX[1], screenX[2] - screenX[3]};
	}
}

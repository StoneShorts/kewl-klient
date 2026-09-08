// The pure half of the camera-PITCH derivation, the sibling of PerspectiveYawTest.
//
// Background (2026-09-06): the yaw was recovered from the screen-X spread of the world's north and
// east basis vectors, with no new offset. The pitch falls out of the same probes carried one step
// further into screen Y -- see the block above Perspective.pitchFromScreenBasis for the algebra.
// These are the cases that decide whether the recovery is trustworthy rather than plausible:
//
//   * every pitch the game can actually show, at every heading, against a simulated projection
//     built from the camera model the derivation claims (and NOT from the derivation itself -- the
//     simulation multiplies out a rotation and a perspective divide, so if the model were wrong the
//     sweep would fail);
//   * the two forms compared, because the four-probe form is the fallback and has to be shown to be
//     a fallback rather than an equal;
//   * the vertical-scale immunity that is the whole reason the five-probe form exists;
//   * the degenerate basis, which must be REFUSED rather than answered with a plausible zero -- a
//     pitch of 0 is a level camera, and reporting one for "I could not tell" is exactly the failure
//     mode that made "the camera shows zeros" impossible to act on.
package net.runelite.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PerspectivePitchTest
{
	/** Half the fine offset the real probe uses: two tiles either side of the player. */
	private static final int D = 2 * Perspective.LOCAL_TILE_SIZE;

	/**
	 * The pitches OSRS can actually put the camera at, in the shim's 0..2047 units. The client's own
	 * clamp is roughly 128 (a shallow, near-level view) to 383 (the steepest ordinary view); 512 is
	 * straight down, which the game only reaches in special views but which the derivation must not
	 * fall apart at.
	 */
	private static final int[] IN_GAME_PITCHES = { 128, 192, 256, 320, 383, 448, 512 };

	@Test
	public void straightDownIsAQuarterOfTheYawCircle()
	{
		// The units are the yaw's units -- 2048 to the turn -- so a right angle is 512. Stated as a
		// test because every other number in this file is checked against it.
		assertEquals(Perspective.YAW_UNITS / 4, Perspective.PITCH_STRAIGHT_DOWN);
		assertEquals(Math.PI / 2, Perspective.PITCH_STRAIGHT_DOWN * Perspective.UNIT, 1e-4);
	}

	@Test
	public void aLevelCameraReadsZeroAndAVerticalOneReadsStraightDown()
	{
		// The two ends, from the model rather than from a table: a level camera foreshortens nothing
		// (the vertical pair spans the full basis, the horizontal pairs span none of it in Y), and a
		// camera looking straight down is the reverse.
		assertEquals(0, pitchAt(0, 0, 1.0), 0);
		assertEquals(Perspective.PITCH_STRAIGHT_DOWN, pitchAt(0, Perspective.PITCH_STRAIGHT_DOWN, 1.0), 2);
	}

	@Test
	public void everyHeadingAndPitchRecoversToWithinAFewUnits()
	{
		// The sweep that pins the accuracy claim. Whole-pixel projection output at the two-tile probe
		// distance the shim uses; the tolerance is the same order as the yaw's (~4 units, 0.7
		// degrees), so a form that drifted by a visible amount fails here.
		int worst = 0;
		for (int pitch : IN_GAME_PITCHES)
		{
			for (int yaw = 0; yaw < Perspective.YAW_UNITS; yaw += 7)
			{
				int got = pitchAt(yaw, pitch, 1.0);
				assertTrue("a real basis must not be refused (yaw " + yaw + " pitch " + pitch + ")",
					got >= 0);
				worst = Math.max(worst, Math.abs(got - pitch));
			}
		}
		// Measured worst case is 4 units across the whole sweep; 5 leaves one unit of slack for a
		// different rounding on another JDK without letting a visible drift through.
		assertTrue("worst error " + worst + " units", worst <= 5);
	}

	@Test
	public void theYawIsUnaffectedByTheSameProbes()
	{
		// The two derivations share one probe sweep, so a change to either must not disturb the
		// other: the yaw comes out of the screen-X terms alone and cannot see the pitch at all.
		for (int pitch : IN_GAME_PITCHES)
		{
			for (int yaw = 0; yaw < Perspective.YAW_UNITS; yaw += 61)
			{
				int[] b = simulateBasis(yaw, pitch, 1.0);
				int got = Perspective.yawFromScreenBasis(b[0], b[2]);
				int diff = Math.abs(got - yaw);
				assertTrue("yaw " + yaw + " at pitch " + pitch + " recovered " + got,
					Math.min(diff, Perspective.YAW_UNITS - diff) <= 8);
			}
		}
	}

	@Test
	public void theAtan2FormIsImmuneToTheVerticalProjectionScale()
	{
		// The reason the vertical probe pair is worth two extra projections a frame. The four-probe
		// form has to assume the client divides ONE scale into both screen axes; the five-probe form
		// has both a sine and a cosine term carrying that scale, so it cancels. Simulate a client
		// that stretched screen Y by half again and check which form survives.
		final double stretched = 1.5;
		for (int pitch : new int[]{ 192, 256, 320 })
		{
			int atan2 = pitchAt(700, pitch, stretched);
			int asin = asinPitchAt(700, pitch, stretched);
			assertTrue("atan2 form off by " + Math.abs(atan2 - pitch), Math.abs(atan2 - pitch) <= 8);
			assertTrue("the asin form should be visibly wrong under a stretched Y, was " + asin,
				Math.abs(asin - pitch) > 20);
		}
	}

	@Test
	public void theAsinFallbackIsGoodEnoughWhenTheScalesMatch()
	{
		// It is the fallback for the frame where the vertical pair will not project, so it has to be
		// worth falling back TO. With one scale for both axes it tracks the atan2 form closely away
		// from vertical -- which is also what pitchFormsNote's agreement threshold encodes.
		for (int pitch : new int[]{ 128, 192, 256, 320, 383 })
		{
			int asin = asinPitchAt(1300, pitch, 1.0);
			assertTrue("asin form off by " + Math.abs(asin - pitch) + " at pitch " + pitch,
				Math.abs(asin - pitch) <= 10);
		}
	}

	@Test
	public void aCollapsedBasisIsRefusedRatherThanAnsweredWithALevelCamera()
	{
		// The dangerous case, and the reason -1 rather than 0 is the "unknown" value all the way up
		// to Client.getCameraPitch: 0 is a perfectly plausible level camera. Both forms must refuse.
		assertEquals(-1, Perspective.pitchFromScreenBasis(0, 0, 0, 0));
		assertEquals(-1, Perspective.pitchFromScreenBasis(0, 0, 0, 0, 0));
		assertEquals(-1, Perspective.pitchFromScreenBasis(1, 0, 0, 0, -50));
		// A horizontal basis that resolves but a vertical pair that collapsed to nothing: the sine
		// term is real, so this one is answerable and must NOT be refused.
		assertTrue(Perspective.pitchFromScreenBasis(0, -80, 100, 0, 0) >= 0);
	}

	@Test
	public void aPitchIsNotABearingAndDoesNotWrap()
	{
		// yawUnits wraps -1 to 2047 because a bearing is circular. A pitch is not: a camera a hair
		// above the horizon is a level camera plus rounding, and wrapping it to 2047 would hand the
		// caller a camera pointing almost all the way round. OSRS never lets the camera rise above
		// the horizon at all, so clamping is the honest reading.
		assertEquals(0, Perspective.pitchUnits(0));
		assertEquals(0, Perspective.pitchUnits(-Perspective.UNIT));
		assertEquals(0, Perspective.pitchUnits(-Math.PI / 4));
		assertEquals(256, Perspective.pitchUnits(Math.PI / 4));
		assertEquals(Perspective.PITCH_STRAIGHT_DOWN, Perspective.pitchUnits(Math.PI / 2));
		assertEquals(Perspective.PITCH_STRAIGHT_DOWN, Perspective.pitchUnits(Math.PI));
	}

	@Test
	public void theCrossCheckSaysWhichWayTheFormsDisagree()
	{
		// The note is the only place a wrong single-scale assumption would ever surface, so its two
		// branches are worth pinning rather than arguing about.
		String agree = Perspective.pitchFormsNote(300, 300 + Perspective.PITCH_FORM_AGREEMENT_UNITS);
		assertNotNull(agree);
		assertTrue(agree, agree.contains("agree"));
		assertTrue(agree, agree.contains("one scale for both screen axes"));

		String differ = Perspective.pitchFormsNote(300, 340);
		assertNotNull(differ);
		assertTrue(differ, differ.startsWith("WARNING"));
		assertTrue("the note must say which form is in force: " + differ, differ.contains("atan2"));

		// Nothing to compare when either form refused; a note about a refusal would be noise.
		assertNull(Perspective.pitchFormsNote(-1, 300));
		assertNull(Perspective.pitchFormsNote(300, -1));
	}

	// -- the simulated projection -------------------------------------------------------------------

	private static int pitchAt(int yawUnits, int pitchUnits, double verticalScale)
	{
		int[] b = simulateBasis(yawUnits, pitchUnits, verticalScale);
		return Perspective.pitchFromScreenBasis(b[0], b[1], b[2], b[3], b[4]);
	}

	private static int asinPitchAt(int yawUnits, int pitchUnits, double verticalScale)
	{
		int[] b = simulateBasis(yawUnits, pitchUnits, verticalScale);
		return Perspective.pitchFromScreenBasis(b[0], b[1], b[2], b[3]);
	}

	/**
	 * {northDx, northDy, eastDx, eastDy, upDy} as the game's projection would produce them.
	 *
	 * <p>Built from the camera model, not from the derivation: rotate the world offset into camera
	 * axes, divide by the real depth, round to whole pixels the way the projection native does. Note
	 * the vertical probe is expressed in WORLD up (+D is higher), which the shim produces by passing
	 * {@code height - D} because the client's height axis is negative = up.</p>
	 *
	 * @param verticalScale screen-Y scale relative to screen-X; 1.0 is what the RS projection does
	 */
	private static int[] simulateBasis(int yawUnits, int pitchUnits, double verticalScale)
	{
		double a = yawUnits * Perspective.UNIT;
		double p = pitchUnits * Perspective.UNIT;
		double cosA = Math.cos(a), sinA = Math.sin(a);
		double cosP = Math.cos(p), sinP = Math.sin(p);
		final double depthBase = 2500, scale = 512, centreX = 800, centreY = 400;

		// {east, north, up} offsets: the north pair, the east pair, then the vertical pair.
		int[][] offsets = {{0, D, 0}, {0, -D, 0}, {D, 0, 0}, {-D, 0, 0}, {0, 0, D}, {0, 0, -D}};
		int[] sx = new int[offsets.length];
		int[] sy = new int[offsets.length];
		for (int i = 0; i < offsets.length; i++)
		{
			double east = offsets[i][0], north = offsets[i][1], up = offsets[i][2];
			double right = east * cosA + north * sinA;      // delta . r^
			double ahead = north * cosA - east * sinA;      // delta . h^
			double depth = depthBase + ahead * cosP - up * sinP;
			double screenUp = ahead * sinP + up * cosP;     // delta . u^
			sx[i] = (int) Math.round(centreX + right * scale / depth);
			sy[i] = (int) Math.round(centreY - screenUp * scale * verticalScale / depth);
		}
		return new int[]{ sx[0] - sx[1], sy[0] - sy[1], sx[2] - sx[3], sy[2] - sy[3], sy[4] - sy[5] };
	}
}

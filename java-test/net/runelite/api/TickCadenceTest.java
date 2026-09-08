// Client.getTickCount's one unproven assumption, and the probe that now measures it.
//
// getTickCount returns cycle()/30. The 30 encodes "CYCLE advances once per 20ms", i.e. 50 bumps a
// second. offsets.hpp says in as many words that this cadence was never re-proven -- the binary only
// shows one bump per FRAME CALLBACK -- so on a client whose frames are not a fixed 50Hz timestep every
// value getTickCount returns is wrong by the frame-rate ratio, and every tick-delta comparison in the
// ported plugins drifts with it. That is the worst kind of gap in this shim, because the number stays
// plausible: nothing on screen looks wrong, things just fire at the wrong moment.
//
// Client.measureCycleCadence is the answer to that: it times CYCLE against the wall clock and says out
// loud whether 50/s is what actually happens. It reports and does NOT change the divisor -- switching
// divisors mid-session would make getTickCount jump backwards and every plugin holding a "last tick"
// would fire at once.
//
// This suite exists because a diagnostic that lies is worse than none. It pins the two ways this one
// could lie: concluding from a client that was not running frames, and concluding across a relog that
// reset the counter.
package net.runelite.api;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TickCadenceTest
{
	private static final long SECOND = 1_000_000_000L;

	@Before
	public void clean()
	{
		Client.resetCycleProbe();
		ShimSupport.reset();
		ShimSupport.setLogging(false);
	}

	@After
	public void restore()
	{
		Client.resetCycleProbe();
		ShimSupport.reset();
	}

	@Test
	public void itSaysNothingUntilItHasWatchedLongEnough()
	{
		assertNull(Client.measureCycleCadence(100, 0L));
		// Nine seconds in it still has no business concluding: a burst of frames at the start of a
		// session would make any shorter window read fast.
		assertNull(Client.measureCycleCadence(550, 9 * SECOND));
	}

	@Test
	public void fiftyPerSecondConfirmsTheDivisor()
	{
		assertNull(Client.measureCycleCadence(1000, 0L));
		final String line = Client.measureCycleCadence(1500, 10 * SECOND);
		assertNotNull("ten seconds is the window; it must answer", line);
		assertTrue(line, line.contains("50.0/s"));
		assertTrue("50/s is exactly what /30 assumes: " + line, line.contains("is RIGHT on this build"));
	}

	@Test
	public void aRenderPacedCounterIsCalledOutWithTheNumberToFixItBy()
	{
		// The failure this whole probe exists for: CYCLE bumping once per RENDERED frame. At 120fps
		// that is 120/s, so a "tick" is 72 cycles rather than 30 and getTickCount runs 2.4x fast.
		assertNull(Client.measureCycleCadence(1000, 0L));
		final String line = Client.measureCycleCadence(1000 + 1200, 10 * SECOND);
		assertNotNull(line);
		assertTrue(line, line.contains("120.0/s"));
		assertTrue("must name the correct cycles-per-tick: " + line, line.contains("72 cycles"));
		assertTrue("must name the factor the plugins drift by: " + line, line.contains("2.40"));
	}

	@Test
	public void aClientThatRanNoFramesIsNotAMeasurement()
	{
		// Logged out, minimised, or the window blocked: CYCLE does not move. "0.0/s, wrong by a factor
		// of 0.00" would be a confident wrong answer printed once and never revisited, so the probe
		// must restart instead and measure over the next ten seconds of actual play.
		assertNull(Client.measureCycleCadence(1000, 0L));
		assertNull("no frames ran; that is not a cadence of zero",
			Client.measureCycleCadence(1000, 10 * SECOND));

		// Restarted from the second sample, so a normal ten seconds after it still answers.
		final String line = Client.measureCycleCadence(1500, 20 * SECOND);
		assertNotNull(line);
		assertTrue(line, line.contains("50.0/s"));
	}

	@Test
	public void aRelogThatResetsTheCounterRestartsTheProbe()
	{
		// CYCLE going BACKWARDS means the client started counting again. Measuring across that
		// boundary would divide a negative bump count by ten seconds and print nonsense.
		assertNull(Client.measureCycleCadence(9000, 0L));
		assertNull("the counter reset; start again rather than measure across it",
			Client.measureCycleCadence(5, 5 * SECOND));

		final String line = Client.measureCycleCadence(505, 15 * SECOND);
		assertNotNull(line);
		assertTrue(line, line.contains("50.0/s"));
	}

	@Test
	public void itAnswersOnceAndThenCostsNothing()
	{
		assertNull(Client.measureCycleCadence(1000, 0L));
		assertNotNull(Client.measureCycleCadence(1500, 10 * SECOND));
		// A per-frame getter must not print a line per frame for the rest of the session.
		assertNull(Client.measureCycleCadence(2000, 20 * SECOND));
		assertNull(Client.measureCycleCadence(2500, 30 * SECOND));
	}

	@Test
	public void aCounterThatHasNotStartedIsIgnored()
	{
		// cycle() reads 0 before the player exists. Anchoring the probe there would time the login
		// screen.
		assertNull(Client.measureCycleCadence(0, 0L));
		assertNull(Client.measureCycleCadence(0, 60 * SECOND));
	}
}

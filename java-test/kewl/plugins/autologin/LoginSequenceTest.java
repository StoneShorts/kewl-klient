package kewl.plugins.autologin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import kewl.plugins.autologin.LoginSequence.Phase;
import kewl.plugins.autologin.LoginSequence.Settings;

/**
 * The login state machine, driven by a manual clock and a recording sink. No natives, no game.
 *
 * <p>The script order and the result handling follow what was seen live on 2026-09-06: Existing
 * User click, the password typed into the already-focused field, the Login BUTTON clicked (Enter
 * does not submit), and a result timeout with the state still 10 meaning the rejection screen. The
 * tests that encoded "Enter submits" and "a timeout means nothing typed" were deleted with that.</p>
 *
 * <p>The password used here is deliberately distinctive so the leak test can grep for it across
 * everything the sequence ever turns into a string.</p>
 */
public class LoginSequenceTest {

	private static final String USER = "someone@example.com";
	private static final String PASS = "Zz9!secret";
	private static final int LOGIN = 10;
	/** The canvas every test arms with: centre x = 400, so the default offsets land at 400 + x, y. */
	private static final int W = 800, H = 600;

	/** Records every emission with the clock time it went out. */
	static final class Recorder implements InputSink {
		final List<String> events = new ArrayList<>();
		final List<Long> times = new ArrayList<>();
		long now;

		@Override public boolean postChar(int c) { record("char"); return true; }
		@Override public boolean postKey(int vk, boolean down) { record("key " + vk + (down ? " down" : " up")); return true; }
		@Override public boolean postMouse(int x, int y, int action) { record("mouse " + action + " " + x + "," + y); return true; }

		private void record(String e) { events.add(e); times.add(now); }
	}

	private Recorder sink;
	private LoginSequence seq;
	private Settings settings;
	private long now;
	private final StringBuilder everything = new StringBuilder();

	@Before
	public void setUp()
	{
		sink = new Recorder();
		settings = Settings.defaults();
		seq = new LoginSequence(sink, settings);
		now = 100_000;
	}

	/** One frame: advance the clock, step, collect every string the sequence produces. */
	private Phase frame(long advanceMs, int raw)
	{
		now += advanceMs;
		sink.now = now;
		Phase p = seq.step(now, raw);
		for (String l : seq.drainLog()) everything.append(l).append('\n');
		everything.append(seq.status()).append('\n').append(seq.toString()).append('\n');
		return p;
	}

	/** Frames of 33 ms until the predicate holds or the budget runs out. */
	private Phase runUntil(int raw, long budgetMs, java.util.function.BooleanSupplier done)
	{
		Phase p = seq.phase();
		for (long t = 0; t < budgetMs && !done.getAsBoolean(); t += 33) p = frame(33, raw);
		return p;
	}

	/** Title screen up, settle over, script armed -- the state just before typing starts. */
	private void armAtTitle()
	{
		frame(0, 0);
		frame(33, LOGIN);
		runUntil(LOGIN, settings.settleMs() + 1000, () -> seq.wantsScript());
		assertTrue("settle over -> the plugin is asked for a script", seq.wantsScript());
		seq.arm(USER, PASS, W, H);
		everything.append(seq.status()).append('\n');
	}

	private void typeWholeScript()
	{
		runUntil(LOGIN, 60_000, () -> seq.phase() != Phase.RUNNING);
		assertEquals(Phase.AWAITING_RESULT, seq.phase());
	}

	/** The state stays 10 for the whole result timeout: on this build that is the rejection screen. */
	private Phase waitOutTheResultTimeout()
	{
		return runUntil(LOGIN, settings.resultTimeoutMs() + 1000, () -> seq.phase() != Phase.AWAITING_RESULT);
	}

	private static void addClick(List<String> to, int x, int y)
	{
		to.add("mouse 0 " + x + "," + y);
		to.add("mouse 1 " + x + "," + y);
		to.add("mouse 2 " + x + "," + y);
	}

	private static void addKey(List<String> to, int vk)
	{
		to.add("key " + vk + " down");
		to.add("key " + vk + " up");
	}

	@Test
	public void nothingIsPostedWhileTheStateIsNotTheLoginScreen()
	{
		for (int i = 0; i < 300; i++) frame(33, 0);
		assertEquals(Phase.WAITING_FOR_LOGIN_SCREEN, seq.phase());
		assertFalse(seq.wantsScript());
		assertTrue(sink.events.isEmpty());
		// Even an armed script does not type at raw state 5 (a pre-title value): arm() is only
		// honoured from settling, and the machine never settles off the login state.
		for (int i = 0; i < 300; i++) frame(33, 5);
		assertFalse(seq.wantsScript());
		assertTrue(sink.events.isEmpty());
		assertTrue("an unexpected raw state is called out for calibration",
				everything.toString().contains("raw state 5 is not the configured login state 10"));
	}

	@Test
	public void defaultPathIsExistingUserBackspacesPasswordEnterThenTheLoginClick()
	{
		assertTrue("the default assumes 'Remember username' is ticked", settings.usernameRemembered());
		armAtTitle();
		typeWholeScript();

		List<String> expected = new ArrayList<>();
		addClick(expected, W / 2 + 69, 288);                          // Existing User (centre + 69, top + 288)
		for (int i = 0; i < settings.clearBackspaces(); i++) addKey(expected, 8);
		for (int i = 0; i < PASS.length(); i++) expected.add("char"); // straight into the password field
		addKey(expected, 13);                                          // harmless: Enter does not submit
		addClick(expected, W / 2 - 93, 315);                          // the Login button, which does
		assertEquals(expected, sink.events);
		assertEquals("the username is never typed on the remembered path", 0, seq.usernameChars());
		assertEquals(PASS.length(), seq.passwordChars());
		// What the plugin is allowed to ask, and all it is allowed to ask: set or empty, never a count.
		assertFalse(seq.usernameTyped());
		assertTrue(seq.passwordTyped());
		assertTrue(everything.toString().contains("Login clicked"));
	}

	@Test
	public void noTwoEmissionsCloserThanTheKeyDelay()
	{
		armAtTitle();
		typeWholeScript();
		for (int i = 1; i < sink.times.size(); i++)
		{
			// A click is a move and a down in the same slot, as a real mouse would; the up is paced.
			if (sink.events.get(i).startsWith("mouse 1 ") && sink.events.get(i - 1).startsWith("mouse 0 ")) continue;
			long gap = sink.times.get(i) - sink.times.get(i - 1);
			assertTrue("emission " + i + " came " + gap + " ms after the previous one",
					gap >= settings.keyDelayMs());
		}
	}

	@Test
	public void stateLeavingTheLoginScreenMeansSubmittedThenLoggedIn()
	{
		armAtTitle();
		typeWholeScript();
		assertEquals(Phase.SUBMITTED, frame(500, 20));
		assertEquals(Phase.SUBMITTED, frame(500, 25));
		assertEquals(Phase.LOGGED_IN, frame(500, 30));
		assertEquals("logged in", seq.status());
		assertTrue(everything.toString().contains("submitted; state 10 -> 20 after"));
	}

	@Test
	public void noStateChangeAfterTheLoginClickIsARejectionTryAgainIsClickedAndMaxRejectsStopsIt()
	{
		armAtTitle();
		typeWholeScript();
		int firstRun = sink.events.size();

		// The state never leaves 10: that is the "Incorrect username or password" screen.
		assertEquals(Phase.DISMISSING_REJECTION, waitOutTheResultTimeout());
		assertEquals(1, seq.rejects());
		assertEquals(1, seq.attempts());
		assertTrue(everything.toString().contains(
				"no state change after Login click: assuming the rejection screen, clicking Try again"));
		assertTrue(seq.status().contains("Try again"));

		// Try again (centre - 14, top + 288) is clicked, then the machine settles for the form.
		runUntil(LOGIN, 5000, () -> seq.phase() != Phase.DISMISSING_REJECTION);
		assertEquals(Phase.SETTLING, seq.phase());
		List<String> tryAgain = new ArrayList<>();
		addClick(tryAgain, W / 2 - 14, 288);
		assertEquals(tryAgain, sink.events.subList(firstRun, sink.events.size()));
		int afterTryAgain = sink.events.size();

		// The attempt script again -- without the Existing User click: the form is already up. Since
		// the screen-variant work it also clicks the password field first, because nothing on a screen
		// that was already showing says where the caret is (LoginScreen.FORM.focusesPasswordField()).
		runUntil(LOGIN, settings.settleMs() + 1000, () -> seq.wantsScript());
		assertTrue("re-armed after Try again", seq.wantsScript());
		assertEquals(LoginScreen.FORM, seq.screen());
		seq.arm(USER, PASS, W, H);
		typeWholeScript();
		List<String> rerun = new ArrayList<>();
		addClick(rerun, W / 2 - 82, 257);                             // the password field
		for (int i = 0; i < settings.clearBackspaces(); i++) addKey(rerun, 8);
		for (int i = 0; i < PASS.length(); i++) rerun.add("char");
		addKey(rerun, 13);
		addClick(rerun, W / 2 - 93, 315);
		assertEquals(rerun, sink.events.subList(afterTryAgain, sink.events.size()));

		// The second rejection is the last (maxRejects = 2): stop, and say why.
		assertEquals(Phase.GAVE_UP, waitOutTheResultTimeout());
		assertEquals(2, seq.rejects());
		assertTrue(seq.status(), seq.status().startsWith("rejected 2 times -- check the credentials"));
		assertTrue(seq.status().contains("Jagex Account"));
		assertTrue(seq.status().contains("Jagex Launcher"));
		int after = sink.events.size();
		for (int i = 0; i < 1000; i++) frame(33, LOGIN);
		assertFalse(seq.wantsScript());
		assertEquals("nothing more is typed or clicked", after, sink.events.size());
		assertEquals(0, seq.queued());
	}

	@Test
	public void aFreshStartAfterAGiveUpClicksExistingUserAgain()
	{
		armAtTitle();
		typeWholeScript();
		waitOutTheResultTimeout();
		runUntil(LOGIN, 5000, () -> seq.phase() != Phase.DISMISSING_REJECTION);
		runUntil(LOGIN, settings.settleMs() + 1000, () -> seq.wantsScript());
		seq.arm(USER, PASS, W, H);
		typeWholeScript();
		assertEquals(Phase.GAVE_UP, waitOutTheResultTimeout());
		// The hotkey: back to the welcome box, so the next script starts with the Existing User click.
		seq.loginNow();
		int before = sink.events.size();
		runUntil(LOGIN, LoginSequence.QUICK_SETTLE_MS + 200, () -> seq.wantsScript());
		seq.arm(USER, PASS, W, H);
		runUntil(LOGIN, 1000, () -> sink.events.size() > before);
		assertEquals("mouse 0 " + (W / 2 + 69) + ",288", sink.events.get(before));
	}

	@Test
	public void bounceBackToTheLoginScreenCountsARejectAndMaxRejectsStopsIt()
	{
		armAtTitle();
		typeWholeScript();
		frame(500, 20);
		assertEquals(Phase.BACKOFF, frame(2000, LOGIN));
		assertEquals(1, seq.rejects());
		runUntil(LOGIN, 120_000, () -> seq.wantsScript());
		seq.arm(USER, PASS, W, H);
		typeWholeScript();
		frame(500, 20);
		assertEquals(Phase.GAVE_UP, frame(2000, LOGIN));
		assertEquals(2, seq.rejects());
		assertTrue(seq.status().startsWith("rejected 2 times"));
		assertTrue(everything.toString().contains("server bounced us back to the login screen"));
	}

	/**
	 * Seen live 2026-09-06: a back-off can run for a minute, and a human who gets tired of waiting
	 * logs in themselves -- which also arrives at state 30. The play click is only ever ours.
	 */
	@Test
	public void aManualLoginDuringALongBackOffGetsNoPlayClick()
	{
		armAtTitle();
		typeWholeScript();
		frame(500, 20);
		assertEquals(Phase.BACKOFF, frame(2000, LOGIN));
		// The client sits on something that is not the login screen (so the back-off cannot end) for
		// longer than the result timeout: whatever reaches 30 after that is not our submission.
		runUntil(0, settings.resultTimeoutMs() + 2000, () -> false);
		int before = sink.events.size();
		assertEquals(Phase.LOGGED_IN, frame(33, 30));
		assertFalse("a human clicked play; ours would land in the loaded world", seq.playPending());
		for (int i = 0; i < 300; i++) frame(33, 30);
		assertEquals("nothing is posted into the game world", before, sink.events.size());
		assertEquals("logged in", seq.status());
	}

	/** The other half of the same rule: a slow server accepting what we typed IS ours. */
	@Test
	public void aSlowAcceptDuringTheBackOffStillClicksPlay()
	{
		armAtTitle();
		typeWholeScript();
		frame(500, 20);
		assertEquals(Phase.BACKOFF, frame(2000, LOGIN));
		assertEquals(Phase.LOGGED_IN, frame(500, 30));      // well inside the result timeout: ours
		assertTrue(seq.playPending());
		int before = sink.events.size();
		runUntil(30, settings.playDelayMs() + 1000, () -> sink.events.size() > before + 2);
		List<String> play = new ArrayList<>();
		addClick(play, W / 2 - 3, 334);
		assertEquals(play, sink.events.subList(before, sink.events.size()));
		assertEquals("logged in -- clicked play (the configured offset)", seq.status());
	}

	/**
	 * A world hop is 30 -> 45 -> 25 -> 30 (seen live). None of those is the title screen, and the
	 * "set the loginState setting to X" advice must not be offered for any of them: a user who
	 * followed it would have the sequence treat every later hop as the login screen and type the
	 * password into the game.
	 */
	@Test
	public void aWorldHopIsNeverOfferedAsTheLoginState()
	{
		armAtTitle();
		typeWholeScript();
		frame(500, 20);
		assertEquals(Phase.LOGGED_IN, frame(500, 30));
		frame(600, 45);
		frame(600, 25);
		assertEquals("a hop is not a logout", Phase.LOGGED_IN, frame(600, 30));
		String all = everything.toString();
		assertFalse(all, all.contains("is not the configured login state"));

		// Nor before the first login: 40, 45 and 1000 are known hop/loading values on this client,
		// so they are expected states rather than candidate login screens.
		seq = new LoginSequence(sink, settings);
		everything.setLength(0);
		frame(33, 45);
		frame(33, 1000);
		frame(33, 40);
		assertFalse(everything.toString(), everything.toString().contains("is not the configured login state"));
	}

	@Test
	public void authenticatorScreenStopsWithoutRetry()
	{
		armAtTitle();
		typeWholeScript();
		frame(500, 20);
		assertEquals(Phase.STOPPED_AUTHENTICATOR, frame(500, 11));
		assertEquals("authenticator screen -- stopped", seq.status());
		int after = sink.events.size();
		for (int i = 0; i < 1000; i++) frame(33, 11);
		for (int i = 0; i < 1000; i++) frame(33, LOGIN);
		assertFalse(seq.wantsScript());
		assertEquals(after, sink.events.size());
	}

	/** Same as the defaults but with the automatic relogin opted in. */
	private static Settings withRelogin(Settings d)
	{
		return new Settings(d.loginState(), d.settleMs(), d.keyDelayMs(), d.resultTimeoutMs(), d.maxAttempts(),
				d.maxRejects(), d.usernameRemembered(), d.clearBackspaces(), d.tabToPassword(), d.clickExistingUser(),
				d.existingX(), d.existingY(), d.usernameX(), d.usernameY(), d.passwordX(), d.passwordY(),
				d.loginX(), d.loginY(), d.tryAgainX(), d.tryAgainY(), d.afterClickMs(), true,
				d.clickPlay(), d.playX(), d.playY(), d.playDelayMs());
	}

	/** The defaults with the "CLICK HERE TO PLAY" click switched off. */
	private static Settings withoutPlayClick(Settings d)
	{
		return new Settings(d.loginState(), d.settleMs(), d.keyDelayMs(), d.resultTimeoutMs(), d.maxAttempts(),
				d.maxRejects(), d.usernameRemembered(), d.clearBackspaces(), d.tabToPassword(), d.clickExistingUser(),
				d.existingX(), d.existingY(), d.usernameX(), d.usernameY(), d.passwordX(), d.passwordY(),
				d.loginX(), d.loginY(), d.tryAgainX(), d.tryAgainY(), d.afterClickMs(), d.reloginAfterDisconnect(),
				false, d.playX(), d.playY(), d.playDelayMs());
	}

	/** The defaults with room for one more rejection than the two the panel stops at. */
	private static Settings withMaxRejects(Settings d, int maxRejects)
	{
		return new Settings(d.loginState(), d.settleMs(), d.keyDelayMs(), d.resultTimeoutMs(), d.maxAttempts(),
				maxRejects, d.usernameRemembered(), d.clearBackspaces(), d.tabToPassword(), d.clickExistingUser(),
				d.existingX(), d.existingY(), d.usernameX(), d.usernameY(), d.passwordX(), d.passwordY(),
				d.loginX(), d.loginY(), d.tryAgainX(), d.tryAgainY(), d.afterClickMs(), d.reloginAfterDisconnect(),
				d.clickPlay(), d.playX(), d.playY(), d.playDelayMs());
	}

	/** The defaults with the username NOT remembered: click the field, type both. */
	private static Settings notRemembered(Settings d, boolean tab)
	{
		return new Settings(d.loginState(), d.settleMs(), d.keyDelayMs(), d.resultTimeoutMs(), d.maxAttempts(),
				d.maxRejects(), false, d.clearBackspaces(), tab, d.clickExistingUser(),
				d.existingX(), d.existingY(), d.usernameX(), d.usernameY(), d.passwordX(), d.passwordY(),
				d.loginX(), d.loginY(), d.tryAgainX(), d.tryAgainY(), d.afterClickMs(), d.reloginAfterDisconnect(),
				d.clickPlay(), d.playX(), d.playY(), d.playDelayMs());
	}

	/** Seen live 2026-09-06: state 30 shows a welcome screen; the world loads only after "CLICK HERE TO PLAY". */
	@Test
	public void clickHereToPlayIsClickedOnceAfterTheDelayAndNotForAManualLogin()
	{
		assertTrue(settings.clickPlay());
		armAtTitle();
		typeWholeScript();
		frame(500, 20);
		assertEquals(Phase.LOGGED_IN, frame(500, 30));
		int loggedIn = sink.events.size();
		assertTrue("the target is drawn while the click is owed", seq.playPending());
		// Not before the delay: the welcome screen needs a moment to draw.
		runUntil(30, settings.playDelayMs() - 200, () -> false);
		assertEquals("nothing posted before playDelayMs", loggedIn, sink.events.size());
		assertEquals("logged in", seq.status());
		// Then exactly one click at (centre + playX, top + playY), never repeated.
		runUntil(30, 1000, () -> sink.events.size() > loggedIn + 2);
		List<String> play = new ArrayList<>();
		addClick(play, W / 2 - 3, 334);
		assertEquals(play, sink.events.subList(loggedIn, sink.events.size()));
		assertFalse(seq.playPending());
		assertEquals("logged in -- clicked play (the configured offset)", seq.status());
		assertTrue(everything.toString().contains("clicked play"));
		int after = sink.events.size();
		for (int i = 0; i < 1000; i++) frame(33, 30);
		assertEquals("the play click is never repeated", after, sink.events.size());
		assertEquals(0, seq.queued());
		// A logout, then a login by hand noticed from IDLE: the human is at the keyboard, no click.
		assertEquals(Phase.IDLE, frame(33, LOGIN));
		assertEquals(Phase.LOGGED_IN, frame(33, 30));
		assertFalse(seq.playPending());
		for (int i = 0; i < 200; i++) frame(33, 30);
		assertEquals("no play click after a manual login", after, sink.events.size());
		assertEquals("logged in", seq.status());
	}

	/**
	 * The widget path: with a resolver wired in (the plugin wires {@link PlayButton}), the click goes
	 * to the point the resolver returns -- the centre of the component that contains the measured one
	 * -- and both the log and the status line name it. The measured offset is what the resolver is
	 * SEEDED with, which is asserted here because that seeding is the whole mechanism.
	 */
	@Test
	public void aResolverMovesThePlayClickToTheWidgetCentreAndSaysSo()
	{
		final int[] seed = new int[2];
		seq.playTarget((cw, ch, ox, oy) -> {
			seed[0] = ox;
			seed[1] = oy;
			return new LoginSequence.PlayTarget.Resolution(500, 600, "widget 549:12 500,590 40x20 shown");
		});
		armAtTitle();
		typeWholeScript();
		frame(500, 20);
		assertEquals(Phase.LOGGED_IN, frame(500, 30));
		int loggedIn = sink.events.size();
		runUntil(30, settings.playDelayMs() + 1000, () -> sink.events.size() > loggedIn + 2);
		List<String> play = new ArrayList<>();
		addClick(play, 500, 600);
		assertEquals(play, sink.events.subList(loggedIn, sink.events.size()));
		assertEquals("the resolver is seeded with the measured offset", W / 2 - 3, seed[0]);
		assertEquals(334, seed[1]);
		assertEquals("logged in -- clicked play (widget 549:12 500,590 40x20 shown)", seq.status());
		assertTrue(everything.toString().contains("clicked play (CLICK HERE TO PLAY) at 500,600"));
	}

	/** A resolver that throws (an old DLL with no widget natives) costs the click nothing. */
	@Test
	public void aThrowingResolverFallsBackToTheMeasuredOffset()
	{
		seq.playTarget((cw, ch, ox, oy) -> { throw new UnsatisfiedLinkError("dumpWidgetText"); });
		armAtTitle();
		typeWholeScript();
		frame(500, 20);
		assertEquals(Phase.LOGGED_IN, frame(500, 30));
		int loggedIn = sink.events.size();
		runUntil(30, settings.playDelayMs() + 1000, () -> sink.events.size() > loggedIn + 2);
		List<String> play = new ArrayList<>();
		addClick(play, W / 2 - 3, 334);
		assertEquals(play, sink.events.subList(loggedIn, sink.events.size()));
		assertTrue(seq.status().startsWith("logged in -- clicked play (the configured offset"));
		assertTrue(seq.playHow().contains("UnsatisfiedLinkError"));
	}

	@Test
	public void clickPlayOffPostsNothingAfterLoggedIn()
	{
		settings = withoutPlayClick(settings);
		seq = new LoginSequence(sink, settings);
		armAtTitle();
		typeWholeScript();
		frame(500, 20);
		assertEquals(Phase.LOGGED_IN, frame(500, 30));
		int loggedIn = sink.events.size();
		assertFalse(seq.playPending());
		runUntil(30, settings.playDelayMs() + 5000, () -> false);
		assertEquals("clickPlay off: nothing after LOGGED_IN", loggedIn, sink.events.size());
		assertEquals("logged in", seq.status());
		assertFalse(everything.toString().contains("clicked play"));
	}

	@Test
	public void loggedInThenLoginScreenAgainIsIdleByDefaultUntilLoginNow()
	{
		assertFalse("the default must not re-type a password on a logout", settings.reloginAfterDisconnect());
		armAtTitle();
		typeWholeScript();
		assertEquals(Phase.LOGGED_IN, frame(100, 30));
		for (int i = 0; i < 100; i++) frame(33, 30);
		int typed = sink.events.size();
		// A logout -- deliberate or a disconnect, the machine cannot tell -- parks it.
		assertEquals(Phase.IDLE, frame(33, LOGIN));
		for (int i = 0; i < 1000; i++) frame(33, LOGIN);
		assertEquals(Phase.IDLE, seq.phase());
		assertFalse(seq.wantsScript());
		assertEquals("nothing typed while idle", typed, sink.events.size());
		assertTrue(seq.status().contains("idle"));
		// Logging in by hand while idle is noticed...
		assertEquals(Phase.LOGGED_IN, frame(33, 30));
		assertEquals(Phase.IDLE, frame(33, LOGIN));
		// ...and the hotkey / panel toggle is what starts a new login.
		seq.loginNow();
		assertEquals(Phase.WAITING_FOR_LOGIN_SCREEN, seq.phase());
		runUntil(LOGIN, LoginSequence.QUICK_SETTLE_MS + 200, () -> seq.wantsScript());
		assertTrue("login now from idle settles quickly and asks for a script", seq.wantsScript());
	}

	@Test
	public void loggedInThenLoginScreenAgainReArmsWithCountersReset()
	{
		settings = withRelogin(settings);
		seq = new LoginSequence(sink, settings);
		armAtTitle();
		typeWholeScript();
		waitOutTheResultTimeout();
		assertEquals(1, seq.attempts());
		assertEquals(1, seq.rejects());
		// Logged in after all (a slow server, or by hand), then a disconnect: back to the title.
		assertEquals(Phase.LOGGED_IN, frame(100, 30));
		for (int i = 0; i < 100; i++) frame(33, 30);
		Phase p = frame(33, LOGIN);
		assertEquals(Phase.SETTLING, p);
		assertEquals(0, seq.attempts());
		assertEquals(0, seq.rejects());
		runUntil(LOGIN, settings.settleMs() + 1000, () -> seq.wantsScript());
		assertTrue("a relogin is offered after a disconnect", seq.wantsScript());
	}

	@Test
	public void nothingTheSequenceSaysContainsThePassword()
	{
		// Every path that produces text, in one run: a bounce and its back-off, a Try-again rejection
		// that is really dismissed, a re-arm, and the give-up. maxRejects 3 so the second rejection
		// goes through DISMISSING_REJECTION for real rather than stopping at it.
		settings = withMaxRejects(settings, 3);
		seq = new LoginSequence(sink, settings);

		armAtTitle();
		typeWholeScript();
		long scriptMs = sink.times.get(sink.times.size() - 1) - sink.times.get(0);

		frame(500, 20);
		frame(2000, LOGIN);                                     // the server bounces us: rejection 1
		runUntil(LOGIN, 120_000, () -> seq.wantsScript());
		seq.arm(USER, PASS, W, H);
		typeWholeScript();
		assertEquals(Phase.DISMISSING_REJECTION, waitOutTheResultTimeout());     // rejection 2
		runUntil(LOGIN, 5000, () -> seq.phase() != Phase.DISMISSING_REJECTION);
		runUntil(LOGIN, settings.settleMs() + 1000, () -> seq.wantsScript());
		seq.arm(USER, PASS, W, H);
		typeWholeScript();
		assertEquals(Phase.GAVE_UP, waitOutTheResultTimeout());                  // rejection 3: stop

		// Nothing of the password is left queued, and a stray arm() cannot put it back. Review
		// 2026-09-06: this test used to END on an arm() in GAVE_UP, which built the whole script in a
		// phase that never drains it -- the characters then sat in the queue for the rest of the
		// session and no test noticed.
		assertEquals("nothing of the password lingers after the give-up", 0, seq.queued());
		seq.arm(USER, PASS, W, H);
		assertEquals("an arm() nobody asked for queues nothing", 0, seq.queued());
		frame(33, LOGIN);
		assertTrue(everything.toString().contains("arm() ignored"));

		everything.append(seq.status()).append(seq.toString());
		String all = everything.toString();
		assertFalse(all.contains(PASS));
		assertFalse(all.contains("secret"));
		assertFalse("the username is not printed either", all.contains(USER));
		assertFalse("nor its local part", all.contains("someone"));
		assertFalse("no character count either (a length is a fact about the password)",
				all.contains("" + PASS.length() + " keys") || all.contains("" + PASS.length() + " chars"));

		// Nor a length by arithmetic. The script is one paced emission slot per character and two per
		// key/click, so a duration anywhere in the output is the character count with the sums
		// already done: (t / keyDelayMs) - 2 * controlSteps, the pauses being constants of the
		// settings. No number the sequence prints may be within a keystroke of what typing took.
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(all);
		while (m.find())
		{
			if (m.group().length() > 9) continue;               // not a duration; do not overflow parsing it
			long n = Long.parseLong(m.group());
			assertTrue("printed " + n + ", within a keystroke of how long the script took: the password "
					+ "length follows from it", Math.abs(n - scriptMs) > 2 * settings.keyDelayMs());
		}
	}

	@Test
	public void scriptIsClearedAfterEmissionAndOnAbort()
	{
		armAtTitle();
		assertTrue(seq.queued() > 0);
		typeWholeScript();
		assertEquals("nothing of the password lingers once typed", 0, seq.queued());

		// A second attempt (after Try again) aborted half way: the queue is dropped, not kept for later.
		waitOutTheResultTimeout();
		runUntil(LOGIN, 120_000, () -> seq.wantsScript());
		seq.arm(USER, PASS, W, H);
		for (int i = 0; i < 5; i++) frame(33, LOGIN);
		assertTrue(seq.queued() > 0);
		seq.abort();
		assertEquals(0, seq.queued());
		assertEquals(Phase.WAITING_FOR_LOGIN_SCREEN, seq.phase());
	}

	@Test
	public void noCredentialsTypesNothingAndReChecksLater()
	{
		frame(0, 0);
		frame(33, LOGIN);
		runUntil(LOGIN, settings.settleMs() + 1000, () -> seq.wantsScript());
		seq.noCredentials("password empty in autologin.properties");
		assertEquals(Phase.NO_CREDENTIALS, seq.phase());
		assertEquals("password empty in autologin.properties", seq.status());
		assertFalse(seq.wantsScript());
		runUntil(LOGIN, LoginSequence.NO_CREDENTIALS_RETRY_MS + 1000, () -> seq.wantsScript());
		assertTrue("the file is re-read after the retry interval", seq.wantsScript());
		assertTrue(sink.events.isEmpty());
		// Filled in now: arming from NO_CREDENTIALS starts typing.
		seq.arm(USER, PASS, W, H);
		typeWholeScript();
		assertFalse(sink.events.isEmpty());
	}

	@Test
	public void loginNowSettlesQuicklyAndResetsATerminalPhase()
	{
		seq.loginNow();
		frame(0, LOGIN);
		Phase p = runUntil(LOGIN, LoginSequence.QUICK_SETTLE_MS + 200, () -> seq.wantsScript());
		assertTrue("500 ms settle instead of " + settings.settleMs(), seq.wantsScript());
		seq.arm(USER, PASS, W, H);
		typeWholeScript();
		frame(500, 20);
		frame(500, 11);
		assertEquals(Phase.STOPPED_AUTHENTICATOR, seq.phase());
		seq.loginNow();
		assertEquals(Phase.WAITING_FOR_LOGIN_SCREEN, seq.phase());
		assertEquals(0, seq.attempts());
	}

	@Test
	public void notRememberedPathClicksTheUsernameFieldTypesBothAndClicksThePasswordField()
	{
		settings = notRemembered(settings, false);
		seq = new LoginSequence(sink, settings);
		armAtTitle();
		typeWholeScript();

		List<String> expected = new ArrayList<>();
		addClick(expected, W / 2 + 69, 288);                          // Existing User
		addClick(expected, W / 2 - 82, 234);                          // the username field
		for (int i = 0; i < settings.clearBackspaces(); i++) addKey(expected, 8);
		for (int i = 0; i < USER.length(); i++) expected.add("char");
		addClick(expected, W / 2 - 82, 257);                          // the password field
		for (int i = 0; i < PASS.length(); i++) expected.add("char");
		addKey(expected, 13);
		addClick(expected, W / 2 - 93, 315);                          // Login
		assertEquals(expected, sink.events);
		assertEquals(USER.length(), seq.usernameChars());
		assertEquals(PASS.length(), seq.passwordChars());
		assertFalse("no Tab when clicking the password field", sink.events.contains("key 9 down"));
	}

	@Test
	public void notRememberedWithTabTabsInsteadOfClickingThePasswordField()
	{
		settings = notRemembered(settings, true);
		seq = new LoginSequence(sink, settings);
		armAtTitle();
		typeWholeScript();
		List<String> e = sink.events;
		int afterUser = 3 + 3 + 2 * settings.clearBackspaces() + USER.length();
		assertEquals("key 9 down", e.get(afterUser));
		assertEquals("key 9 up", e.get(afterUser + 1));
		assertFalse("no click into the password field", e.contains("mouse 1 " + (W / 2 - 82) + ",257"));
		assertEquals("the Login click still ends the script", "mouse 2 " + (W / 2 - 93) + ",315", e.get(e.size() - 1));
	}

	// -----------------------------------------------------------------------------------------------
	// Which screen is up. THE reported bug: a disconnect puts up a different screen than a cold start
	// and the old script clicked Existing User into it, typed into whatever had focus, and submitted
	// an empty password field twice (live 2026-09-06, "rejected 2 times").
	// -----------------------------------------------------------------------------------------------

	/** Logged in, then dropped back to the title screen: the form is up, not the welcome box. */
	private void disconnect()
	{
		settings = withRelogin(settings);
		seq = new LoginSequence(sink, settings);
		frame(0, 0);
		frame(33, LOGIN);
		runUntil(LOGIN, settings.settleMs() + 1000, () -> seq.wantsScript());
		seq.arm(USER, PASS, W, H);
		typeWholeScript();
		frame(500, 20);
		assertEquals(Phase.LOGGED_IN, frame(500, 30));
		for (int i = 0; i < 50; i++) frame(33, 30);
		assertEquals("30 -> 10 with the relogin opted in re-arms", Phase.SETTLING, frame(33, LOGIN));
	}

	@Test
	public void aDisconnectDoesNotClickExistingUserAndFocusesThePasswordFieldItself()
	{
		disconnect();
		assertEquals("reached the title screen from the world: that is the disconnect screen",
				LoginScreen.DISCONNECT, seq.screen());
		runUntil(LOGIN, settings.settleMs() + 1000, () -> seq.wantsScript());
		int before = sink.events.size();
		seq.arm(USER, PASS, W, H);
		typeWholeScript();

		List<String> expected = new ArrayList<>();
		addClick(expected, W / 2 - 82, 257);                          // the password field, NOT (69, 288)
		for (int i = 0; i < settings.clearBackspaces(); i++) addKey(expected, 8);
		for (int i = 0; i < PASS.length(); i++) expected.add("char");
		addKey(expected, 13);
		addClick(expected, W / 2 - 93, 315);                          // Login
		assertEquals(expected, sink.events.subList(before, sink.events.size()));
		assertFalse("the Existing User spot is not a button on this screen",
				sink.events.subList(before, sink.events.size()).contains("mouse 1 " + (W / 2 + 69) + ",288"));
		assertEquals(LoginScreen.DISCONNECT, seq.armedScreen());
	}

	/** The cold start is the one path that was working; it has to come out byte for byte the same. */
	@Test
	public void aColdStartIsStillTheWelcomeScreenAndIsUnchanged()
	{
		armAtTitle();
		assertEquals(LoginScreen.WELCOME, seq.screen());
		typeWholeScript();
		List<String> expected = new ArrayList<>();
		addClick(expected, W / 2 + 69, 288);
		for (int i = 0; i < settings.clearBackspaces(); i++) addKey(expected, 8);
		for (int i = 0; i < PASS.length(); i++) expected.add("char");
		addKey(expected, 13);
		addClick(expected, W / 2 - 93, 315);
		assertEquals(expected, sink.events);
		assertFalse("no password-field click on the welcome path: its own click hands the caret over",
				sink.events.contains("mouse 1 " + (W / 2 - 82) + ",257"));
	}

	/**
	 * A known fingerprint outranks how we got here. Nothing is in {@link LoginScreen#KNOWN} yet, so
	 * this drives the mechanism through a book of its own -- the shape the next live run fills in.
	 */
	@Test
	public void aKnownFingerprintOverridesHowWeGotHere()
	{
		disconnect();
		assertEquals(LoginScreen.DISCONNECT, seq.provenance());
		seq.setScreenBook(java.util.Map.of("11,22,33", LoginScreen.WELCOME));
		seq.noteScreen(new int[] { 33, 11, 22 }, true);
		assertTrue(seq.screenFromFingerprint());
		assertEquals("the client said welcome box; that beats the state history",
				LoginScreen.WELCOME, seq.screen());

		runUntil(LOGIN, settings.settleMs() + 1000, () -> seq.wantsScript());
		int before = sink.events.size();
		seq.arm(USER, PASS, W, H);
		typeWholeScript();
		assertEquals("mouse 0 " + (W / 2 + 69) + ",288", sink.events.get(before));
	}

	@Test
	public void anUnknownFingerprintFallsBackToHowWeGotHere()
	{
		disconnect();
		seq.noteScreen(new int[] { 4, 5, 6 }, true);                  // not in the empty shipped book
		assertFalse(seq.screenFromFingerprint());
		assertEquals(LoginScreen.DISCONNECT, seq.screen());
		frame(33, LOGIN);                                             // drains the log into `everything`
		assertTrue("the ids are logged so the next run can say which screen they were",
				everything.toString().contains("login screen fingerprint [4,5,6]"));
		assertTrue(everything.toString().contains("not in LoginScreen.KNOWN"));
	}

	/** One line per DISTINCT screen, not one per frame: the log is evidence, not a stream. */
	@Test
	public void eachFingerprintIsLoggedOnceAndOnlyWhenAskedFor()
	{
		frame(0, 0);
		frame(33, LOGIN);
		for (int i = 0; i < 20; i++) { seq.noteScreen(new int[] { 7, 8 }, true); frame(33, LOGIN); }
		assertEquals(1, countOf(everything.toString(), "login screen fingerprint [7,8]"));
		seq.noteScreen(new int[] { 9 }, true);
		frame(33, LOGIN);
		assertEquals(1, countOf(everything.toString(), "login screen fingerprint [9]"));
		// Quiet without KEWL_LOG: the screen is still read, it is just not narrated.
		seq.noteScreen(new int[] { 1, 2 }, false);
		frame(33, LOGIN);
		assertFalse(everything.toString().contains("[1,2]"));
	}

	private static int countOf(String haystack, String needle)
	{
		int n = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
		return n;
	}

	/**
	 * The opt-in direct write: the sequence types nothing at all, and no character of either value is
	 * ever queued. It still clicks its way to the form and submits.
	 */
	@Test
	public void fieldsSetDirectlyMeansNothingIsTypedAndNothingIsQueued()
	{
		frame(0, 0);
		frame(33, LOGIN);
		runUntil(LOGIN, settings.settleMs() + 1000, () -> seq.wantsScript());
		seq.arm(USER, PASS, W, H, true);
		assertTrue(seq.fieldsSetDirectly());
		assertFalse("no character of the username is queued", seq.usernameTyped());
		assertFalse("nor of the password", seq.passwordTyped());
		assertEquals(0, seq.usernameChars());
		assertEquals(0, seq.passwordChars());
		assertTrue(seq.status(), seq.status().startsWith("fields set directly"));
		typeWholeScript();

		List<String> expected = new ArrayList<>();
		addClick(expected, W / 2 + 69, 288);                          // Existing User: the form must be up
		addClick(expected, W / 2 - 93, 315);                          // Login
		assertEquals(expected, sink.events);
		assertEquals("nothing lingers", 0, seq.queued());
		String all = everything.toString();
		assertFalse(all.contains(PASS));
		assertFalse(all.contains(USER));
	}

	/** On the disconnect screen the direct write needs no clicks but the submit. */
	@Test
	public void fieldsSetDirectlyOnTheDisconnectScreenIsOneClick()
	{
		disconnect();
		runUntil(LOGIN, settings.settleMs() + 1000, () -> seq.wantsScript());
		int before = sink.events.size();
		seq.arm(USER, PASS, W, H, true);
		typeWholeScript();
		List<String> expected = new ArrayList<>();
		addClick(expected, W / 2 - 93, 315);
		assertEquals(expected, sink.events.subList(before, sink.events.size()));
	}

	/** Everything the screen machinery adds to the output, held against the same rule as the rest. */
	@Test
	public void neitherTheScreenLinesNorTheFingerprintsContainACredential()
	{
		disconnect();
		seq.noteScreen(new int[] { 12, 34, 56 }, true);
		runUntil(LOGIN, settings.settleMs() + 1000, () -> seq.wantsScript());
		seq.arm(USER, PASS, W, H);
		typeWholeScript();
		seq.noteScreen(new int[] { 78 }, true);
		frame(33, LOGIN);
		everything.append(seq.status()).append('\n').append(seq.armedScreen()).append('\n')
				.append(seq.screen()).append('\n').append(seq.provenance()).append('\n');
		String all = everything.toString();
		assertFalse(all, all.contains(PASS));
		assertFalse(all, all.contains("secret"));
		assertFalse(all, all.contains(USER));
		assertFalse(all, all.contains("someone"));
	}
}

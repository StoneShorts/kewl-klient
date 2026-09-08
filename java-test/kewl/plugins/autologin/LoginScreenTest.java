package kewl.plugins.autologin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

/**
 * The fingerprint and the two things a screen decides. Pure: no natives, no game, no clock.
 *
 * <p>The point of these is that the mechanism works before anybody knows the real group ids: the
 * fingerprints here are invented, and they have to be -- {@link LoginScreen#KNOWN} ships empty on
 * purpose, because a guessed id would make the cold start that works today skip its Existing User
 * click. What is tested is the machinery the next live run plugs numbers into.</p>
 */
public class LoginScreenTest
{
	@Test
	public void onlyTheWelcomeBoxHasAnExistingUserButton()
	{
		// The reported bug in one assertion: the disconnect screen must not be clicked at (69, 288).
		assertTrue(LoginScreen.WELCOME.clicksExistingUser());
		assertFalse(LoginScreen.DISCONNECT.clicksExistingUser());
		assertFalse(LoginScreen.FORM.clicksExistingUser());
		assertFalse(LoginScreen.REJECTION.clicksExistingUser());
		assertFalse(LoginScreen.UNKNOWN.clicksExistingUser());
	}

	@Test
	public void everyScreenThatWasAlreadyUpNeedsThePasswordFieldFocused()
	{
		// The welcome box hands the caret over itself when its click opens the form; on a screen that
		// was already showing when we arrived nothing has said where the focus is.
		assertFalse(LoginScreen.WELCOME.focusesPasswordField());
		assertTrue(LoginScreen.DISCONNECT.focusesPasswordField());
		assertTrue(LoginScreen.FORM.focusesPasswordField());
		assertTrue(LoginScreen.REJECTION.focusesPasswordField());
	}

	@Test
	public void theFingerprintIsSortedDedupedAndOrderIndependent()
	{
		assertEquals("12,378,553", LoginScreen.fingerprint(new int[] { 553, 12, 378 }));
		assertEquals("12,378,553", LoginScreen.fingerprint(new int[] { 12, 378, 553, 378, 12 }));
		assertEquals("378", LoginScreen.fingerprint(new int[] { 378 }));
	}

	/** "Nothing loaded" is itself a screen's signature, not a missing reading. */
	@Test
	public void noGroupsIsItsOwnFingerprintAndNotAnError()
	{
		assertEquals("", LoginScreen.fingerprint(null));
		assertEquals("", LoginScreen.fingerprint(new int[0]));
		assertEquals("nothing loaded is looked up like any other fingerprint",
				LoginScreen.WELCOME, LoginScreen.ofFingerprint("", Map.of("", LoginScreen.WELCOME)));
	}

	/** In-game the client reports hundreds of groups; neither the log nor the map wants that list. */
	@Test
	public void aLoadedGameIsSummarisedRatherThanListed()
	{
		int[] many = new int[LoginScreen.MAX_GROUPS + 5];
		for (int i = 0; i < many.length; i++) many[i] = 1000 + i;
		assertEquals("many:" + many.length, LoginScreen.fingerprint(many));
	}

	@Test
	public void anUnknownFingerprintIsUnknownAndNeverAGuess()
	{
		Map<String, LoginScreen> book = Map.of("1,2,3", LoginScreen.WELCOME, "4,5", LoginScreen.DISCONNECT);
		assertEquals(LoginScreen.WELCOME, LoginScreen.ofFingerprint("1,2,3", book));
		assertEquals(LoginScreen.DISCONNECT, LoginScreen.ofFingerprint("4,5", book));
		assertEquals(LoginScreen.UNKNOWN, LoginScreen.ofFingerprint("9,9,9", book));
		assertEquals(LoginScreen.UNKNOWN, LoginScreen.ofFingerprint(null, book));
		assertEquals(LoginScreen.UNKNOWN, LoginScreen.ofFingerprint("1,2,3", null));
	}

	/**
	 * The shipped table is empty, and that is a decision rather than an omission: until a live run
	 * says which ids mean which screen, every fingerprint falls back to how the sequence got there,
	 * which keeps the cold start behaving exactly as it does now.
	 */
	@Test
	public void theShippedTableIsEmptyUntilALiveRunFillsItIn()
	{
		assertTrue("a guessed group id would break the cold start that works today",
				LoginScreen.KNOWN.isEmpty());
	}
}

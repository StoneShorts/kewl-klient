package kewl.plugins;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import kewl.plugins.autologin.LoginSequence;

/**
 * The two copies of the click targets have to agree.
 *
 * <p>{@code LoginSequence.Settings.defaults()} is the 2026-09-06 live measurements written out for
 * the tests; {@code AutoLogin}'s constructor declares the same numbers as panel settings, and
 * {@code settingsFromConfig()} turns those into the record the sequence actually runs on. Review
 * 2026-09-06: nothing checked that they matched, so the next re-measurement -- "the Login button
 * moved to y = 320" -- could be typed into AutoLogin alone and the whole eighteen-case
 * LoginSequenceTest would keep happily asserting clicks at the old offsets, green, and testing
 * nothing the plugin does.</p>
 *
 * <p>This is a plain unit test on purpose: constructing an {@link AutoLogin} only declares settings
 * (no natives, no JVM bridge, no game), and reading them back is the same call the plugin makes
 * every tick.</p>
 */
public class AutoLoginDefaultsTest
{
	@Test
	public void theSettingsThePanelDeclaresAreTheDefaultsTheTestsAssert()
	{
		// A record, so one equals() covers all twenty-six fields -- including any added later.
		assertEquals("LoginSequence.Settings.defaults() and AutoLogin's declared defaults have drifted "
				+ "apart: whichever one was re-measured, put the same numbers in the other",
				LoginSequence.Settings.defaults(), new AutoLogin().settingsFromConfig());
	}
}

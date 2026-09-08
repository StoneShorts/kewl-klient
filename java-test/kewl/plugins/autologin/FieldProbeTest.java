package kewl.plugins.autologin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

/**
 * The one line of {@link FieldProbe} that could ever have carried a secret.
 *
 * <p>The probe walks the slots either side of a username hit looking for the password field, and an
 * inline {@code NxtString} stores {@code 0x17 - length} in its flag byte at {@code +0x17}. Printing
 * that byte, or the length decoded from it, publishes the length of the field the probe exists to
 * find -- which is the password. It did exactly that until review 2026-09-06
 * ({@code "neighbour +24: flag=0d inline, length 10"}). {@link FieldProbe#neighbour} now answers with
 * a verdict, and these tests hold every possible flag byte against every plausible length to prove
 * the answer discloses neither.</p>
 */
public class FieldProbeTest
{
	/** Every flag byte, against every length a password might be: only three sentences ever come out. */
	@Test
	public void theVerdictIsOneOfThreeSentencesAndNeverAValue()
	{
		Set<String> seen = new LinkedHashSet<>();
		for (int flag = 0; flag <= 0xFF; flag++)
			for (int len = 1; len <= 64; len++)
				seen.add(FieldProbe.neighbour(flag, len));
		assertEquals("a fourth phrasing would be a fourth thing the log can say: " + seen, 3, seen.size());
		for (String s : seen)
			for (int i = 0; i < s.length(); i++)
				assertFalse("no digit may reach the log from a length: " + s,
						Character.isDigit(s.charAt(i)));
	}

	/**
	 * The bit the reader is actually given. It is a yes/no about a length they already hold, not the
	 * length -- the same classify-never-dump rule {@link FieldWriter#gapIsText} follows for bytes.
	 */
	@Test
	public void aMatchIsReportedAsAMatchAndNothingMore()
	{
		// A ten-character field: an inline NxtString would carry 0x17 - 10 = 0x0d.
		assertTrue(FieldProbe.neighbour(0x17 - 10, 10).contains("this is the password field"));
		assertEquals("inline, but a different length", FieldProbe.neighbour(0x17 - 11, 10));
		assertEquals("inline, but a different length", FieldProbe.neighbour(0x17 - 9, 10));
	}

	/** A flag byte above 0x17 is not an inline string at all -- a heap pointer, or unrelated data. */
	@Test
	public void anythingThatIsNotAnInlineStringSaysSo()
	{
		assertEquals("(heap or not a string)", FieldProbe.neighbour(0x18, 10));
		assertEquals("(heap or not a string)", FieldProbe.neighbour(0xFF, 10));
	}

	/**
	 * With no password to compare against, nothing may be claimed. The probe runs with whatever the
	 * plugin resolved, and an empty password must not turn every empty inline slot -- flag 0x17 -- into
	 * "this is the password field".
	 */
	@Test
	public void noPasswordToMatchMeansNoMatchIsEverClaimed()
	{
		for (int flag = 0; flag <= 0x17; flag++)
			assertEquals("inline, but a different length", FieldProbe.neighbour(flag, 0));
	}
}

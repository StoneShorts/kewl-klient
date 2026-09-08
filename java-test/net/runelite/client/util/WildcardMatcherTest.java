// WildcardMatcher is the whole of "NPCs to highlight" matching: whole-string, case-insensitive, `*`
// any run, `?` one character, everything else literal (regex metacharacters included).
package net.runelite.client.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class WildcardMatcherTest
{
	@Test
	public void caseInsensitiveWholeString()
	{
		assertTrue(WildcardMatcher.matches("cow", "Cow"));
		assertTrue(WildcardMatcher.matches("COW", "cow"));
		assertFalse("whole string, not substring", WildcardMatcher.matches("cow", "Cow calf"));
	}

	@Test
	public void starMatchesAnyRun()
	{
		assertTrue(WildcardMatcher.matches("cow*", "Cow calf"));
		assertTrue("empty run", WildcardMatcher.matches("cow*", "Cow"));
		assertTrue(WildcardMatcher.matches("*guard*", "Tower guard"));
	}

	@Test
	public void questionMarkMatchesAtMostOneCharacter()
	{
		// Upstream's semantic: `?` -> ".?", so "Ban?ker" takes "Banker" (the shape a user types).
		assertTrue(WildcardMatcher.matches("?oblin", "Goblin"));
		assertTrue(WildcardMatcher.matches("?oblin", "oblin"));
		assertTrue(WildcardMatcher.matches("Ban?ker", "Banker"));
		assertFalse(WildcardMatcher.matches("?oblin", "Hobgoblin"));
	}

	@Test
	public void regexMetacharactersAreLiteral()
	{
		assertTrue(WildcardMatcher.matches("Man (guard)", "Man (guard)"));
		assertFalse(WildcardMatcher.matches("Man (guard)", "Man guard"));
		assertTrue(WildcardMatcher.matches("a.b", "a.b"));
		assertFalse("a dot is a dot", WildcardMatcher.matches("a.b", "axb"));
	}

	@Test
	public void compileOnceMatchesMany()
	{
		java.util.regex.Pattern p = WildcardMatcher.compile("*cow*");
		assertTrue(p.matcher("Cow").matches());
		assertTrue(p.matcher("Cow calf").matches());
		assertFalse(p.matcher("Chicken").matches());
	}

	@Test
	public void nullIsNoMatch()
	{
		assertFalse(WildcardMatcher.matches(null, "Cow"));
		assertFalse(WildcardMatcher.matches("Cow", null));
	}

	@Test
	public void textHelpersUsedByTheLists()
	{
		assertEquals(List.of("Goblin", "Cow*", "1234"), Text.fromCSV(" Goblin, Cow*,, 1234 , "));
		assertTrue(Text.fromCSV("").isEmpty());
		assertTrue(Text.fromCSV(null).isEmpty());
		assertEquals("nbsp folded and trimmed", "Big Bob", Text.sanitize("Big\u00A0Bob\u00A0"));
		assertEquals("figure space folded", "Big Bob", Text.sanitize("Big\u2007Bob"));
		assertEquals("Big Bob", Text.sanitize("<col=ff0000>Big Bob</col>"));
		assertEquals("", Text.sanitize(null));
	}
}

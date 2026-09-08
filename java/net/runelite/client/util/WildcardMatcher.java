// Hand-written shim of net.runelite.client.util.WildcardMatcher (BSD-2, RuneLite): whole-string,
// case-insensitive glob matching for the "NPCs to highlight" style lists ported plugins keep.
//
// Semantics (upstream's: `?` -> ".?", `*` -> ".*"): `*` matches any run of characters (including
// none), `?` matches at most one ("Ban?ker" takes both "Banker" and "Bankxer"), and every other
// character is literal -- a "(" in "Man (guard)" is a bracket, not a regex group. Plugins compile a
// pattern once per config change ({@link #compile}) rather than per NPC per frame.
package net.runelite.client.util;

import java.util.regex.Pattern;

public final class WildcardMatcher
{
	private WildcardMatcher()
	{
	}

	/** One-shot convenience: compiles and matches. Prefer {@link #compile} in a per-frame loop. */
	public static boolean matches(String pattern, String text)
	{
		if (pattern == null || text == null)
		{
			return false;
		}
		return compile(pattern).matcher(text).matches();
	}

	/** The pattern as a whole-string, case-insensitive regex; every non-wildcard run is quoted. */
	public static Pattern compile(String pattern)
	{
		StringBuilder regex = new StringBuilder(pattern.length() + 16);
		StringBuilder literal = new StringBuilder();
		for (int i = 0; i < pattern.length(); i++)
		{
			char c = pattern.charAt(i);
			if (c == '*' || c == '?')
			{
				if (literal.length() > 0)
				{
					regex.append(Pattern.quote(literal.toString()));
					literal.setLength(0);
				}
				regex.append(c == '*' ? ".*" : ".?");
			}
			else
			{
				literal.append(c);
			}
		}
		if (literal.length() > 0)
		{
			regex.append(Pattern.quote(literal.toString()));
		}
		return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	}
}

// Shim of net.runelite.client.util.Text (BSD-2, RuneLite) -- the used surface only: the plugin strips
// colour/col tags out of widget text and counts characters for its fairy-ring parsing.
package net.runelite.client.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class Text
{
	private static final int[] FOOTER = {'<', '>'};

	public static String removeTags(String str)
	{
		if (str == null)
		{
			return "";
		}
		StringBuilder out = new StringBuilder(str.length());
		for (int i = 0; i < str.length(); i++)
		{
			char c = str.charAt(i);
			if (c == '<')
			{
				int close = str.indexOf('>', i);
				if (close == -1)
				{
					return out.toString();
				}
				i = close;
				continue;
			}
			out.append(c);
		}
		return out.toString();
	}

	public static String standardize(String str)
	{
		if (str == null)
		{
			return "";
		}
		return removeTags(str).replaceAll("[  ]", " ").trim();
	}

	public static boolean contains(Collection<String> haystack, String needle)
	{
		return haystack.stream()
			.map(Text::standardize)
			.collect(Collectors.toList())
			.contains(standardize(needle));
	}

	public static int length(String str)
	{
		return removeTags(str).length();
	}

	/**
	 * Upstream semantics (Guava's {@code Splitter.on(',').omitEmptyStrings().trimResults()}): the
	 * "NPCs to highlight" box is one of these. Never null; "" and null give an empty list.
	 */
	public static List<String> fromCSV(String input)
	{
		List<String> out = new ArrayList<>();
		if (input == null)
		{
			return out;
		}
		for (String part : input.split(","))
		{
			String t = part.trim();
			if (!t.isEmpty())
			{
				out.add(t);
			}
		}
		return out;
	}

	/** The inverse of {@link #fromCSV}: {@code "a, b, c"}. */
	public static String toCSV(Collection<String> input)
	{
		return String.join(", ", input);
	}

	/**
	 * Upstream shape: strip tags and fold the non-breaking spaces the client pads names with
	 * (U+00A0, and U+2007 for figure-space padding) to plain spaces, then trim. The local player's
	 * name is a heap NxtString that may carry U+00A0 (Player.rawName), so every name comparison
	 * goes through here first.
	 */
	public static String sanitize(String name)
	{
		if (name == null)
		{
			return "";
		}
		return removeTags(name).replace('\u00A0', ' ').replace('\u2007', ' ').trim();
	}
}

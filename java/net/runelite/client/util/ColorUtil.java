// Shim of net.runelite.client.util.ColorUtil (BSD-2, RuneLite) -- the used surface only.
package net.runelite.client.util;

import java.awt.Color;

public class ColorUtil
{
	public static String wrapWithColorTag(String text, Color color)
	{
		return "<col=" + colorToHexCode(color) + ">" + text + "</col>";
	}

	public static String colorToHexCode(Color color)
	{
		return String.format("%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
	}

	public static String toHexCode(int alpha, int red, int green, int blue)
	{
		return String.format("%02x%02x%02x%02x", alpha, red, green, blue);
	}
}

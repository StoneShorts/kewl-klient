// Shim of net.runelite.client.ui.FontManager (BSD-2, RuneLite).
//
// The real one loads Jagex's bitmap fonts. We do not have them, so every request gets a plain small
// sans-serif that reads fine over the game. If the plugin sizes text with it, that sizing holds.
package net.runelite.client.ui;

import java.awt.Font;

public class FontManager
{
	public static Font getRunescapeFont()
	{
		return new Font(Font.SANS_SERIF, Font.PLAIN, 16);
	}

	public static Font getRunescapeSmallFont()
	{
		return new Font(Font.SANS_SERIF, Font.PLAIN, 14);
	}

	public static Font getRunescapeBoldFont()
	{
		return new Font(Font.SANS_SERIF, Font.BOLD, 16);
	}
}

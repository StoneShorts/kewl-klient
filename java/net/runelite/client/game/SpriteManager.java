// Shim of net.runelite.client.game.SpriteManager (BSD-2, RuneLite).
//
// The real one reads sprites out of the game cache. The plugin wants the two minimap mask sprites,
// whose shape is known (a circle for fixed-mode, a rounded rectangle for resizable), so those are
// synthesised procedurally; anything else comes back null.
package net.runelite.client.game;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

public class SpriteManager
{
	private static final int FIXED_MAP_MASK = 1183;
	private static final int RESIZE_MAP_MASK = 1178;

	/** The only caller treats this as a plain image, so this returns BufferedImage. */
	public BufferedImage getSprite(int spriteId, int archiveId)
	{
		if (spriteId == FIXED_MAP_MASK)
		{
			return circleMask(250);
		}
		if (spriteId == RESIZE_MAP_MASK)
		{
			return roundedMask(252, 222);
		}
		return null;
	}

	private static BufferedImage circleMask(int size)
	{
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(Color.BLACK);
		g.fill(new Ellipse2D.Float(0, 0, size, size));
		g.dispose();
		return img;
	}

	private static BufferedImage roundedMask(int w, int h)
	{
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(Color.BLACK);
		g.fill(new RoundRectangle2D.Float(0, 0, w, h, 24, 24));
		g.dispose();
		return img;
	}
}

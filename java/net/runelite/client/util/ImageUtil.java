// Shim of net.runelite.client.util.ImageUtil (BSD-2, RuneLite) -- the used surface only.
package net.runelite.client.util;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;

public class ImageUtil
{
	public static BufferedImage loadImageResource(Class<?> clazz, String resource)
	{
		InputStream in = clazz.getResourceAsStream(resource);
		if (in == null)
		{
			return null;
		}
		try
		{
			return ImageIO.read(in);
		}
		catch (IOException e)
		{
			return null;
		}
	}
}

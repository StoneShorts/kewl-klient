// Shim of net.runelite.client.ui.overlay.worldmap.WorldMapPoint (BSD-2, RuneLite) -- the used
// surface: a world point, an image, and the setter chain the plugin uses on creation.
package net.runelite.client.ui.overlay.worldmap;

import java.awt.image.BufferedImage;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.coords.WorldPoint;

@Getter
@Setter
public class WorldMapPoint
{
	private WorldPoint worldPoint;
	private BufferedImage image;
	private WorldPoint target;
	private String name;
	private boolean jumpOnClick;

	public WorldMapPoint(WorldPoint worldPoint, BufferedImage image)
	{
		this.worldPoint = worldPoint;
		this.image = image;
	}
}

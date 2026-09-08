// Shim of net.runelite.client.ui.overlay.RenderableEntity (BSD-2, RuneLite).
package net.runelite.client.ui.overlay;

import java.awt.Dimension;
import java.awt.Graphics2D;

public interface RenderableEntity
{
	Dimension render(Graphics2D graphics);
}

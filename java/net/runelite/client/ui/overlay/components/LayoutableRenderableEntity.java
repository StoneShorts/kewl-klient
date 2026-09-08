// Shim of net.runelite.client.ui.overlay.components.LayoutableRenderableEntity (BSD-2, RuneLite).
//
// The layout setters are no-op defaults on purpose: the shim's PanelComponent positions children by
// translating the Graphics2D itself, so components that ignore (or never implement) the setters still
// land in the right place. Upstream's own implementations satisfy these; ours keep the default.
package net.runelite.client.ui.overlay.components;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;

import net.runelite.client.ui.overlay.RenderableEntity;

public interface LayoutableRenderableEntity extends RenderableEntity
{
	Rectangle getBounds();

	default void setPreferredLocation(Point position)
	{
	}

	default void setPreferredSize(Dimension dimension)
	{
	}
}

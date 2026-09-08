// Shim of net.runelite.client.ui.overlay.components.TitleComponent (BSD-2, RuneLite) -- builder +
// render: the title text centered in the panel width.
package net.runelite.client.ui.overlay.components;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.AllArgsConstructor;
import lombok.Setter;

@Setter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TitleComponent implements LayoutableRenderableEntity
{
	@Getter
	private final String text;

	@Getter
	private final Color color;

	private final Rectangle bounds = new Rectangle();

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final FontMetrics metrics = graphics.getFontMetrics(graphics.getFont());
		final int textWidth = metrics.stringWidth(text == null ? "" : text);
		final int x = bounds.x + (bounds.width - textWidth) / 2;
		final int y = bounds.y + metrics.getHeight();

		if (color != null)
		{
			graphics.setColor(color);
		}
		graphics.drawString(text == null ? "" : text, x, y);

		final Dimension dim = new Dimension(bounds.width, metrics.getHeight() + 2);
		bounds.setSize(dim);
		return dim;
	}

	@Override
	public Rectangle getBounds()
	{
		return bounds;
	}
}

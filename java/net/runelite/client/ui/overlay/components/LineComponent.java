// Shim of net.runelite.client.ui.overlay.components.LineComponent (BSD-2, RuneLite) -- builder +
// render: left text, right-aligned text, one row.
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
public class LineComponent implements LayoutableRenderableEntity
{
	@Getter
	@Builder.Default
	private final String left = "";

	@Getter
	@Builder.Default
	private final String right = "";

	@Builder.Default
	private Color leftColor = Color.WHITE;

	@Builder.Default
	private Color rightColor = Color.WHITE;

	private final Rectangle bounds = new Rectangle();

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final FontMetrics metrics = graphics.getFontMetrics(graphics.getFont());
		final String leftText = left == null ? "" : left;
		final String rightText = right == null ? "" : right;

		if (leftColor != null)
		{
			graphics.setColor(leftColor);
		}
		graphics.drawString(leftText, bounds.x, bounds.y + metrics.getHeight());

		if (!rightText.isEmpty())
		{
			if (rightColor != null)
			{
				graphics.setColor(rightColor);
			}
			final int rightWidth = metrics.stringWidth(rightText);
			graphics.drawString(rightText, bounds.x + bounds.width - rightWidth,
				bounds.y + metrics.getHeight());
		}

		final int height = metrics.getHeight() + 2;
		final Dimension dim = new Dimension(bounds.width, height);
		bounds.setSize(dim);
		return dim;
	}

	@Override
	public Rectangle getBounds()
	{
		return bounds;
	}
}

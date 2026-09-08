// Shim of net.runelite.client.ui.overlay.components.PanelComponent (BSD-2, RuneLite).
//
// Children are laid out by translating the Graphics2D down the column, so any child implementation
// (ours, or a plugin's minimal one that ignores setPreferredLocation) draws where it should. Each
// child is ALSO told how wide it is, twice, through both contracts that exist in the wild -- see
// layout() for why one of them is not enough.
//
// The background is painted under the rows at its exact height: the rows are laid out once into a
// clipped-away copy of the Graphics2D first (nothing is painted, but the font metrics are the same,
// so the measured height is the real one), then the box is drawn, then the rows are drawn for real.
// This replaces a "height measured last frame" scheme that drew the box one frame late every time a
// row appeared or disappeared -- including the very first frame a panel was shown.
package net.runelite.client.ui.overlay.components;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PanelComponent implements LayoutableRenderableEntity
{
	private final List<LayoutableRenderableEntity> children = new ArrayList<>();

	@Getter
	private Dimension preferredSize = new Dimension(ComponentConstants.STANDARD_WIDTH, 0);

	@Getter
	private Color backgroundColor = new Color(0, 0, 0, 150);

	@Getter
	private Color borderColor = new Color(255, 255, 255, 60);

	private final Rectangle bounds = new Rectangle();

	private boolean wipeChildren = true;

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final int width = preferredSize == null ? ComponentConstants.STANDARD_WIDTH
			: preferredSize.width;
		final int border = ComponentConstants.STANDARD_BORDER;
		final int childWidth = Math.max(0, width - 2 * border);

		if (children.isEmpty())
		{
			bounds.setSize(width, 0);
			return new Dimension(width, 0);
		}

		// Measuring pass: paints nothing (empty clip) but returns each row's real height.
		final Graphics2D measure = (Graphics2D) graphics.create();
		measure.setClip(0, 0, 0, 0);
		final int[] measured = layout(measure, childWidth, border);
		measure.dispose();

		final int height = measured[1] + 2 * border;

		final Color old = graphics.getColor();
		graphics.setColor(backgroundColor);
		graphics.fillRect(bounds.x, bounds.y, width, height);
		graphics.setColor(borderColor);
		graphics.drawRect(bounds.x, bounds.y, width, height);
		graphics.setColor(old);

		final int[] drawn = layout(graphics, childWidth, border);

		final Dimension dim = new Dimension(Math.max(drawn[0] + 2 * border, width),
			drawn[1] + 2 * border);
		bounds.setSize(dim);
		if (wipeChildren)
		{
			children.clear();
		}
		return dim;
	}

	/**
	 * Renders every child down the column into {@code graphics}, returning {widest, contentHeight}.
	 * Called twice per frame: once to measure, once to draw.
	 */
	private int[] layout(Graphics2D graphics, int childWidth, int border)
	{
		int y = bounds.y + border;
		int widest = 0;

		for (LayoutableRenderableEntity child : children)
		{
			// Tell the child its width through BOTH contracts, because both exist here:
			//   setPreferredSize is upstream RuneLite's, honoured by any component with a real setter
			//     (shortest-path's own SeparatorLine, and anything a hub plugin brings);
			//   the bounds rectangle is THIS shim's -- LineComponent and TitleComponent lay themselves
			//     out from bounds and nothing else, and their fields are final so Lombok generates no
			//     setter that could reach them.
			// Without the bounds line every row's right-hand value was drawn at
			// bounds.width(0) - textWidth, i.e. that many pixels LEFT of the panel origin, and every
			// title was centred at -textWidth/2. Reported live as "the numbers show on the left".
			// The location is reset to (0,0) as well because the child's Graphics2D is translated to
			// the row (and because one SeparatorLine instance is reused for every separator in a
			// panel, so a stale location from the previous row would compound).
			child.setPreferredSize(new Dimension(childWidth, 0));
			child.getBounds().setBounds(0, 0, childWidth, 0);

			final Graphics2D childG = (Graphics2D) graphics.create();
			childG.translate(bounds.x + border, y);
			final Dimension dim = child.render(childG);
			childG.dispose();
			y += dim == null ? 0 : dim.height;
			widest = Math.max(widest, dim == null ? 0 : dim.width);
		}

		return new int[]{ widest, y - bounds.y - border };
	}

	@Override
	public Rectangle getBounds()
	{
		return bounds;
	}

	/** RuneLite also allows setting just the width; the height stays measured from the children. */
	public void setPreferredSize(int width)
	{
		this.preferredSize = new Dimension(width, 0);
	}
}

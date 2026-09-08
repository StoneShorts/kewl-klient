// Shim of net.runelite.client.ui.overlay.OverlayPanel (BSD-2, RuneLite), cut to what the ported
// plugin uses: a panelComponent whose children a plugin adds each frame.
//
// Subclasses override render(Graphics2D), add children to panelComponent, and either return this
// render's Dimension or super's. The shim draws the panel background around the children.
package net.runelite.client.ui.overlay;

import java.awt.Dimension;
import java.awt.Graphics2D;

import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.PanelComponent;

public abstract class OverlayPanel extends Overlay
{
	protected final PanelComponent panelComponent = new PanelComponent();

	private boolean clearChildren = true;

	protected OverlayPanel()
	{
		super();
		setResizable(true);
		panelComponent.setPreferredSize(ComponentConstants.STANDARD_WIDTH);
		// Seed the placement rectangle with the one dimension that is known before the first render.
		// A panel's width is fixed by the line above; only its height depends on the rows a plugin
		// adds. Seeding it means a right-aligned panel is horizontally correct on its FIRST frame
		// instead of being flung off the right edge by a zero width.
		getBounds().setSize(ComponentConstants.STANDARD_WIDTH, 0);
	}

	protected OverlayPanel(Plugin plugin)
	{
		this();
	}

	@Override
	public Dimension render(final Graphics2D graphics)
	{
		if (clearChildren && panelComponent.getChildren().isEmpty())
		{
			return new Dimension(0, 0); // nothing added this frame: draw nothing
		}
		final Dimension rendered = panelComponent.render(graphics);
		if (clearChildren)
		{
			panelComponent.getChildren().clear();
		}
		return rendered;
	}
}

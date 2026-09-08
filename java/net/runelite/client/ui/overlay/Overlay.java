// Shim of net.runelite.client.ui.overlay.Overlay (BSD-2, RuneLite), cut to the used surface:
// position/priority/layer setters, the PRIORITY_* constants, and Dimension render(Graphics2D).
package net.runelite.client.ui.overlay;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;

public abstract class Overlay implements RenderableEntity
{
	public static final float PRIORITY_LOW = 0f;
	public static final float PRIORITY_DEFAULT = 0.25f;
	public static final float PRIORITY_MED = 0.5f;
	public static final float PRIORITY_HIGH = 0.75f;
	public static final float PRIORITY_HIGHEST = 1f;

	private OverlayPosition position = OverlayPosition.TOP_LEFT;
	private OverlayLayer layer = OverlayLayer.UNDER_WIDGETS;
	private float priority = PRIORITY_DEFAULT;
	private Dimension preferredSize;
	private boolean resizable;

	// The rectangle this overlay occupied when it was last rendered. Upstream RuneLite has the same
	// field for the same reason: an overlay pinned to a RIGHT or BOTTOM corner has to be moved by its
	// own width/height before it is drawn, and its height is not known until it has been drawn. A
	// measuring pre-pass is impossible at this level -- OverlayPanel.render() empties its panel's
	// child list on the way out, so a second render in the same frame draws an empty panel -- so the
	// renderer places from the size recorded here on the PREVIOUS frame, exactly as upstream does.
	private final Rectangle bounds = new Rectangle();

	protected Overlay()
	{
	}

	public Rectangle getBounds()
	{
		return bounds;
	}

	public OverlayPosition getPosition()
	{
		return position;
	}

	public void setPosition(OverlayPosition position)
	{
		this.position = position;
	}

	public OverlayLayer getLayer()
	{
		return layer;
	}

	public void setLayer(OverlayLayer layer)
	{
		this.layer = layer;
	}

	public float getPriority()
	{
		return priority;
	}

	public void setPriority(float priority)
	{
		this.priority = priority;
	}

	public void setPriority(OverlayPriority overlayPriority)
	{
		setPriority(overlayPriority.getPriority());
	}

	public Dimension getPreferredSize()
	{
		return preferredSize;
	}

	public void setPreferredSize(Dimension preferredSize)
	{
		this.preferredSize = preferredSize;
	}

	public boolean isResizable()
	{
		return resizable;
	}

	public void setResizable(boolean resizable)
	{
		this.resizable = resizable;
	}

	public String getName()
	{
		return getClass().getSimpleName();
	}

    /** Recorded and ignored: the shim draws above everything, so draw-order hints do not apply. */
    public void drawAfterInterface(int interfaceId) {}

    public void drawAfterLayer(int interfaceId) {}
}

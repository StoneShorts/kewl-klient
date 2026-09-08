// Shim of net.runelite.api.Widget (BSD-2, RuneLite), cut to what the ported plugin reads: text,
// bounds, visibility, children, and the scroll fields of the fairy-ring panel.
//
// Values come from the widget() and widgetChild() natives. Children are filled lazily, one level per
// getDynamicChildren()/getStaticChildren() call, so a per-frame getWidget() of a leaf costs no child
// enumeration at all.
package net.runelite.api.widgets;

import java.awt.Rectangle;

import net.runelite.api.Point;
import net.runelite.api.ShimSupport;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class Widget
{
	public static final int MAX_CHILDREN = 0x10000;

	private final int id;
	private String text = "";
	private final Rectangle bounds = new Rectangle();
	private boolean hidden;
	/** X as the widget stores it -- relative to its parent -- filled by ClientState. */
	private int relativeX;
	/** Y as the widget stores it -- relative to its parent -- filled by ClientState. */
	private int relativeY;
	/**
	 * True when {@link #bounds} is a CANVAS rectangle, i.e. the parent chain was walked all the way to
	 * a root. False when it is the stored parent-relative rectangle and nothing may be drawn at it.
	 */
	private boolean canvasAbsolute;
	/** How many ancestors were summed to get {@link #bounds}; 0 for a root. Diagnostic only. */
	private int chainDepth;
	private int scrollY;
	private int scrollHeight;

	private final List<Widget> children = new ArrayList<>();
	private boolean childrenLoaded;
	/** Set by ClientState; enumerates this widget's children through the widgetChild native. */
	private Runnable childLoader;

	public Widget(int id)
	{
		this.id = id;
	}

	public int getId()
	{
		return id;
	}

	public String getText()
	{
		return text;
	}

	public void setText(String text)
	{
		this.text = text;
	}

	public Rectangle getBounds()
	{
		return bounds;
	}

	/** Filled in by ClientState from the widget() native; not a RuneLite method. */
	public void setBounds(int x, int y, int width, int height)
	{
		bounds.setBounds(x, y, width, height);
	}

	public boolean isHidden()
	{
		return hidden;
	}

	public void setHidden(boolean hidden)
	{
		this.hidden = hidden;
	}

	public int getWidth()
	{
		return bounds.width;
	}

	public int getHeight()
	{
		return bounds.height;
	}

	/**
	 * The widget's position in CANVAS coordinates, upstream's guarantee -- now actually kept, for a
	 * widget whose parent chain resolved.
	 *
	 * <p>This method used to be the single sentence this whole piece of work existed to retire: "no
	 * parent-pointer offset is derived and a packed id cannot be walked upwards", so it handed back a
	 * parent-relative pair dressed as a canvas position and every map overlay either refused or drew
	 * in the wrong corner. {@code kewl.Natives.widgetAbs} now takes RuneLite's own sum -- the
	 * component's x/y plus every ancestor's -- through a parent link derived live from the widget tree
	 * itself, and {@link net.runelite.api.ClientState} sets {@link #isCanvasAbsolute()} when that walk
	 * reached a root.</p>
	 *
	 * <p>When it did NOT, the note below fires and the returned pair is still the stored relative one.
	 * That is unchanged behaviour, deliberately: the failure mode of this feature is "the overlay
	 * refuses, as it always did", never "the overlay draws somewhere wrong". Callers that place
	 * something on the canvas must consult {@link #isCanvasAbsolute()} first --
	 * {@code Perspective.localToMinimap} gets there through its existing placement refusal, which a
	 * relative x can no longer sneak past now that a resolved chain reports the real one.</p>
	 */
	public Point getCanvasLocation()
	{
		if (!canvasAbsolute)
		{
			ShimSupport.note("Widget.getCanvasLocation", ShimSupport.Kind.NEEDS_OFFSET,
				"returns the widget's PARENT-RELATIVE x/y for this widget because the parent chain did"
					+ " not resolve to a root. Either no parent-link offset survived the whole-tree"
					+ " derivation (client/game.hpp scanWidgetTree -- run the widgetTreeProbe native to"
					+ " see the counts), or this group is parented ACROSS groups (world map 595, bank,"
					+ " any modal), which needs the client's component table and no offset for that is"
					+ " derived. Overlays anchored to it must refuse rather than draw");
		}
		return new Point(bounds.x, bounds.y);
	}

	/**
	 * True when {@link #getBounds()} and {@link #getCanvasLocation()} are a CANVAS rectangle. False
	 * when they are the stored parent-relative one, which nothing may be drawn at.
	 *
	 * <p>Not a RuneLite method -- upstream has no reason for it, because upstream always knows the
	 * parent. It is the shim's honesty flag, and the reason pointing every widget consumer at absolute
	 * geometry at once is safe.</p>
	 */
	public boolean isCanvasAbsolute()
	{
		return canvasAbsolute;
	}

	/** Filled in by ClientState; not a RuneLite method. */
	public void setCanvasAbsolute(boolean canvasAbsolute)
	{
		this.canvasAbsolute = canvasAbsolute;
	}

	/** How many ancestors were summed into the bounds. Diagnostic; not a RuneLite method. */
	public int getChainDepth()
	{
		return chainDepth;
	}

	/** Filled in by ClientState; not a RuneLite method. */
	public void setChainDepth(int chainDepth)
	{
		this.chainDepth = chainDepth;
	}

	public Point getCanvasLocation(int plane)
	{
		return getCanvasLocation();
	}

	/**
	 * The shim's one child list, seen through both RuneLite accessors. The widgetChild native exposes
	 * a single child array per widget and cannot say which of its entries the cache defined and which
	 * the game spawned at runtime, so static and dynamic callers get the same enumeration. The
	 * plugin's two consumers look for a specific text match in one list or the other, so the
	 * overlap only ever re-tests a widget, never mislabels one.
	 */
	public Widget[] getDynamicChildren()
	{
		ensureChildren();
		return children.toArray(new Widget[0]);
	}

	public Collection<Widget> getChildren()
	{
		ensureChildren();
		return children;
	}

	public Widget[] getStaticChildren()
	{
		return getDynamicChildren();
	}

	public int getScrollY()
	{
		return scrollY;
	}

	public void setScrollY(int scrollY)
	{
		this.scrollY = scrollY;
	}

	/**
	 * Derived, not read: the widget native reports geometry but no scroll state, so the content height
	 * is the furthest child edge. Good enough for "is this row scrolled out of view"; a real
	 * scrollHeight needs the same offset as setScrollY below.
	 */
	public int getScrollHeight()
	{
		ensureChildren();
		return scrollHeight;
	}

	/** X within the parent, as the widget stores it. */
	public int getRelativeX()
	{
		return relativeX;
	}

	/** Y within the parent, as the widget stores it. */
	public int getRelativeY()
	{
		return relativeY;
	}

	/** Stored and ignored: nothing in the shim draws widget text, the game does. */
	public void setTextColor(int color)
	{
		this.textColor = color;
	}

	public int getTextColor()
	{
		return textColor;
	}

	private int textColor;

	/**
	 * Stored, not applied. Writing the game's scroll position needs the client's script VM (the
	 * plugin drives it with the UPDATE_SCROLLBAR script, which runScript cannot run yet), so the
	 * stored value is what the shim believes the scroll is and the panel does not actually move.
	 */
	public void revalidateScroll()
	{
		ShimSupport.note("Widget.revalidateScroll", ShimSupport.Kind.NEEDS_OFFSET,
			"does NOTHING: setScrollY stores a number the client never sees, because moving a real"
				+ " scrollbar needs the cs2 VM (see Client.runScript). A plugin that scrolls a panel"
				+ " to reveal a row gets no movement and no error");
	}

	// -- shim plumbing (not RuneLite API) -----------------------------------------------------------

	/**
	 * Filled in by ClientState from the widget() native; not a RuneLite method. Kept alongside the
	 * absolute bounds rather than replaced by them: the two shapes must stay distinguishable, and
	 * {@link #getScrollHeight()} is derived from children's RELATIVE y, not from a canvas one.
	 */
	public void setRelativeX(int x)
	{
		this.relativeX = x;
	}

	/** Filled in by ClientState from the widget() native; not a RuneLite method. */
	public void setRelativeY(int y)
	{
		this.relativeY = y;
	}

	/** Derived by ClientState from the children's extents; not a RuneLite method. */
	public void setScrollHeight(int scrollHeight)
	{
		this.scrollHeight = scrollHeight;
	}

	/** Filled in by ClientState; not a RuneLite method. */
	public void setChildLoader(Runnable loader)
	{
		this.childLoader = loader;
	}

	private void ensureChildren()
	{
		if (!childrenLoaded)
		{
			childrenLoaded = true; // set first: a self-referential child must not re-enter
			if (childLoader != null)
			{
				childLoader.run();
			}
		}
	}
}

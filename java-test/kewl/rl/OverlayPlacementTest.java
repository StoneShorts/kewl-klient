// Where a corner-anchored overlay lands, with no game behind it.
//
// This exists because of one user report -- "the overlay for shortestpath is buggy. its not aligned
// properly" -- and one of its two causes: the renderer used to right-align every panel by assuming it
// was 160 wide and bottom-align it by assuming it was 200 tall, so a 129-wide panel sat 31px off the
// right margin and every bottom-anchored stack grew DOWNWARD, off the canvas. The placement
// arithmetic is pure, so it can be pinned here exactly.
package kewl.rl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;

import org.junit.Test;

import net.runelite.client.ui.overlay.OverlayPosition;

public class OverlayPlacementTest
{
	/** A canvas the size of the game client area, origin at 0,0 as the real Graphics2D clip is. */
	private static final Rectangle CANVAS = new Rectangle(0, 0, 1000, 700);

	/** The shim's panel size: STANDARD_WIDTH wide, whatever the rows measured tall. */
	private static final Dimension PANEL = new Dimension(129, 80);

	@Test
	public void topCornersAnchorToTheTopMargin()
	{
		assertEquals(new Point(5, 20), OverlayRenderer.anchorFor(OverlayPosition.TOP_LEFT, CANVAS));
		assertEquals(new Point(995, 20), OverlayRenderer.anchorFor(OverlayPosition.TOP_RIGHT, CANVAS));
	}

	@Test
	public void bottomCornersAnchorToTheBottomMargin()
	{
		assertEquals(new Point(5, 695), OverlayRenderer.anchorFor(OverlayPosition.BOTTOM_LEFT, CANVAS));
		assertEquals(new Point(995, 695), OverlayRenderer.anchorFor(OverlayPosition.BOTTOM_RIGHT, CANVAS));
	}

	/** TOP_LEFT used to return a hardcoded (8, 8) and ignore the rectangle it was handed. */
	@Test
	public void everyAnchorHonoursTheCanvasOrigin()
	{
		Rectangle offset = new Rectangle(40, 10, 1000, 700);
		for (OverlayPosition p : OverlayPosition.values())
		{
			if (!OverlayRenderer.isCornerAnchored(p))
			{
				continue;
			}
			Point at = OverlayRenderer.anchorFor(p, offset);
			Point origin = OverlayRenderer.anchorFor(p, CANVAS);
			assertEquals(p + " x ignored the origin", origin.x + 40, at.x);
			assertEquals(p + " y ignored the origin", origin.y + 10, at.y);
		}
	}

	/** ABOVE_CHATBOX_RIGHT used to be mapped to the TOP-right corner. */
	@Test
	public void aboveChatboxIsNotTheTopRightCorner()
	{
		Point chat = OverlayRenderer.anchorFor(OverlayPosition.ABOVE_CHATBOX_RIGHT, CANVAS);
		Point top = OverlayRenderer.anchorFor(OverlayPosition.TOP_RIGHT, CANVAS);
		assertEquals(top.x, chat.x);
		assertTrue("must sit above the bottom margin, not at the top", chat.y > top.y);
		assertTrue(chat.y < OverlayRenderer.anchorFor(OverlayPosition.BOTTOM_RIGHT, CANVAS).y);
	}

	/** The guard against a hardcoded 160x200 creeping back: the shift is the overlay's OWN size. */
	@Test
	public void transformShiftsByTheOverlaysOwnSize()
	{
		assertEquals(new Point(0, 0), OverlayRenderer.transformPosition(OverlayPosition.TOP_LEFT, PANEL));
		assertEquals(new Point(-129, 0), OverlayRenderer.transformPosition(OverlayPosition.TOP_RIGHT, PANEL));
		assertEquals(new Point(0, -80), OverlayRenderer.transformPosition(OverlayPosition.BOTTOM_LEFT, PANEL));
		assertEquals(new Point(-129, -80), OverlayRenderer.transformPosition(OverlayPosition.BOTTOM_RIGHT, PANEL));
		assertEquals(new Point(-64, 0), OverlayRenderer.transformPosition(OverlayPosition.TOP_CENTER, PANEL));
	}

	/** A right/bottom-anchored panel's right/bottom EDGE is what meets the margin. */
	@Test
	public void bottomRightPanelEdgesMeetTheMargins()
	{
		Point cursor = OverlayRenderer.anchorFor(OverlayPosition.BOTTOM_RIGHT, CANVAS);
		Point at = OverlayRenderer.placeAt(OverlayPosition.BOTTOM_RIGHT, cursor, PANEL);

		assertEquals("right edge", 1000 - 5, at.x + PANEL.width);
		assertEquals("bottom edge", 700 - 5, at.y + PANEL.height);
		assertTrue("must stay on the canvas", at.x >= 0 && at.y >= 0);
	}

	@Test
	public void topLeftPanelSitsAtTheTopLeftMargin()
	{
		Point cursor = OverlayRenderer.anchorFor(OverlayPosition.TOP_LEFT, CANVAS);
		Point at = OverlayRenderer.placeAt(OverlayPosition.TOP_LEFT, cursor, PANEL);
		assertEquals(new Point(5, 20), at);
	}

	/** Bottom corners stack UPWARD: the second panel must not walk off the bottom of the canvas. */
	@Test
	public void bottomCornerStacksUpward()
	{
		Point cursor = OverlayRenderer.anchorFor(OverlayPosition.BOTTOM_RIGHT, CANVAS);

		Point first = OverlayRenderer.placeAt(OverlayPosition.BOTTOM_RIGHT, cursor, PANEL);
		OverlayRenderer.advance(OverlayPosition.BOTTOM_RIGHT, cursor, PANEL.height);
		Point second = OverlayRenderer.placeAt(OverlayPosition.BOTTOM_RIGHT, cursor, PANEL);

		assertTrue("second panel must be ABOVE the first", second.y < first.y);
		assertEquals("stacked with one padding gap", first.y - PANEL.height - 3, second.y);
		assertEquals("both flush to the right margin", first.x, second.x);
	}

	/** Top corners still stack downward. */
	@Test
	public void topCornerStacksDownward()
	{
		Point cursor = OverlayRenderer.anchorFor(OverlayPosition.TOP_LEFT, CANVAS);

		Point first = OverlayRenderer.placeAt(OverlayPosition.TOP_LEFT, cursor, PANEL);
		OverlayRenderer.advance(OverlayPosition.TOP_LEFT, cursor, PANEL.height);
		Point second = OverlayRenderer.placeAt(OverlayPosition.TOP_LEFT, cursor, PANEL);

		assertEquals(first.y + PANEL.height + 3, second.y);
	}

	/**
	 * Routing is by POSITION, not by "is this an OverlayPanel". All four shortest-path map and tile
	 * overlays are DYNAMIC and draw in their own coordinates; translating them would move the path.
	 */
	@Test
	public void dynamicOverlaysAreNeverTranslated()
	{
		assertFalse(OverlayRenderer.isCornerAnchored(OverlayPosition.DYNAMIC));
		assertFalse(OverlayRenderer.isCornerAnchored(OverlayPosition.MOUSE));
		assertFalse(OverlayRenderer.isCornerAnchored(OverlayPosition.TOOLTIP));
		assertFalse(OverlayRenderer.isCornerAnchored(OverlayPosition.STATE_OVERLAY));
		assertTrue(OverlayRenderer.isCornerAnchored(OverlayPosition.TOP_LEFT));
		assertTrue(OverlayRenderer.isCornerAnchored(OverlayPosition.BOTTOM_RIGHT));
	}
}

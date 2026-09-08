// Draws a registered RuneLite overlay set into kewl's single Graphics2D.
//
// Layering collapses: the layered window is above everything the game draws, so UNDER_WIDGETS and
// ABOVE_SCENE end up in the same pass no matter what we do. The values still order overlays relative
// to each other.
//
// Placement follows upstream RuneLite's snap-corner model: an overlay whose position names a corner
// is moved by its OWN size (measured on the previous frame and recorded in Overlay.getBounds) so its
// right edge meets the right margin and its bottom edge meets the bottom margin, then the corner's
// cursor advances -- down from the top corners, UP from the bottom ones. Overlays whose position is
// DYNAMIC/MOUSE/TOOLTIP/STATE_OVERLAY draw in their own coordinates and are never translated.
//
// After the plugin overlay pass a WorldMapPoint marker pass runs: plugins push markers into a
// WorldMapPointManager (Shortest Path's target marker is the only user), and nothing else renders
// them. See renderWorldMapPoints for what that pass does and does not promise.
package kewl.rl;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

final class OverlayRenderer
{
	private static final Comparator<Overlay> ORDER = Comparator
		.comparingInt(OverlayRenderer::layerRank)
		.thenComparing(o -> -o.getPriority());

	private OverlayRenderer()
	{
	}

	static void render(Graphics2D g, OverlayManager manager)
	{
		List<Overlay> overlays = new ArrayList<>(manager.getOverlays());
		overlays.sort(ORDER);

		// The canvas rectangle overlays are anchored to. g.getClipBounds() is always
		// (0, 0, canvasWidth, canvasHeight) here: KewlKlient.render() creates the Graphics2D from a
		// BufferedImage sized to Natives.viewport() -- the game's client area, the same space the
		// projection natives and postMouse work in -- and a BufferedImage's Graphics2D always carries
		// the image bounds as its clip. If it is ever null we simply have no canvas rectangle, and an
		// empty one degrades to the origin; inventing RuneLite's fixed-mode 765x503 viewport (which
		// this canvas is not) would put every corner-anchored panel in the wrong place instead.
		Rectangle clipped = g.getClipBounds();
		final Rectangle bounds = clipped == null ? new Rectangle() : clipped;

		// Per-position running cursor so panels stacking in one corner do not overlap. It grows DOWN
		// from the top corners and UP from the bottom ones.
		Map<OverlayPosition, Point> cursors = new EnumMap<>(OverlayPosition.class);

		for (Overlay o : overlays)
		{
			Graphics2D og = (Graphics2D) g.create();
			try
			{
				final OverlayPosition position = o.getPosition();
				if (isCornerAnchored(position))
				{
					Point cursor = cursors.computeIfAbsent(position, p -> anchorFor(p, bounds));

					// The size this overlay had LAST frame (or its explicit preferred size). Right-
					// and bottom-anchored overlays must be moved by their own width/height before
					// they are drawn, and nothing can know that height before the draw: OverlayPanel
					// empties its panel's children on the way out of render, so a measuring pre-pass
					// here would just draw an empty panel. Upstream RuneLite solves it the same way.
					Dimension size = o.getPreferredSize() != null
						? o.getPreferredSize() : o.getBounds().getSize();
					Point where = placeAt(position, cursor, size);
					og.translate(where.x, where.y);

					Dimension d = o.render(og);
					// Upstream's !bounds.isEmpty(): a panel that added no rows this frame returns
					// (0, 0) and must neither record a size nor consume a slot's padding.
					if (d != null && d.width > 0 && d.height > 0)
					{
						o.getBounds().setSize(d);
						advance(position, cursor, d.height);
					}
				}
				else
				{
					o.render(og);
				}
			}
			catch (Throwable t)
			{
				System.out.println("[overlay:" + o.getName() + "] render threw: " + t);
			}
			finally
			{
				og.dispose();
			}
		}

		renderWorldMapPoints(g);
	}

	/** Last reason the world-map marker pass stood down; keyed on the text so each is said once. */
	private static String loggedMapMarkerRefusal = "";

	/**
	 * The WorldMapPoint markers plugins added. Two surfaces:
	 *
	 * World map: gated on {@link WorldMap#refusalFor}, the SAME predicate PathMapOverlay, the
	 * map-click resolver and the map menu entries use. It used to gate on "the container widget is
	 * non-null and the centre is live", which is three conditions short and every one of them draws
	 * something wrong rather than nothing:
	 * <ul>
	 *   <li>no isHidden() test -- the world-map GROUP STAYS LOADED WHILE THE MAP IS CLOSED on this
	 *       build, so a non-null container is not an open map and this pass painted markers over the
	 *       SCENE whenever a target was set;</li>
	 *   <li>no isCanvasAbsolute() test -- the container's stored x/y are parent-relative until the
	 *       chain resolves, so both the marker positions AND {@code mg.setClip(rect)} were taken from
	 *       a rectangle that is not where the map is. Clipping to a wrong rectangle is the failure
	 *       that painted the world map solid black;</li>
	 *   <li>no isZoomCalibrated() test -- the placeholder 4.0 px/tile scales every marker's distance
	 *       from the centre.</li>
	 * </ul>
	 * The pixel mapping mirrors ShortestPathPlugin.mapWorldPointToGraphicsPointX/Y (RuneLite's own
	 * world-map-overlay maths, anchored on the map centre and the MAP_CONTAINER bounds).
	 *
	 * Minimap: the same Perspective.localToMinimap projection PathMinimapOverlay uses, clipped to the
	 * minimap draw widget's ellipse.
	 *
	 * The images come off the points themselves, which the plugins load once at startup -- nothing is
	 * loaded here per frame. Points with a null image are skipped.
	 */
	private static void renderWorldMapPoints(Graphics2D g)
	{
		WorldMapPointManager manager = WorldMapPointManager.get();
		if (manager == null)
		{
			return; // no plugin built yet, so no markers exist
		}

		Client client = Client.get();
		List<WorldMapPoint> points = manager.getWorldMapPoints();
		if (points.isEmpty())
		{
			return;
		}

		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		String mapRefusal = WorldMap.refusalFor(map, client.getWorldMap());
		if (mapRefusal == null)
		{
			Rectangle rect = map.getBounds();
			net.runelite.api.Point centre = client.getWorldMap().getWorldMapPosition();
			float pixelsPerTile = client.getWorldMap().getWorldMapZoom();
			int widthInTiles = (int) Math.ceil(rect.getWidth() / pixelsPerTile);
			int heightInTiles = (int) Math.ceil(rect.getHeight() / pixelsPerTile);

			Graphics2D mg = (Graphics2D) g.create();
			mg.setClip(rect);
			for (WorldMapPoint p : points)
			{
				BufferedImage image = p.getImage();
				WorldPoint wp = p.getWorldPoint();
				if (image == null || wp == null)
				{
					continue;
				}

				// Same conversion as ShortestPathPlugin.mapWorldPointToGraphicsPointX/Y: the map
				// centre sits at the middle of MAP_CONTAINER, one zoom pixel per tile.
				int xTileOffset = wp.getX() + widthInTiles / 2 - centre.getX();
				int x = (int) (xTileOffset * pixelsPerTile)
					+ (int) (pixelsPerTile - Math.ceil(pixelsPerTile / 2))
					+ (int) rect.getX();
				int yTileOffset = (centre.getY() - heightInTiles / 2 - wp.getY() - 1) * -1;
				int y = rect.height - (int) (yTileOffset * pixelsPerTile)
					+ (int) (pixelsPerTile - Math.ceil(pixelsPerTile / 2))
					+ (int) rect.getY();

				mg.drawImage(image, x - image.getWidth() / 2, y - image.getHeight() / 2, null);
			}
			mg.dispose();
		}
		else if (!mapRefusal.equals(loggedMapMarkerRefusal))
		{
			// Keyed on the text, exactly as PathMapOverlay does it: a session moves from "not loaded"
			// to "closed" to "no scale", and each distinct reason is worth saying once. Silence here
			// would read as "the marker feature is broken" rather than "the map geometry is not
			// trusted yet", which is a different fix.
			loggedMapMarkerRefusal = mapRefusal;
			System.out.println("[overlay] world-map markers stay OFF: " + mapRefusal
				+ ". Minimap markers are unaffected.");
		}

		// Minimap: the same projection PathMinimapOverlay uses (which rotates with the camera and
		// approximates the game's own minimap rotation -- accepted there, so accepted here too).
		Widget minimap = client.getMinimapDrawWidget();
		if (minimap == null || minimap.isHidden())
		{
			return;
		}
		int plane = client.getTopLevelWorldView().getPlane();
		Graphics2D mng = (Graphics2D) g.create();
		// The clip is the widget's bounding ellipse, PathMinimapOverlay's fallback shape: the mask
		// sprites it prefers are not reachable from here.
		mng.setClip(new Ellipse2D.Double(minimap.getBounds().getX(), minimap.getBounds().getY(),
			minimap.getBounds().getWidth(), minimap.getBounds().getHeight()));
		for (WorldMapPoint p : points)
		{
			BufferedImage image = p.getImage();
			WorldPoint wp = p.getWorldPoint();
			if (image == null || wp == null)
			{
				continue;
			}
			LocalPoint lp = LocalPoint.fromWorld(client, wp);
			if (lp == null || wp.getPlane() != plane)
			{
				continue;
			}
			net.runelite.api.Point pos = Perspective.localToMinimap(client, lp);
			if (pos == null)
			{
				continue;
			}
			mng.drawImage(image, pos.getX() - image.getWidth() / 2, pos.getY() - image.getHeight() / 2, null);
		}
		mng.dispose();
	}

	/** Margin between a corner-anchored overlay and the canvas edge. */
	static final int BORDER = 5;

	/** Top margin. Larger than BORDER so a top-anchored panel clears the game's own top furniture. */
	static final int BORDER_TOP = 20;

	/** Gap between two overlays stacked in the same corner. */
	static final int PADDING = 3;

	/**
	 * UNVERIFIED. Height reserved above the bottom edge for the chatbox, used only by
	 * ABOVE_CHATBOX_RIGHT. It is a guess, not a measurement: the chatbox widget's own rectangle
	 * cannot be trusted while widget x/y are parent-relative (see the project's ground truth), so
	 * there is nothing live to derive it from. No overlay in this tree uses that position today.
	 */
	static final int CHATBOX_HEIGHT = 165;

	/**
	 * Whether {@code position} pins an overlay to a canvas corner. Routing is by POSITION, as upstream
	 * does it, not by whether the overlay happens to be an OverlayPanel: DYNAMIC/MOUSE/TOOLTIP/
	 * STATE_OVERLAY overlays draw in their own coordinates (all four shortest-path map and tile
	 * overlays are DYNAMIC) and must not be translated.
	 */
	static boolean isCornerAnchored(OverlayPosition position)
	{
		switch (position)
		{
			case DYNAMIC:
			case MOUSE:
			case TOOLTIP:
			case STATE_OVERLAY:
				return false;
			default:
				return true;
		}
	}

	/** Bottom-anchored stacks grow away from the edge they are pinned to, i.e. upward. */
	static boolean growsUpward(OverlayPosition position)
	{
		switch (position)
		{
			case BOTTOM_LEFT:
			case BOTTOM_CENTER:
			case BOTTOM_RIGHT:
			case ABOVE_CHATBOX_RIGHT:
				return true;
			default:
				return false;
		}
	}

	/**
	 * The anchor point of {@code position} on {@code bounds}: the canvas corner the stack starts from,
	 * BEFORE the overlay's own size is taken off it (see {@link #transformPosition}). Every case
	 * honours bounds.x/bounds.y so a non-zero canvas origin would still work.
	 */
	static Point anchorFor(OverlayPosition position, Rectangle bounds)
	{
		final int left = bounds.x + BORDER;
		final int right = bounds.x + bounds.width - BORDER;
		final int centre = bounds.x + bounds.width / 2;
		final int top = bounds.y + BORDER_TOP;
		final int bottom = bounds.y + bounds.height - BORDER;

		switch (position)
		{
			case TOP_CENTER: return new Point(centre, bounds.y + BORDER);
			case TOP_RIGHT: return new Point(right, top);
			case BOTTOM_LEFT: return new Point(left, bottom);
			case BOTTOM_CENTER: return new Point(centre, bottom);
			case BOTTOM_RIGHT: return new Point(right, bottom);
			case ABOVE_CHATBOX_RIGHT: return new Point(right, bottom - CHATBOX_HEIGHT);
			case TOP_LEFT:
			default: return new Point(left, top);
		}
	}

	/**
	 * The top-left corner an overlay of {@code size} actually draws at, given its corner's running
	 * {@code cursor}. Pure, so the placement arithmetic can be asserted without a game.
	 */
	static Point placeAt(OverlayPosition position, Point cursor, Dimension size)
	{
		final Point offset = transformPosition(position, size);
		return new Point(cursor.x + offset.x, cursor.y + offset.y);
	}

	/** Moves {@code cursor} past an overlay {@code height} tall, away from the edge it is pinned to. */
	static void advance(OverlayPosition position, Point cursor, int height)
	{
		cursor.y += growsUpward(position) ? -(height + PADDING) : height + PADDING;
	}

	/**
	 * How far an overlay of {@code size} must be shifted off its anchor so the anchored EDGE of the
	 * overlay meets the anchor: right-anchored overlays move left by their width, bottom-anchored
	 * ones up by their height, centred ones left by half their width. This is what replaces the old
	 * hardcoded "assume every panel is 160 wide and 200 tall".
	 */
	static Point transformPosition(OverlayPosition position, Dimension size)
	{
		final Point offset = new Point();
		switch (position)
		{
			case TOP_LEFT:
				break;
			case TOP_CENTER:
				offset.x -= size.width / 2;
				break;
			case TOP_RIGHT:
				offset.x -= size.width;
				break;
			case BOTTOM_LEFT:
				offset.y -= size.height;
				break;
			case BOTTOM_CENTER:
				offset.x -= size.width / 2;
				offset.y -= size.height;
				break;
			case BOTTOM_RIGHT:
			case ABOVE_CHATBOX_RIGHT:
				offset.x -= size.width;
				offset.y -= size.height;
				break;
			default:
				break;
		}
		return offset;
	}

	private static int layerRank(Overlay o)
	{
		switch (o.getLayer())
		{
			case UNDER_WIDGETS: return 0;
			case ABOVE_SCENE: return 1;
			case ABOVE_WIDGETS: return 2;
			case MANUAL: return 3;
			case ALWAYS_ON_TOP:
			default: return 4;
		}
	}
}

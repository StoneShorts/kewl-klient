package shortestpath;

import com.google.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.util.HashSet;
import java.util.Objects;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.PathStep;
import shortestpath.transport.Transport;

public class PathMapOverlay extends Overlay
{
	/**
	 * Largest tile extent per axis the collision-map pass will walk. The real world is a few thousand
	 * tiles across and the widget only shows a few hundred at any sane zoom; past this the extent came
	 * from broken map maths, and iterating it would hang the frame.
	 */
	private static final int MAX_EXTENT_TILES = 1024;

	private final Client client;
	private final ShortestPathPlugin plugin;

	@Inject
	private PathMapOverlay(Client client, ShortestPathPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		setLayer(OverlayLayer.MANUAL);
		drawAfterLayer(InterfaceID.Worldmap.MAP_CONTAINER);
	}

	private static String loggedMapRefusal = "";

	/**
	 * Whether the shim can describe the world map well enough to draw on it -- ONE predicate, shared
	 * with {@code ShortestPathPlugin}'s map-click path and its menu entries, so the three cannot
	 * disagree about whether the map is usable (they did, and that disagreement is half of the
	 * "a map click flies to another area" report).
	 *
	 * <p>This used to be a hardcoded {@code false}. It was right to be: with a parent-relative
	 * rectangle AND a placeholder scale, this overlay clipped and filled the wrong region and the map
	 * rendered FULLY BLACK with its close button unusable (live 2026-09-06, proven by bisection).
	 * What has changed is that both failures are now DETECTED rather than assumed: the widget reports
	 * whether its rectangle is canvas-absolute, and the scale reports whether it was measured. So the
	 * refusal lifts by itself the moment the geometry is real, and re-arms by itself if it stops being
	 * -- and when it refuses it says WHICH of the five conditions failed, because the fixes differ.</p>
	 */
	private boolean worldMapGeometryTrusted(Widget map)
	{
		String refusal = WorldMap.refusalFor(map, client.getWorldMap());
		if (refusal == null)
		{
			return true;
		}
		// Keyed on the TEXT, so each distinct reason is said once and a changed reason is said again
		// (a session can move from "not loaded" to "closed" to "no scale"), without a line per frame.
		if (!refusal.equals(loggedMapRefusal))
		{
			loggedMapRefusal = refusal;
			System.out.println("[shortestpath] world map overlay stays OFF: " + refusal
					+ ". Path tiles, the minimap and the scene overlays are unaffected.");
		}
		return false;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.drawMap)
		{
			return null;
		}

		Widget mapContainer = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);

		// kewl: DO NOT DRAW while the shim cannot describe the map. Painting over the game's own map is
		// far worse than drawing no path on it -- see worldMapGeometryTrusted for what that looked like
		// -- so the honest behaviour is to stand down and say why. Note this is also the isHidden()
		// gate: on this build the world-map GROUP STAYS LOADED WHILE THE MAP IS CLOSED, so the upstream
		// null check alone let this overlay run with the map shut and paint over the scene.
		if (!worldMapGeometryTrusted(mapContainer))
		{
			return null;
		}

		Rectangle worldMapRectangle = Objects.requireNonNull(mapContainer).getBounds();
		Area worldMapClipArea = getWorldMapClipArea(worldMapRectangle);
		graphics.setClip(worldMapClipArea);

		if (plugin.drawCollisionMap && collisionExtentIsSane(worldMapRectangle))
		{
			graphics.setColor(plugin.colourCollisionMap);
			int mapWorldPoint = plugin.calculateMapPoint(worldMapRectangle.x, worldMapRectangle.y);
			int extentX = WorldPointUtil.unpackWorldX(mapWorldPoint);
			int extentY = WorldPointUtil.unpackWorldY(mapWorldPoint);
			int extentWidth = getWorldMapExtentWidth(worldMapRectangle);
			int extentHeight = getWorldMapExtentHeight(worldMapRectangle);
			final CollisionMap map = plugin.getMap();
			final int z = client.getTopLevelWorldView().getPlane();
			for (int x = extentX; x < (extentX + extentWidth + 1); x++)
			{
				for (int y = extentY - extentHeight; y < (extentY + 1); y++)
				{
					if (map.isBlocked(x, y, z))
					{
						drawOnMap(graphics, WorldPointUtil.packWorldPoint(x, y, z), false, null);
					}
				}
			}
		}

		if (plugin.drawTransports)
		{
			graphics.setColor(Color.WHITE);
			for (int a : plugin.getTransports().keySet())
			{
				if (a == Transport.UNDEFINED_ORIGIN)
				{
					continue; // skip teleports
				}

				int mapAX = plugin.mapWorldPointToGraphicsPointX(a);
				int mapAY = plugin.mapWorldPointToGraphicsPointY(a);
				if (!worldMapClipArea.contains(mapAX, mapAY))
				{
					continue;
				}

				for (Transport b : plugin.getTransports().getOrDefault(a, new HashSet<>()))
				{
					if (b == null || (b.getType() != null && b.getType().isTeleport()))
					{
						continue; // skip teleports
					}

					int mapBX = plugin.mapWorldPointToGraphicsPointX(b.getDestination());
					int mapBY = plugin.mapWorldPointToGraphicsPointY(b.getDestination());
					if (!worldMapClipArea.contains(mapBX, mapBY))
					{
						continue;
					}

					graphics.drawLine(mapAX, mapAY, mapBX, mapBY);
				}
			}
		}

		if (plugin.getPathfinder() != null)
		{
			Color colour = plugin.getPathColor();
			java.util.List<PathStep> path = plugin.getPathfinder().getPath();
			Point cursorPos = client.getMouseCanvasPosition();
			for (int i = 0; i < path.size(); i++)
			{
				graphics.setColor(colour);
				int point = path.get(i).getPackedPosition();
				int lastPoint = (i > 0) ? path.get(i - 1).getPackedPosition() : point;
				if (WorldPointUtil.distanceBetween(point, lastPoint) > 1)
				{
					drawOnMap(graphics, lastPoint, point, true, cursorPos);
				}
				drawOnMap(graphics, point, true, cursorPos);
			}
			for (int target : plugin.getPathfinder().getTargets())
			{
				if (!path.isEmpty() && target != path.get(path.size() - 1).getPackedPosition())
				{
					graphics.setColor(plugin.colourPathCalculating);
					drawOnMap(graphics, target, true, cursorPos);
				}
			}
		}

		return null;
	}

	private void drawOnMap(Graphics2D graphics, int point, boolean checkHover, Point cursorPos)
	{
		drawOnMap(graphics, point, WorldPointUtil.dxdy(point, 1, -1), checkHover, cursorPos);
	}

	private void drawOnMap(Graphics2D graphics, int point, int offsetPoint, boolean checkHover, Point cursorPos)
	{
		int startX = plugin.mapWorldPointToGraphicsPointX(point);
		int startY = plugin.mapWorldPointToGraphicsPointY(point);
		int endX = plugin.mapWorldPointToGraphicsPointX(offsetPoint);
		int endY = plugin.mapWorldPointToGraphicsPointY(offsetPoint);

		if (startX == Integer.MIN_VALUE || startY == Integer.MIN_VALUE ||
			endX == Integer.MIN_VALUE || endY == Integer.MIN_VALUE)
		{
			return;
		}

		int x = startX;
		int y = startY;
		final int width = endX - x;
		final int height = endY - y;
		x -= width / 2;
		y -= height / 2;

		if (WorldPointUtil.distanceBetween(point, offsetPoint) > 1)
		{
			graphics.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{9}, 0));
			graphics.drawLine(startX, startY, endX, endY);
		}
		else
		{
			if (checkHover && cursorPos != null &&
				cursorPos.getX() >= x && cursorPos.getX() <= (endX - width / 2) &&
				cursorPos.getY() >= y && cursorPos.getY() <= (endY - width / 2))
			{
				graphics.setColor(graphics.getColor().darker());
			}
			graphics.fillRect(x, y, width, height);
		}
	}

	private Area getWorldMapClipArea(Rectangle baseRectangle)
	{
		final Widget overview = client.getWidget(InterfaceID.Worldmap.OVERVIEW_CONTAINER);
		final Widget surfaceSelector = client.getWidget(InterfaceID.Worldmap.MAPLIST_BOX_GRAPHIC0);

		Area clipArea = new Area(baseRectangle);

		// kewl: the same absoluteness gate the container passes. These two are SUBTRACTED from the
		// clip, so a parent-relative rectangle here does not merely fail to protect the overview panel
		// -- it punches a hole out of the path somewhere else on the map. Skipping the subtraction
		// draws path over the overview panel, which is cosmetic; subtracting the wrong rectangle is
		// not, so the failure is taken in the cosmetic direction.
		if (overview != null && !overview.isHidden() && overview.isCanvasAbsolute())
		{
			clipArea.subtract(new Area(overview.getBounds()));
		}

		if (surfaceSelector != null && !surfaceSelector.isHidden() && surfaceSelector.isCanvasAbsolute())
		{
			clipArea.subtract(new Area(surfaceSelector.getBounds()));
		}

		return clipArea;
	}

	/**
	 * The collision pass walks every tile between the map points of two opposite widget corners. When
	 * calculateMapPoint returns UNDEFINED (the pixel is outside what the map maths can invert),
	 * unpackWorldX(-1) is 32767 and the loops would iterate ~32767^2 tiles, hanging the frame. Bail
	 * out when either corner is UNDEFINED or the extent is negative (inverted corners) or past
	 * {@link #MAX_EXTENT_TILES} -- the same shape of guard drawOnMap's Integer.MIN_VALUE checks are.
	 */
	private boolean collisionExtentIsSane(Rectangle baseRectangle)
	{
		int topLeft = plugin.calculateMapPoint(baseRectangle.x, baseRectangle.y);
		int bottomRight = plugin.calculateMapPoint(
			baseRectangle.x + baseRectangle.width, baseRectangle.y + baseRectangle.height);
		if (topLeft == WorldPointUtil.UNDEFINED || bottomRight == WorldPointUtil.UNDEFINED)
		{
			return false;
		}
		int width = WorldPointUtil.unpackWorldX(bottomRight) - WorldPointUtil.unpackWorldX(topLeft);
		int height = WorldPointUtil.unpackWorldY(topLeft) - WorldPointUtil.unpackWorldY(bottomRight);
		return width >= 0 && width <= MAX_EXTENT_TILES && height >= 0 && height <= MAX_EXTENT_TILES;
	}

	private int getWorldMapExtentWidth(Rectangle baseRectangle)
	{
		return (WorldPointUtil.unpackWorldX(
			plugin.calculateMapPoint(
				baseRectangle.x + baseRectangle.width,
				baseRectangle.y + baseRectangle.height))
			-
			WorldPointUtil.unpackWorldX(
				plugin.calculateMapPoint(
					baseRectangle.x,
					baseRectangle.y)));
	}

	private int getWorldMapExtentHeight(Rectangle baseRectangle)
	{
		return (WorldPointUtil.unpackWorldY(
			plugin.calculateMapPoint(
				baseRectangle.x,
				baseRectangle.y))
			-
			WorldPointUtil.unpackWorldY(
				plugin.calculateMapPoint(
					baseRectangle.x + baseRectangle.width,
					baseRectangle.y + baseRectangle.height)));
	}
}

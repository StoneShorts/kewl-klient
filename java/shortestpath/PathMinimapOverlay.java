package shortestpath;

import com.google.inject.Inject;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import shortestpath.pathfinder.PathStep;

public class PathMinimapOverlay extends Overlay
{
	private final Client client;
	private final ShortestPathPlugin plugin;

	@Inject
	private PathMinimapOverlay(Client client, ShortestPathPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	public static void renderMinimapRect(Client client, Graphics2D graphics, Point center, Color color)
	{
		double angle = client.getCameraYawTarget() * Perspective.UNIT;
		double tileSize = client.getMinimapZoom();
		int x = (int) Math.round(center.getX() - tileSize / 2);
		int y = (int) Math.round(center.getY() - tileSize / 2);
		int width = (int) Math.round(tileSize);
		int height = (int) Math.round(tileSize);
		graphics.setColor(color);
		graphics.rotate(angle, center.getX(), center.getY());
		graphics.fillRect(x, y, width, height);
		graphics.rotate(-angle, center.getX(), center.getY());
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.drawMinimap || plugin.getPathfinder() == null)
		{
			return null;
		}

		// kewl: ONE call, and clip to the shape that was actually judged. Asking twice re-resolved the
		// widget and rebuilt the clip, so the shape checked for null was not necessarily the shape
		// drawn against -- and the shape is now rebuilt whenever the minimap MOVES OR RESIZES, which
		// is precisely the case this overlay has to follow.
		Shape minimapClipArea = plugin.getMinimapClipArea();
		if (minimapClipArea == null)
		{
			// Null means the shim will not vouch for the minimap rectangle this frame: the interface
			// is not built, the minimap is toggled off, or its bounds are still parent-relative. Every
			// one of those is "not this frame", never "draw at the origin".
			return null;
		}
		graphics.setClip(minimapClipArea);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

		java.util.List<PathStep> pathPoints = plugin.getPathfinder().getPath();
		Color pathColor = plugin.getPathColor();
		for (PathStep point : pathPoints)
		{
			int pathPoint = point.getPackedPosition();
			if (WorldPointUtil.unpackWorldPlane(pathPoint) != client.getTopLevelWorldView().getPlane())
			{
				continue;
			}

			drawOnMinimap(graphics, pathPoint, pathColor);
		}
		for (int target : plugin.getPathfinder().getTargets())
		{
			if (!pathPoints.isEmpty() && target != pathPoints.get(pathPoints.size() - 1).getPackedPosition())
			{
				drawOnMinimap(graphics, target, plugin.colourPathCalculating);
			}
		}

		return null;
	}

	private void drawOnMinimap(Graphics2D graphics, int location, Color color)
	{
		PrimitiveIntList points = WorldPointUtil.toLocalInstance(client, location);
		for (int i = 0; i < points.size(); i++)
		{
			LocalPoint lp = WorldPointUtil.toLocalPoint(client, points.get(i));

			if (lp == null)
			{
				continue;
			}

			Point posOnMinimap = Perspective.localToMinimap(client, lp);

			if (posOnMinimap == null)
			{
				continue;
			}

			renderMinimapRect(client, graphics, posOnMinimap, color);
		}
	}
}

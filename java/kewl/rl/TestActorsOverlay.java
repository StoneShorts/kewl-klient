// TestActors' overlay: hull, tile and name for every actor in range, through the same Actor and
// OverlayUtil calls a ported plugin would make.
package kewl.rl;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;

import javax.inject.Inject;

import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

class TestActorsOverlay extends Overlay
{
	private static final Color NPC_COLOUR = new Color(255, 210, 90);
	private static final Color PLAYER_COLOUR = new Color(120, 200, 255);
	private static final Color ME_COLOUR = new Color(120, 255, 140);

	private final Client client;
	private final TestActorsConfig config;

	@Inject
	private TestActorsOverlay(Client client, TestActorsConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return null;
		}
		WorldPoint here = me.getWorldLocation();
		int range = config.range();

		if (config.showNpcs())
		{
			for (NPC npc : client.getNpcs())
			{
				if (npc.getWorldLocation().distanceTo(here) > range)
				{
					continue;
				}
				String name = npc.getName();
				String label = name.isEmpty() ? "#" + npc.getId() : name;
				int lvl = npc.getCombatLevel();
				if (lvl > 0)
				{
					label += " (" + lvl + ")";
				}
				draw(graphics, npc, label, NPC_COLOUR);
			}
		}

		if (config.showPlayers())
		{
			for (Player p : client.getPlayers())
			{
				if (p.getWorldLocation().distanceTo(here) > range)
				{
					continue;
				}
				String name = p.getName();
				String label = name.isEmpty() ? "player#" + p.getId() : name;
				draw(graphics, p, label, p == me ? ME_COLOUR : PLAYER_COLOUR);
			}
		}
		return null;
	}

	private void draw(Graphics2D graphics, Actor actor, String label, Color colour)
	{
		if (config.drawHull())
		{
			Shape hull = actor.getConvexHull();
			if (hull != null)
			{
				OverlayUtil.renderPolygon(graphics, hull, colour);
			}
		}
		if (config.drawTile())
		{
			Polygon tile = actor.getCanvasTilePoly();
			if (tile != null)
			{
				OverlayUtil.renderPolygon(graphics, tile, colour);
			}
		}
		if (config.drawName())
		{
			Point at = actor.getCanvasTextLocation(graphics, label, actor.getLogicalHeight() + 40);
			if (at != null)
			{
				OverlayUtil.renderTextLocation(graphics, at, label, colour);
			}
		}
	}
}

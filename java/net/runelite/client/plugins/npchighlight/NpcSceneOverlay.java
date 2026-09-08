/*
 * Copyright (c) 2018, James Swindle <wilingua@gmail.com>
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
// The scene half of NPC Indicators, in the shape of upstream's NpcOverlayService.renderNpcOverlay.
// Every style is null-guarded: a shim piece that answers null degrades to "that style draws nothing"
// rather than an exception OverlayRenderer would log every frame.
//
// Heights: "tile" and "hull" come off the Actor at its OWN ground height. The true/south-west tile
// styles go through Perspective.getCanvasTileAreaPoly(client, lp, size), which samples the ground
// from the nearest entity to that tile (the NPC itself, for these) -- exact, and the reason the
// south-west styles use the area poly of size 1 rather than getCanvasTilePoly (which only knows the
// local player's height).
package net.runelite.client.plugins.npchighlight;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.Stroke;

import javax.inject.Inject;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.Text;

public class NpcSceneOverlay extends Overlay
{
	private final Client client;
	private final NpcIndicatorsPlugin plugin;
	private final NpcIndicatorsConfig config;

	@Inject
	NpcSceneOverlay(Client client, NpcIndicatorsPlugin plugin, NpcIndicatorsConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(PRIORITY_MED);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		graphics.setFont(FontManager.getRunescapeFont());
		for (NPC npc : client.getNpcs())
		{
			HighlightedNpc highlighted = plugin.highlight(npc);
			if (highlighted == null)
			{
				continue;
			}
			if (config.ignoreDeadNpcs() && npc.isDead())
			{
				continue;
			}
			renderNpcOverlay(graphics, npc, highlighted);
		}
		return null;
	}

	private void renderNpcOverlay(Graphics2D graphics, NPC actor, HighlightedNpc highlightedNpc)
	{
		NPCComposition npcComposition = actor.getTransformedComposition();
		if (npcComposition == null)
		{
			return;
		}
		final int size = Math.max(1, npcComposition.getSize());
		final Color borderColor = highlightedNpc.getHighlightColor();
		final Color fillColor = highlightedNpc.getFillColor();
		final Stroke stroke = new BasicStroke((float) Math.max(1d, highlightedNpc.getBorderWidth()));

		if (highlightedNpc.isHull())
		{
			Shape objectClickbox = actor.getConvexHull();
			if (objectClickbox != null)
			{
				OverlayUtil.renderPolygon(graphics, objectClickbox, borderColor, fillColor, stroke);
			}
		}

		if (highlightedNpc.isTile())
		{
			Polygon tilePoly = actor.getCanvasTilePoly();
			if (tilePoly != null)
			{
				OverlayUtil.renderPolygon(graphics, tilePoly, borderColor, fillColor, stroke);
			}
		}

		if (highlightedNpc.isTrueTile())
		{
			LocalPoint lp = LocalPoint.fromWorld(client, actor.getWorldLocation()); // centre of the SW true tile
			if (lp != null)
			{
				final LocalPoint centerLp = new LocalPoint(
					lp.getX() + Perspective.LOCAL_TILE_SIZE * (size - 1) / 2,
					lp.getY() + Perspective.LOCAL_TILE_SIZE * (size - 1) / 2);
				Polygon tilePoly = Perspective.getCanvasTileAreaPoly(client, centerLp, size);
				if (tilePoly != null)
				{
					OverlayUtil.renderPolygon(graphics, tilePoly, borderColor, fillColor, stroke);
				}
			}
		}

		if (highlightedNpc.isSwTile())
		{
			final LocalPoint lp = actor.getLocalLocation();
			final int x = lp.getX() - ((size - 1) * Perspective.LOCAL_TILE_SIZE / 2);
			final int y = lp.getY() - ((size - 1) * Perspective.LOCAL_TILE_SIZE / 2);
			// Upstream: getCanvasTilePoly(client, sw). The size-1 area poly is the same square, at the
			// NPC's ground height instead of the local player's (see the class comment).
			Polygon southWestTilePoly = Perspective.getCanvasTileAreaPoly(client, new LocalPoint(x, y), 1);
			if (southWestTilePoly != null)
			{
				OverlayUtil.renderPolygon(graphics, southWestTilePoly, borderColor, fillColor, stroke);
			}
		}

		if (highlightedNpc.isSwTrueTile())
		{
			LocalPoint lp = LocalPoint.fromWorld(client, actor.getWorldLocation());
			if (lp != null)
			{
				Polygon tilePoly = Perspective.getCanvasTileAreaPoly(client, lp, 1);
				if (tilePoly != null)
				{
					OverlayUtil.renderPolygon(graphics, tilePoly, borderColor, fillColor, stroke);
				}
			}
		}

		if (highlightedNpc.isName())
		{
			String npcName = Text.sanitize(actor.getName());
			// kewl: a nameless definition (transform NPCs whose child carries the name -- ids 5885 and
			// 6521 live, 2026-09-06) shows its id instead of nothing, like Test Actors does.
			if (npcName.isEmpty())
			{
				npcName = "#" + actor.getId();
			}
			if (!npcName.isEmpty())
			{
				Point textLocation = actor.getCanvasTextLocation(graphics, npcName, actor.getLogicalHeight() + 40);
				if (textLocation != null)
				{
					OverlayUtil.renderTextLocation(graphics, textLocation, npcName, borderColor);
				}
			}
		}
	}
}

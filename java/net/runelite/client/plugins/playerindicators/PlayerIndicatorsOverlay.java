/*
 * Copyright (c) 2018, Tomas Slusny <slusnucky@gmail.com>
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
// The scene half, upstream's renderPlayerOverlay minus the friend/clan-rank icons (no sprites, no
// lists), plus the hull/tile styles NPC Indicators has (a kewl extension -- see the config header;
// this plugin replaced the box the worked example kewl.plugins.PlayerVisuals drew).
//
// Everything here is positioned off the ACTOR, never off a widget: the hull is the prism at the
// player's own ground height (Actor.getConvexHull), the tile is the square under its rendered
// position at that same height (Actor.getCanvasTilePoly), the name is the feet point raised along the
// height axis. Widget positions are the one part of the shim that is still unverified, and no scene
// overlay depends on them.
//
// Text sits at getLogicalHeight()+40 above the feet (ABOVE_HEAD) or at half the logical height
// (centre/right) -- and the logical height is the shim's tuning constant (Actor.logicalHeight), so
// the centre/right positions are approximate until it has been measured live.
package net.runelite.client.plugins.playerindicators;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.Stroke;

import javax.inject.Inject;

import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.Text;

public class PlayerIndicatorsOverlay extends Overlay
{
	private static final int ACTOR_OVERHEAD_TEXT_MARGIN = 40;
	private static final int ACTOR_HORIZONTAL_TEXT_MARGIN = 10;

	private final PlayerIndicatorsService playerIndicatorsService;
	private final PlayerIndicatorsConfig config;

	@Inject
	private PlayerIndicatorsOverlay(PlayerIndicatorsConfig config, PlayerIndicatorsService playerIndicatorsService)
	{
		this.config = config;
		this.playerIndicatorsService = playerIndicatorsService;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(PRIORITY_MED);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		graphics.setFont(FontManager.getRunescapeFont());
		playerIndicatorsService.forEachPlayer((player, color) -> renderPlayerOverlay(graphics, player, color));
		return null;
	}

	private void renderPlayerOverlay(Graphics2D graphics, Player actor, Color color)
	{
		// Shapes first, so the name reads on top of its own fill.
		renderShapes(graphics, actor, color);

		final PlayerNameLocation drawPlayerNamesConfig = config.playerNamePosition();
		if (drawPlayerNamesConfig == PlayerNameLocation.DISABLED)
		{
			return;
		}

		final int zOffset;
		switch (drawPlayerNamesConfig)
		{
			case MODEL_CENTER:
			case MODEL_RIGHT:
				zOffset = actor.getLogicalHeight() / 2;
				break;
			default:
				zOffset = actor.getLogicalHeight() + ACTOR_OVERHEAD_TEXT_MARGIN;
		}

		// "" until the client yields the name (other players' names are not yet confirmed live):
		// nothing to draw, and nothing to draw it at.
		final String name = Text.sanitize(actor.getName());
		if (name.isEmpty())
		{
			return;
		}
		Point textLocation = actor.getCanvasTextLocation(graphics, name, zOffset);

		if (drawPlayerNamesConfig == PlayerNameLocation.MODEL_RIGHT)
		{
			textLocation = actor.getCanvasTextLocation(graphics, "", zOffset);
			if (textLocation == null)
			{
				return;
			}
			textLocation = new Point(textLocation.getX() + ACTOR_HORIZONTAL_TEXT_MARGIN, textLocation.getY());
		}

		if (textLocation == null)
		{
			return;
		}

		OverlayUtil.renderTextLocation(graphics, textLocation, name, color);
	}

	/**
	 * The hull and tile styles. The fill is the player's own highlight colour at the configured alpha,
	 * so own and others stay told apart without a second colour setting each. Both shapes are
	 * null-guarded: off screen (or fewer than three hull corners projected) means "draws nothing" this
	 * frame, not an exception the renderer would log every frame.
	 */
	private void renderShapes(Graphics2D graphics, Player actor, Color color)
	{
		final boolean hull = config.highlightHull();
		final boolean tile = config.highlightTile();
		if (!hull && !tile)
		{
			return;
		}
		final Stroke stroke = new BasicStroke((float) Math.max(1d, config.borderWidth()));
		final int alpha = Math.max(0, Math.min(255, config.fillOpacity()));
		final Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);

		if (hull)
		{
			Shape convexHull = actor.getConvexHull();
			if (convexHull != null)
			{
				OverlayUtil.renderPolygon(graphics, convexHull, color, fill, stroke);
			}
		}
		if (tile)
		{
			Polygon tilePoly = actor.getCanvasTilePoly();
			if (tilePoly != null)
			{
				OverlayUtil.renderPolygon(graphics, tilePoly, color, fill, stroke);
			}
		}
	}
}

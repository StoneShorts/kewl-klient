/*
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
// Shim port of net.runelite.client.ui.overlay.OverlayUtil, cut to the actor/shape/text helpers
// ported plugins call. renderTileOverlay(TileObject) and the sprite/clickbox helpers are omitted --
// there is no TileObject or sprite access in the shim yet.
package net.runelite.client.ui.overlay;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.image.BufferedImage;

import net.runelite.api.Actor;
import net.runelite.api.Point;

public class OverlayUtil
{
	private OverlayUtil()
	{
	}

	public static void renderPolygon(Graphics2D graphics, Shape poly, Color color)
	{
		renderPolygon(graphics, poly, color, new BasicStroke(2));
	}

	public static void renderPolygon(Graphics2D graphics, Shape poly, Color color, Stroke borderStroke)
	{
		renderPolygon(graphics, poly, color, new Color(0, 0, 0, 50), borderStroke);
	}

	public static void renderPolygon(Graphics2D graphics, Shape poly, Color color, Color fillColor, Stroke borderStroke)
	{
		if (poly == null)
		{
			return;
		}
		graphics.setColor(color);
		final Stroke originalStroke = graphics.getStroke();
		graphics.setStroke(borderStroke);
		graphics.draw(poly);
		graphics.setColor(fillColor);
		graphics.fill(poly);
		graphics.setStroke(originalStroke);
	}

	public static void renderMinimapLocation(Graphics2D graphics, Point mini, Color color)
	{
		graphics.setColor(Color.BLACK);
		graphics.fillOval(mini.getX() - 2, mini.getY() - 2 + 1, 4, 4);
		graphics.setColor(color);
		graphics.fillOval(mini.getX() - 2, mini.getY() - 2, 4, 4);
	}

	/** Upstream style: a black drop shadow one pixel down-right, then the text in {@code color}. */
	public static void renderTextLocation(Graphics2D graphics, Point txtLoc, String text, Color color)
	{
		if (txtLoc == null || text == null)
		{
			return;
		}
		int x = txtLoc.getX();
		int y = txtLoc.getY();

		graphics.setColor(Color.BLACK);
		graphics.drawString(text, x + 1, y + 1);

		graphics.setColor(color);
		graphics.drawString(text, x, y);
	}

	public static void renderImageLocation(Graphics2D graphics, Point imgLoc, BufferedImage image)
	{
		if (imgLoc == null || image == null)
		{
			return;
		}
		graphics.drawImage(image, imgLoc.getX(), imgLoc.getY(), null);
	}

	/**
	 * Upstream shape: the actor's tile polygon plus {@code text} floated above its head
	 * (getLogicalHeight() + 40 fine units, the same offset upstream uses).
	 */
	public static void renderActorOverlay(Graphics2D graphics, Actor actor, String text, Color color)
	{
		Polygon poly = actor.getCanvasTilePoly();
		if (poly != null)
		{
			renderPolygon(graphics, poly, color);
		}

		Point textLocation = actor.getCanvasTextLocation(graphics, text, actor.getLogicalHeight() + 40);
		if (textLocation != null)
		{
			renderTextLocation(graphics, textLocation, text, color);
		}
	}

	public static void setGraphicProperties(Graphics2D graphics)
	{
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
	}
}

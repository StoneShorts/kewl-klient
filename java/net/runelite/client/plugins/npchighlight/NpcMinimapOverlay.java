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
// PARTLY APPROXIMATED: Perspective.localToMinimap is upstream's maths and the camera YAW it rotates
// by is now derived from the client's own projection (Perspective.yawFromScreenBasis, 2026-09-06),
// so dots turn with the camera. Only the SCALE is still a constant (4.0 px/tile, vanilla's value):
// the minimap is a raster the client rasterises itself, not part of the 3D viewport the projection
// natives describe, so no pair of projected points can measure its zoom -- see
// Perspective.minimapScaleNote and ShimSupport's Client.getMinimapZoom entry. The same caveat
// Shortest Path's minimap overlay accepts. Off by default (upstream's default too).
//
// Whether anything is drawn at all is Perspective.localToMinimap's call: it refuses while the minimap
// widget's rectangle is unusable and lifts by itself once it is not (re-checked 2026-09-06 after the
// widget native was fixed; the log line names which of the two failures it is).
package net.runelite.client.plugins.npchighlight;

import java.awt.Dimension;
import java.awt.Graphics2D;

import javax.inject.Inject;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.api.Perspective;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.Text;

public class NpcMinimapOverlay extends Overlay
{
	private final Client client;
	private final NpcIndicatorsPlugin plugin;

	@Inject
	NpcMinimapOverlay(Client client, NpcIndicatorsPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		graphics.setFont(FontManager.getRunescapeSmallFont());
		for (NPC npc : client.getNpcs())
		{
			HighlightedNpc highlighted = plugin.highlight(npc);
			if (highlighted == null || !highlighted.isNameOnMinimap())
			{
				continue;
			}
			Point minimapLocation = Perspective.localToMinimap(client, npc.getLocalLocation());
			if (minimapLocation == null)
			{
				continue;
			}
			OverlayUtil.renderMinimapLocation(graphics, minimapLocation, highlighted.getHighlightColor());
			String name = Text.sanitize(npc.getName());
			if (name.isEmpty())
			{
				name = "#" + npc.getId(); // same fallback the scene overlay uses for a nameless definition
			}
			if (!name.isEmpty())
			{
				Point textLocation = new Point(minimapLocation.getX(), minimapLocation.getY() - 5);
				OverlayUtil.renderTextLocation(graphics, textLocation, name, highlighted.getHighlightColor());
			}
		}
		return null;
	}
}

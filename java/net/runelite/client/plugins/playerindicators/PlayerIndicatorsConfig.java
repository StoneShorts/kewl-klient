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
// Ported from RuneLite's Player Indicators (net.runelite.client.plugins.playerindicators) onto the
// kewl shim. Group and key names are upstream's.
//
// OMITTED (not stubbed), each naming what it waits on -- on this shim every other player is an
// "other", because no membership list is readable:
//   drawPartyMemberNames / partyMemberColor (234,123,91)      no party service
//   drawFriendNames / friendNameColor (0,200,83)              friends list unreadable
//   drawFriendsChatMemberNames / friendsChatMemberColor (170,0,255)
//                                                            friends-chat list unreadable
//   drawClanMemberNames / clanChatMemberColor (36,15,171)     clan list unreadable
//   drawTeamMemberNames / teamMemberColor (19,110,247)        team cape needs the appearance read
//   colorPlayerMenu / clanMenuIcons                           the game menu is unreadable; no rank sprites
//
// Upstream defaults draw NOTHING (own and others both Disabled). kewl turns both on (2026-09-06),
// because this plugin is the client's player visuals now -- see the "Render style" section below,
// which is the other kewl extension here.
package net.runelite.client.plugins.playerindicators;

import java.awt.Color;

import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(PlayerIndicatorsConfig.GROUP)
public interface PlayerIndicatorsConfig extends Config
{
	String GROUP = "playerindicators";

	@ConfigSection(
		name = "Highlight Options",
		description = "Toggle highlighted players by type (self, others) and choose their highlight colors",
		position = 99
	)
	String highlightSection = "section";

	// kewl extension, not in upstream: upstream's Player Indicators draws a NAME and nothing else,
	// because RuneLite has separate plugins for the rest. Here this plugin IS the client's player
	// visuals -- it replaces the worked example kewl.plugins.PlayerVisuals, which drew a box -- so it
	// grew the same two render styles NPC Indicators has, drawn the same way (Actor.getConvexHull /
	// Actor.getCanvasTilePoly: the actor's own position and its own ground height, never a widget).
	@ConfigSection(
		name = "Render style",
		description = "What to draw over a highlighted player (kewl extension; upstream draws only the name)",
		position = 98
	)
	String renderStyleSection = "renderStyleSection";

	@ConfigItem(
		position = 0,
		keyName = "highlightHull",
		name = "Highlight hull",
		description = "Draw the player's hull. "
			+ "(Shim: an approximate prism around the model -- no model access on this build)",
		section = renderStyleSection
	)
	default boolean highlightHull()
	{
		return true;
	}

	@ConfigItem(
		position = 1,
		keyName = "highlightTile",
		name = "Highlight tile",
		description = "Draw the tile the player is rendered on",
		section = renderStyleSection
	)
	default boolean highlightTile()
	{
		return false;
	}

	// One opacity rather than a fill colour per player type: the fill is the player's OWN highlight
	// colour at this alpha, so own and others stay told apart by the one colour each already has.
	@Range(min = 0, max = 255)
	@ConfigItem(
		position = 2,
		keyName = "fillOpacity",
		name = "Fill opacity",
		description = "How solid the hull/tile fill is, 0-255. The fill takes the player's own color",
		section = renderStyleSection
	)
	default int fillOpacity()
	{
		return 20;
	}

	// @Range for the shim: kewl draws ints as sliders, and an unbounded width slider is unusable.
	@Range(min = 1, max = 8)
	@ConfigItem(
		position = 3,
		keyName = "borderWidth",
		name = "Border width",
		description = "Width of the hull/tile border",
		section = renderStyleSection
	)
	default double borderWidth()
	{
		return 2;
	}

	@ConfigItem(
		position = 0,
		keyName = "drawOwnName",
		name = "Highlight own player",
		description = "Highlight your own player. PvP: only on PvP/Deadman worlds "
			+ "(shim: world type is not readable yet, so PvP behaves as Disabled)",
		section = highlightSection
	)
	default HighlightSetting highlightOwnPlayer()
	{
		return HighlightSetting.ENABLED;      // kewl: on by default -- this is the client's player visuals now
	}

	@Alpha
	@ConfigItem(
		position = 1,
		keyName = "ownNameColor",
		name = "Own player",
		description = "Color of your own player",
		section = highlightSection
	)
	default Color getOwnPlayerColor()
	{
		return new Color(0, 184, 212);
	}

	@ConfigItem(
		position = 7,
		keyName = "drawNonClanMemberNames",
		name = "Highlight others",
		description = "Highlight other players. Shim: EVERY other player counts as an 'other' -- "
			+ "friends, clan, chat and party membership are not readable on this build",
		section = highlightSection
	)
	default HighlightSetting highlightOthers()
	{
		return HighlightSetting.ENABLED;      // kewl: on by default (upstream keeps others off)
	}

	@Alpha
	@ConfigItem(
		position = 8,
		keyName = "othersColor",
		name = "Others",
		description = "Color of other players' names",
		section = highlightSection
	)
	default Color getOthersColor()
	{
		return new Color(255, 0, 0);
	}

	@ConfigItem(
		position = 9,
		keyName = "playerNamePosition",
		name = "Name position",
		description = "Configures the position of drawn player names, or if they should be disabled "
			+ "(shim: centre/right sit on an approximate model height)"
	)
	default PlayerNameLocation playerNamePosition()
	{
		return PlayerNameLocation.ABOVE_HEAD;
	}

	@ConfigItem(
		position = 10,
		keyName = "drawMinimapNames",
		name = "Draw names on minimap",
		description = "Configures whether or not minimap names for players with rendered names should be drawn "
			+ "(shim: turns with the camera; fixed zoom -- the minimap scale is not readable)"
	)
	default boolean drawMinimapNames()
	{
		return false;
	}
}

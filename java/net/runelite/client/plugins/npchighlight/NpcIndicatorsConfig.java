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
// Ported from RuneLite's NPC Indicators (net.runelite.client.plugins.npchighlight) onto the kewl
// shim. Group and key names are upstream's, so a real RuneLite profile lines up.
//
// OMITTED (not stubbed -- a dead control in the panel would read as broken), each naming what it
// waits on:
//   highlightOutline / outlineFeather   no model access (no ModelOutlineRenderer; hull is a prism)
//   highlightMenuNames / deadNpcMenuColor
//                                       the game's own right-click menu is unreadable (DO_ACTION 0)
//   showRespawnTimer                    needs Actor.isDead() (no health/dead offset): without it every
//                                       walk-off-screen despawn would start a bogus timer
//   Tag / Untag / Tag-All / Tag-Color menu actions and per-name highlightcolor_ entries
//                                       menu actions cannot be added to the game's menu (see
//                                       kewl.rl.MenuPopup for the phase-2 approximation)
package net.runelite.client.plugins.npchighlight;

import java.awt.Color;

import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(NpcIndicatorsConfig.GROUP)
public interface NpcIndicatorsConfig extends Config
{
	String GROUP = "npcindicators";

	@ConfigSection(
		name = "Render style",
		description = "The render style of NPC highlighting",
		position = 0
	)
	String renderStyleSection = "renderStyleSection";

	@ConfigItem(
		position = 0,
		keyName = "highlightHull",
		name = "Highlight hull",
		description = "Configures whether or not NPC should be highlighted by hull. "
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
		description = "Configures whether or not NPC should be highlighted by tile",
		section = renderStyleSection
	)
	default boolean highlightTile()
	{
		return false;
	}

	@ConfigItem(
		position = 2,
		keyName = "highlightTrueTile",
		name = "Highlight true tile",
		description = "Configures whether or not NPC should be highlighted by true tile (server-side location)",
		section = renderStyleSection
	)
	default boolean highlightTrueTile()
	{
		return false;
	}

	@ConfigItem(
		position = 3,
		keyName = "highlightSouthWestTile",
		name = "Highlight south west tile",
		description = "Configures whether or not NPC should be highlighted by south western tile",
		section = renderStyleSection
	)
	default boolean highlightSouthWestTile()
	{
		return false;
	}

	@ConfigItem(
		position = 4,
		keyName = "highlightSouthWestTrueTile",
		name = "Highlight south west true tile",
		description = "Configures whether or not NPC should be highlighted by south western true tile (server-side location)",
		section = renderStyleSection
	)
	default boolean highlightSouthWestTrueTile()
	{
		return false;
	}

	@ConfigItem(
		position = 5,
		keyName = "highlightName",
		name = "Highlight name",
		description = "Configures whether or not NPC name should be highlighted "
			+ "(shim: draws nothing while the client yields no NPC names)",
		section = renderStyleSection
	)
	default boolean highlightName()
	{
		return true;
	}

	// kewl extension, not in upstream: RuneLite only ever highlights the names on the list, which
	// draws nothing on a fresh install. Here the plugin doubles as the client's default NPC visuals
	// (2026-09-06), so by default every NPC is drawn and the list narrows it down once you clear this.
	@ConfigItem(
		position = 0,
		keyName = "highlightAll",
		name = "Highlight every NPC",
		description = "Draw every NPC in range; off = only the names and ids on the list below"
	)
	default boolean highlightAll()
	{
		return true;
	}

	// kewl extension, not in upstream, and the reason "Highlight every NPC" above can say "in range":
	// upstream never needed a cap because it only ever draws the handful of NPCs on your list, while
	// the sweep mode draws whatever is loaded -- a 104x104 scene, i.e. a wall of hulls in a city. The
	// worked example this plugin replaces (kewl.plugins.NpcVisuals) capped at 20 tiles, so the sweep
	// does too. An NPC you asked for BY NAME OR ID is never range-limited: if you typed it, you want
	// it wherever it is.
	@Range(min = 1, max = 60)
	@ConfigItem(
		position = 1,
		keyName = "highlightAllRange",
		name = "Every-NPC range",
		description = "How far 'Highlight every NPC' reaches, in tiles. NPCs on the list below are always drawn"
	)
	default int highlightAllRange()
	{
		return 20;
	}

	@ConfigItem(
		position = 6,
		keyName = "ignoreDeadNpcs",
		name = "Ignore dead NPCs",
		description = "Configures whether or not NPCs should be highlighted if they are dead "
			+ "(shim: no death read yet, so every NPC counts as alive)",
		section = renderStyleSection
	)
	default boolean ignoreDeadNpcs()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		position = 7,
		keyName = "npcColor",
		name = "Highlight color",
		description = "Color of the NPC highlight border, menu, and text",
		section = renderStyleSection
	)
	default Color highlightColor()
	{
		return Color.CYAN;
	}

	@Alpha
	@ConfigItem(
		position = 8,
		keyName = "fillColor",
		name = "Fill color",
		description = "Color of the NPC highlight fill",
		section = renderStyleSection
	)
	default Color fillColor()
	{
		return new Color(0, 255, 255, 20);
	}

	// @Range added for the shim: kewl draws ints as sliders, and an unbounded width slider is unusable.
	@Range(min = 1, max = 8)
	@ConfigItem(
		position = 9,
		keyName = "borderWidth",
		name = "Border width",
		description = "Width of the highlighted NPC border",
		section = renderStyleSection
	)
	default double borderWidth()
	{
		return 2;
	}

	@ConfigItem(
		position = 14,
		keyName = "npcToHighlight",
		name = "NPCs to highlight",
		description = "List of NPC names to highlight. Format: (name), (name). Supports * and ?. "
			+ "Shim: a plain number matches an NPC type id (names read empty on this client build)."
	)
	default String getNpcToHighlight()
	{
		return "";
	}

	@ConfigItem(
		position = 15,
		keyName = "drawMinimapNames",
		name = "Draw names on minimap",
		description = "Configures whether or not NPC names should be drawn on the minimap "
			+ "(shim: turns with the camera; fixed zoom -- the minimap scale is not readable)",
		section = renderStyleSection
	)
	default boolean drawMinimapNames()
	{
		return false;
	}
}

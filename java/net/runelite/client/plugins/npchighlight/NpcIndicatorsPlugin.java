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
// Ported from RuneLite's NPC Indicators onto the kewl shim. What changed and why:
//
//  - No spawn-driven map. Upstream rebuilds a highlighted-NPC map on NpcSpawned/NpcDespawned because
//    its NPC list is live; kewl's client.getNpcs() is already a per-frame snapshot, so the overlay
//    asks highlight(npc) per NPC per frame and a (id, name)-keyed cache makes that one hash lookup.
//    The spawn/despawn events exist (kewl.rl.Events fires them) and are subscribed only for the
//    once-per-login "names readable" diagnostic.
//  - No menu work: Tag/Untag, menu recolouring and the respawn timer are omitted (see the config
//    header for what each waits on).
//  - Numeric entries in "NPCs to highlight" match the NPC type id: NPC names read "" on this client
//    build (DEF_NAME pending), and an id is the only handle a user has until they do.
//  - The status line (kewl.rl.StatusSource) and the [npchighlight] KEWL_LOG lines are the diagnostics
//    for the first live run; nothing here has been seen in-game.
package net.runelite.client.plugins.npchighlight;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.inject.Inject;

import com.google.inject.Provides;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;
import net.runelite.client.util.WildcardMatcher;

@PluginDescriptor(
	name = "NPC Indicators",
	description = "Highlight NPCs on-screen by name",
	tags = {"highlight", "minimap", "npcs", "overlay", "tags"}
)
public class NpcIndicatorsPlugin extends Plugin implements kewl.rl.StatusSource
{
	@Inject
	private Client client;

	@Inject
	private NpcIndicatorsConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private NpcSceneOverlay npcSceneOverlay;

	@Inject
	private NpcMinimapOverlay npcMinimapOverlay;

	/** The parsed "NPCs to highlight" list; replaced whole on every config change. */
	private volatile Filter filter = Filter.parse("");

	// Diagnostics for the panel status line and KEWL_LOG.
	private volatile String status = "";
	private boolean namesReadableLogged;
	private boolean namesEmptyLogged;
	private int loggedInTicksWithNpcs;

	@Provides
	NpcIndicatorsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(NpcIndicatorsConfig.class);
	}

	@Override
	protected void startUp()
	{
		rebuild();
		overlayManager.add(npcSceneOverlay);
		overlayManager.add(npcMinimapOverlay);
		Filter f = filter;
		System.out.println("[npchighlight] startUp: " + f.namePatterns.size() + " name patterns, "
			+ f.ids.size() + " id patterns, hull=" + config.highlightHull() + " tile=" + config.highlightTile()
			+ " trueTile=" + config.highlightTrueTile() + " name=" + config.highlightName()
			+ " minimap=" + config.drawMinimapNames() + " all=" + config.highlightAll()
			+ "@" + config.highlightAllRange() + " tiles");
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(npcSceneOverlay);
		overlayManager.remove(npcMinimapOverlay);
		filter = Filter.parse("");
		status = "";
		loggedInTicksWithNpcs = 0;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (!configChanged.getGroup().equals(NpcIndicatorsConfig.GROUP))
		{
			return;
		}
		rebuild();
		Filter f = filter;
		System.out.println("[npchighlight] config changed (" + configChanged.getKey() + "): "
			+ f.namePatterns.size() + " name patterns, " + f.ids.size() + " id patterns");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			// Re-arm the once-per-login diagnostics so a relog logs again.
			namesReadableLogged = false;
			namesEmptyLogged = false;
			loggedInTicksWithNpcs = 0;
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		// The first NPC to spawn with a readable name is the moment DEF_NAME started answering.
		noteName(event.getNpc());
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			status = "not logged in";
			return;
		}
		List<NPC> npcs = client.getNpcs();
		int highlighted = 0, named = 0;
		for (NPC npc : npcs)
		{
			if (!npc.getName().isEmpty())
			{
				named++;
				noteName(npc);
			}
			if (highlight(npc) != null)
			{
				highlighted++;
			}
		}
		Filter f = filter;
		status = npcs.size() + " npcs · " + highlighted + " highlighted · names "
			+ (named > 0 ? "ok (" + named + ")" : "empty") + " · " + (f.namePatterns.size() + f.ids.size())
			+ " patterns";

		// Ten logged-in ticks with NPCs present and not one name: say so once, so the log explains why
		// name entries match nothing while id entries still do.
		if (!npcs.isEmpty() && named == 0)
		{
			if (++loggedInTicksWithNpcs == 10 && !namesEmptyLogged)
			{
				namesEmptyLogged = true;
				System.out.println("[npchighlight] all " + npcs.size()
					+ " NPC names empty (DEF_NAME pending); id patterns still match");
			}
		}
		else
		{
			loggedInTicksWithNpcs = 0;
		}
	}

	private void noteName(NPC npc)
	{
		if (namesReadableLogged || npc == null)
		{
			return;
		}
		String name = npc.getName();
		if (!name.isEmpty())
		{
			namesReadableLogged = true;
			System.out.println("[npchighlight] NPC names readable: '" + Text.sanitize(name) + "' id " + npc.getId());
		}
	}

	/** Re-parse the list; the match cache goes with it (the old answers are for the old patterns). */
	private void rebuild()
	{
		filter = Filter.parse(config.getNpcToHighlight());
	}

	/**
	 * The overlay's per-NPC question, upstream's HighlightedNpc as the answer. Null when the NPC is
	 * neither on the list nor inside the sweep mode's range. Called per NPC per frame; the filter's
	 * cache keeps the list side to a hash lookup.
	 */
	public HighlightedNpc highlight(NPC npc)
	{
		if (npc == null)
		{
			return null;
		}
		if (!filter.matches(npc.getId(), npc.getName()))
		{
			// Not on the list, so only the sweep mode draws it -- and only inside its range. The list
			// itself is never range-limited (see NpcIndicatorsConfig.highlightAllRange).
			if (!config.highlightAll() || !withinRange(npc, config.highlightAllRange()))
			{
				return null;
			}
		}
		return HighlightedNpc.builder()
			.npc(npc)
			.highlightColor(config.highlightColor())
			.fillColor(config.fillColor())
			.hull(config.highlightHull())
			.tile(config.highlightTile())
			.trueTile(config.highlightTrueTile())
			.swTile(config.highlightSouthWestTile())
			.swTrueTile(config.highlightSouthWestTrueTile())
			.name(config.highlightName())
			.nameOnMinimap(config.drawMinimapNames())
			.borderWidth(config.borderWidth())
			.build();
	}

	/** True when {@code npc} is within {@code tiles} of the local player; false when there is none. */
	private boolean withinRange(NPC npc, int tiles)
	{
		Player me = client.getLocalPlayer();
		return me != null && withinRange(me.getWorldLocation(), npc.getWorldLocation(), tiles);
	}

	/**
	 * Chebyshev tile distance, the unit every other range in this client uses (kewl.api.Game.distanceTo
	 * and WorldPoint.distanceTo2D are both max(|dx|,|dy|)). Pure -- unit-tested. Across planes
	 * WorldPoint.distanceTo answers Integer.MAX_VALUE, i.e. out of range, which is the right answer;
	 * note that every actor reports the LOCAL PLAYER's plane on this build (see Actor.getWorldLocation),
	 * so that branch cannot fire yet.
	 */
	public static boolean withinRange(WorldPoint from, WorldPoint to, int tiles)
	{
		return from != null && to != null && from.distanceTo(to) <= tiles;
	}

	@Override
	public String status()
	{
		return status;
	}

	/**
	 * The parsed highlight list: wildcard name patterns plus (shim extension) plain NPC type ids, with
	 * a per-(id, name) cache of answers. Pure Java, no Client -- unit-tested directly.
	 */
	public static final class Filter
	{
		final List<Pattern> namePatterns;
		final Set<Integer> ids;
		/** (id, name) -> matched. The name is part of the key so an answer cached while the name read
		 *  "" is recomputed once the client starts yielding names. Bounded by the distinct (id, name)
		 *  pairs seen, i.e. by the NPC types on screen, not by frames. */
		private final Map<String, Boolean> cache = new HashMap<>();

		private Filter(List<Pattern> namePatterns, Set<Integer> ids)
		{
			this.namePatterns = namePatterns;
			this.ids = ids;
		}

		public static Filter parse(String csv)
		{
			List<Pattern> patterns = new ArrayList<>();
			Set<Integer> ids = new HashSet<>();
			for (String entry : Text.fromCSV(csv))
			{
				if (isAllDigits(entry))
				{
					try
					{
						ids.add(Integer.parseInt(entry));
						continue;
					}
					catch (NumberFormatException e)
					{
						// too long for an int: treat as a name pattern, it matches nothing
					}
				}
				patterns.add(WildcardMatcher.compile(entry));
			}
			return new Filter(patterns, ids);
		}

		public boolean matches(int id, String name)
		{
			String n = name == null ? "" : name;
			String key = id + "\0" + n;
			Boolean hit = cache.get(key);
			if (hit == null)
			{
				hit = matches(namePatterns, ids, id, n);
				cache.put(key, hit);
			}
			return hit;
		}

		/** The uncached rule: an id on the list, or a non-empty sanitized name a pattern accepts. */
		public static boolean matches(List<Pattern> namePatterns, Set<Integer> ids, int id, String name)
		{
			if (ids.contains(id))
			{
				return true;
			}
			String clean = Text.sanitize(name);
			if (clean.isEmpty())
			{
				return false;
			}
			for (Pattern p : namePatterns)
			{
				if (p.matcher(clean).matches())
				{
					return true;
				}
			}
			return false;
		}

		public int nameCount()
		{
			return namePatterns.size();
		}

		public int idCount()
		{
			return ids.size();
		}

		int cacheSize()
		{
			return cache.size();
		}

		private static boolean isAllDigits(String s)
		{
			if (s.isEmpty())
			{
				return false;
			}
			for (int i = 0; i < s.length(); i++)
			{
				if (!Character.isDigit(s.charAt(i)))
				{
					return false;
				}
			}
			return true;
		}
	}
}

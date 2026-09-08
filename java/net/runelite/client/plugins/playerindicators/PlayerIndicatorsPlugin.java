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
// Ported from RuneLite's Player Indicators onto the kewl shim. Upstream's menu recolouring and
// clan-rank icons are gone (the game menu is unreadable, no rank sprites); what is left is the
// scene and minimap overlays over PlayerIndicatorsService. The status line (kewl.rl.StatusSource)
// and the [playerindicators] KEWL_LOG lines are the diagnostics for the first live run: other
// players' names (Natives.entityName(uid, true)) are NOT yet confirmed to read.
//
// Names are only ever logged as set/empty, never their text -- other players are other people.
package net.runelite.client.plugins.playerindicators;

import java.util.List;

import javax.inject.Inject;

import com.google.inject.Provides;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Player Indicators",
	description = "Highlight players on-screen and/or on the minimap",
	tags = {"highlight", "minimap", "overlay", "players"}
)
public class PlayerIndicatorsPlugin extends Plugin implements kewl.rl.StatusSource
{
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private PlayerIndicatorsConfig config;

	@Inject
	private PlayerIndicatorsOverlay playerIndicatorsOverlay;

	@Inject
	private PlayerIndicatorsMinimapOverlay playerIndicatorsMinimapOverlay;

	@Inject
	private PlayerIndicatorsService service;

	@Inject
	private Client client;

	private volatile String status = "";
	private boolean ownNameLogged;
	private boolean otherNamesLogged;
	private int loggedInTicksWithOthers;

	@Provides
	PlayerIndicatorsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PlayerIndicatorsConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(playerIndicatorsOverlay);
		overlayManager.add(playerIndicatorsMinimapOverlay);
		System.out.println("[playerindicators] startUp: own=" + config.highlightOwnPlayer()
			+ " others=" + config.highlightOthers() + " position=" + config.playerNamePosition()
			+ " minimap=" + config.drawMinimapNames() + " hull=" + config.highlightHull()
			+ " tile=" + config.highlightTile());
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(playerIndicatorsOverlay);
		overlayManager.remove(playerIndicatorsMinimapOverlay);
		status = "";
		loggedInTicksWithOthers = 0;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			ownNameLogged = false;
			otherNamesLogged = false;
			loggedInTicksWithOthers = 0;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			status = "not logged in";
			return;
		}
		Player me = client.getLocalPlayer();
		List<Player> players = client.getPlayers();
		int othersNamed = 0, others = 0;
		for (Player p : players)
		{
			if (p == me)
			{
				continue;
			}
			others++;
			if (!Text.sanitize(p.getName()).isEmpty())
			{
				othersNamed++;
			}
		}
		int[] drawn = {0};
		service.forEachPlayer((p, c) -> drawn[0]++);

		String ownName = me == null ? "" : Text.sanitize(me.getName());
		status = players.size() + " players · " + drawn[0] + " drawn · own " + (ownName.isEmpty() ? "empty" : "set")
			+ " (" + config.highlightOwnPlayer() + ") · others " + config.highlightOthers()
			+ " · names " + othersNamed + "/" + others;

		if (me != null && !ownNameLogged)
		{
			ownNameLogged = true;
			System.out.println("[playerindicators] own name " + (ownName.isEmpty() ? "empty" : "set"));
		}
		if (others > 0)
		{
			if (othersNamed > 0)
			{
				if (!otherNamesLogged)
				{
					otherNamesLogged = true;
					System.out.println("[playerindicators] other player names readable (" + othersNamed + "/" + others + ")");
				}
			}
			else if (++loggedInTicksWithOthers == 10 && !otherNamesLogged)
			{
				otherNamesLogged = true;
				System.out.println("[playerindicators] other player names empty after 10 ticks with " + others
					+ " players (entityName(uid, true) pending)");
			}
		}
	}

	@Override
	public String status()
	{
		return status;
	}
}

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
// Upstream's classifier, cut to the two classes the shim can tell apart: you, and everyone else.
// Friends / friends chat / clan / team / party all collapse into "others" because none of those
// lists is readable on this build (see the config header). The local player is compared by
// reference first (client.getPlayers() hands back the same object as getLocalPlayer()) and by
// sanitized name second, the way upstream does.
package net.runelite.client.plugins.playerindicators;

import java.awt.Color;
import java.util.function.BiConsumer;

import javax.inject.Inject;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.client.util.Text;

public class PlayerIndicatorsService
{
	private final Client client;
	private final PlayerIndicatorsConfig config;

	@Inject
	PlayerIndicatorsService(Client client, PlayerIndicatorsConfig config)
	{
		this.client = client;
		this.config = config;
	}

	public void forEachPlayer(final BiConsumer<Player, Color> consumer)
	{
		final boolean own = highlight(config.highlightOwnPlayer());
		final boolean others = highlight(config.highlightOthers());
		if (!own && !others)
		{
			return;
		}

		final Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}
		final String localName = Text.sanitize(localPlayer.getName());

		for (Player player : client.getPlayers())
		{
			if (player == null || player.getName() == null)
			{
				continue;
			}

			boolean isSelf = player == localPlayer
				|| (!localName.isEmpty() && localName.equals(Text.sanitize(player.getName())));
			if (isSelf)
			{
				if (own)
				{
					consumer.accept(player, config.getOwnPlayerColor());
				}
			}
			else if (others)
			{
				consumer.accept(player, config.getOthersColor());
			}
		}
	}

	/**
	 * ENABLED always; PVP only on a PvP/Deadman world. The shim's world type is empty until an env()
	 * native reads the world flags (ClientState.getWorldType), so PVP currently behaves as DISABLED.
	 * TODO(shim): wilderness detection (upstream also checks the PVP_SPEC_ORB varbit / wilderness
	 * level widget) once varbits are readable.
	 */
	boolean highlight(HighlightSetting setting)
	{
		switch (setting)
		{
			case ENABLED:
				return true;
			case PVP:
				return WorldType.isPvpWorld(client.getWorldType());
			case DISABLED:
			default:
				return false;
		}
	}
}

// Shim-only (not an upstream type): the registry that gives NPC and Player objects a stable identity
// across frames.
//
// kewl.api.Game rebuilds its Entity snapshots every frame -- new objects each time. RuneLite plugins
// need the opposite: the SAME NPC object from NpcSpawned to NpcDespawned, held in their own sets and
// compared by reference. This table bridges the two: keyed by (kind, uid), it re-points each actor at
// this frame's snapshot, creates actors for new uids, and drops the ones that left. NPC and player
// uids are separate keyspaces in the client (an NPC and a player can share a handle), hence two maps.
//
// The list-taking core (refreshFrom) has no native dependency so it can be unit-tested off Windows;
// refresh(frame) is the one-liner the shim calls.
package net.runelite.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import kewl.api.Entity;
import kewl.api.Game;

public final class ActorTable
{
	private ActorTable()
	{
	}

	/**
	 * Frames between name lookups for an actor whose name still reads "". While DEF_NAME reads ""
	 * (client-240-6 as of 2026-09-05), Entity.name() caches nothing and re-walks the whole entity
	 * registry on every call -- so an overlay calling getName() on every NPC every frame would be
	 * O(n^2) native work per frame. With the backoff each unnamed actor costs one walk per ~second.
	 */
	static final int NAME_RETRY_FRAMES = 30;

	/** Injectable for tests (counts calls); production goes through Entity's own name cache. */
	static Function<Entity, String> nameLookup = Entity::name;

	private static final Map<Integer, NPC> npcs = new HashMap<>();
	private static final Map<Integer, Player> others = new HashMap<>();
	private static List<NPC> npcList = Collections.emptyList();
	private static List<Player> playerList = Collections.emptyList();
	private static int frame = -1;
	/** One line per session for the local-duplicate filter above, not one per frame. */
	private static boolean loggedLocalDuplicate;

	/** Idempotent per frame token (kewl.KewlKlient.frame()). */
	public static void refresh(int frame)
	{
		if (frame == ActorTable.frame)
		{
			return;
		}
		// Belt and braces on the local player. Game.players() is supposed to exclude you (it drops the
		// PLAYER whose uid equals Game.me().uid()), and refreshFrom then puts you at the head of the
		// list from Client.localPlayer(). If those two uids ever disagree -- a stale local uid for a
		// frame after a spawn, say -- you land in the list TWICE, and since both copies carry your
		// name PlayerIndicatorsService calls both "self" and draws two name tags, one of them off the
		// character (seen live 2026-09-06). Filtering here makes the duplicate impossible whatever the
		// uids do, and says so once so the underlying disagreement is not silent.
		List<Entity> others = Game.players();
		final int meUid = Game.me().uid();
		if (Game.me().exists())
		{
			for (Entity e : others)
			{
				if (e.uid() == meUid)
				{
					List<Entity> filtered = new ArrayList<>(others.size());
					for (Entity o : others)
					{
						if (o.uid() != meUid) filtered.add(o);
					}
					if (!loggedLocalDuplicate)
					{
						loggedLocalDuplicate = true;
						System.out.println("[actors] the local player was in Game.players() too (uid "
								+ meUid + ") -- dropped, so it is drawn once");
					}
					others = filtered;
					break;
				}
			}
		}
		refreshFrom(Game.npcs(), others, Game.me().exists(), frame);
	}

	/**
	 * The testable core. {@code localPresent} decides whether the local Player heads the player list
	 * (upstream's client.getPlayers() includes you; kewl's Game.players() excludes you).
	 */
	static void refreshFrom(List<Entity> npcEntities, List<Entity> playerEntities, boolean localPresent, int frame)
	{
		if (frame == ActorTable.frame)
		{
			return;
		}
		ActorTable.frame = frame;

		List<NPC> nl = new ArrayList<>(npcEntities.size());
		for (Entity e : npcEntities)
		{
			NPC n = npcs.get(e.uid());
			// Same handle, different type: the game reused the uid for another NPC between two
			// frames. Upstream would have fired despawn + spawn; a new object gives plugins the same.
			if (n != null && n.last.id() != e.id())
			{
				n.present = false;
				n = null;
			}
			if (n == null)
			{
				n = new NPC(e.uid(), e);
				npcs.put(e.uid(), n);
			}
			n.last = e;
			n.present = true;
			n.seenFrame = frame;
			nl.add(n);
		}
		for (Iterator<NPC> it = npcs.values().iterator(); it.hasNext(); )
		{
			NPC n = it.next();
			if (n.seenFrame != frame)
			{
				n.present = false;
				it.remove();
			}
		}

		List<Player> pl = new ArrayList<>(playerEntities.size() + 1);
		Player local = Client.get().localPlayer();
		if (localPresent)
		{
			pl.add(local);
		}
		else
		{
			// The local Player is one object for the life of the client: forget the name across a
			// logout so a relog on another account cannot serve the previous one. Done here, every
			// absent frame, rather than in Player.rawName() which only runs when a plugin asks.
			local.name = "";
			local.nameRetryFrame = Integer.MIN_VALUE;
		}
		for (Entity e : playerEntities)
		{
			Player p = others.get(e.uid());
			if (p == null)
			{
				p = new Player(e.uid(), e);
				others.put(e.uid(), p);
			}
			p.last = e;
			p.present = true;
			p.seenFrame = frame;
			pl.add(p);
		}
		for (Iterator<Player> it = others.values().iterator(); it.hasNext(); )
		{
			Player p = it.next();
			if (p.seenFrame != frame)
			{
				p.present = false;
				it.remove();
			}
		}

		npcList = Collections.unmodifiableList(nl);
		playerList = Collections.unmodifiableList(pl);
	}

	/** Every NPC in this frame's snapshot, in the client's table order. */
	public static List<NPC> npcs()
	{
		return npcList;
	}

	/** Every player in this frame's snapshot, the local player first when present. */
	public static List<Player> players()
	{
		return playerList;
	}

	public static NPC npcByUid(int uid)
	{
		return npcs.get(uid);
	}

	/** Other players by handle; the local player is reached through Client.getLocalPlayer(). */
	public static Player playerByUid(int uid)
	{
		Player p = others.get(uid);
		if (p == null && Game.me().exists() && Game.me().uid() == uid)
		{
			return Client.get().localPlayer();
		}
		return p;
	}

	/** Which frame the table last refreshed on; -1 before the first. */
	public static int frame()
	{
		return frame;
	}

	/** Forget everything (tests, and logout if a caller wants a clean slate). */
	static void reset()
	{
		npcs.clear();
		others.clear();
		npcList = Collections.emptyList();
		playerList = Collections.emptyList();
		frame = -1;
	}

	// -- names --------------------------------------------------------------------------------------------

	/** Name of an entity-backed actor through the injectable lookup, with the retry backoff. */
	static String nameOf(Actor actor, Entity last)
	{
		return nameOf(actor, () -> last == null ? "" : nameLookup.apply(last));
	}

	/**
	 * The name policy: a non-empty name sticks for the actor's lifetime (a handle names one entity of
	 * a kind while on screen); an empty one is retried at most every NAME_RETRY_FRAMES frames.
	 */
	static String nameOf(Actor actor, Supplier<String> lookup)
	{
		if (!actor.name.isEmpty())
		{
			return actor.name;
		}
		if (actor.nameRetryFrame != Integer.MIN_VALUE && frame - actor.nameRetryFrame < NAME_RETRY_FRAMES)
		{
			return "";
		}
		actor.nameRetryFrame = frame;
		String s = lookup.get();
		actor.name = s == null ? "" : s;
		return actor.name;
	}
}

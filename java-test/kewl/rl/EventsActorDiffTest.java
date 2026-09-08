// The spawn/despawn diff: each NPC/Player object is announced once when it enters the table and once
// when it leaves, per Events instance (= per hosted plugin), with the same object in both events.
// Drives Events.diffActors with lists from ActorTable.refreshFrom so no native is touched.
package kewl.rl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import kewl.api.EntityTestSupport;
import net.runelite.api.ActorTable;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

public class EventsActorDiffTest
{
	private static java.lang.reflect.Method REFRESH;

	@Before
	public void reset() throws Exception
	{
		REFRESH = ActorTable.class.getDeclaredMethod("refreshFrom", List.class, List.class, boolean.class, int.class);
		REFRESH.setAccessible(true);
		reset(ActorTable.class);
	}

	@After
	public void restore() throws Exception
	{
		reset(ActorTable.class);
	}

	private static void reset(Class<?> table) throws Exception
	{
		java.lang.reflect.Method m = table.getDeclaredMethod("reset");
		m.setAccessible(true);
		m.invoke(null);
	}

	private static void refresh(List<kewl.api.Entity> npcs, List<kewl.api.Entity> players, boolean local, int frame)
		throws Exception
	{
		REFRESH.invoke(null, npcs, players, local, frame);
	}

	/** A subscriber in the shape a ported plugin uses: exact event classes, objects kept. */
	public static class Listener
	{
		final List<NPC> spawned = new ArrayList<>();
		final List<NPC> despawned = new ArrayList<>();
		final List<Player> pSpawned = new ArrayList<>();
		final List<Player> pDespawned = new ArrayList<>();

		@Subscribe
		public void onNpcSpawned(NpcSpawned e)
		{
			spawned.add(e.getNpc());
		}

		@Subscribe
		public void onNpcDespawned(NpcDespawned e)
		{
			despawned.add(e.getNpc());
		}

		@Subscribe
		public void onPlayerSpawned(PlayerSpawned e)
		{
			pSpawned.add(e.getPlayer());
		}

		@Subscribe
		public void onPlayerDespawned(PlayerDespawned e)
		{
			pDespawned.add(e.getPlayer());
		}
	}

	@Test
	public void npcIsAnnouncedOnceOnEntryAndOnceOnExit() throws Exception
	{
		EventBus bus = new EventBus();
		Listener l = new Listener();
		bus.register(l);
		Events events = new Events(bus);

		refresh(List.of(EntityTestSupport.npc(7, 100, 10, 10)), List.of(), false, 1);
		events.diffActors(ActorTable.npcs(), ActorTable.players());
		// Same frame content again: nothing new to say.
		refresh(List.of(EntityTestSupport.npc(7, 100, 10, 11)), List.of(), false, 2);
		events.diffActors(ActorTable.npcs(), ActorTable.players());
		assertEquals(1, l.spawned.size());
		assertEquals(0, l.despawned.size());

		refresh(List.of(), List.of(), false, 3);
		events.diffActors(ActorTable.npcs(), ActorTable.players());
		assertEquals(1, l.spawned.size());
		assertEquals(1, l.despawned.size());
		assertSame("the despawned object is the one that spawned", l.spawned.get(0), l.despawned.get(0));
		assertEquals(7, l.despawned.get(0).getIndex());
	}

	@Test
	public void uidReuseWithNewIdIsDespawnThenSpawn() throws Exception
	{
		EventBus bus = new EventBus();
		Listener l = new Listener();
		bus.register(l);
		Events events = new Events(bus);

		refresh(List.of(EntityTestSupport.npc(7, 100, 10, 10)), List.of(), false, 1);
		events.diffActors(ActorTable.npcs(), ActorTable.players());
		refresh(List.of(EntityTestSupport.npc(7, 200, 10, 10)), List.of(), false, 2);
		events.diffActors(ActorTable.npcs(), ActorTable.players());

		assertEquals(2, l.spawned.size());
		assertEquals(1, l.despawned.size());
		assertSame(l.spawned.get(0), l.despawned.get(0));
		assertEquals(200, l.spawned.get(1).getId());
	}

	@Test
	public void eachBusGetsItsOwnCompleteDiff() throws Exception
	{
		EventBus busA = new EventBus();
		EventBus busB = new EventBus();
		Listener a = new Listener();
		Listener b = new Listener();
		busA.register(a);
		busB.register(b);
		Events eventsA = new Events(busA);
		Events eventsB = new Events(busB);

		refresh(List.of(EntityTestSupport.npc(7, 100, 10, 10)), List.of(), false, 1);
		eventsA.diffActors(ActorTable.npcs(), ActorTable.players());
		eventsB.diffActors(ActorTable.npcs(), ActorTable.players());
		assertEquals(1, a.spawned.size());
		assertEquals(1, b.spawned.size());
		assertSame(a.spawned.get(0), b.spawned.get(0));
	}

	@Test
	public void playersIncludingTheLocalOneSpawnAndDespawn() throws Exception
	{
		EventBus bus = new EventBus();
		Listener l = new Listener();
		bus.register(l);
		Events events = new Events(bus);

		refresh(List.of(), List.of(EntityTestSupport.player(9, 12, 12)), true, 1);
		events.diffActors(ActorTable.npcs(), ActorTable.players());
		assertEquals(2, l.pSpawned.size());                      // you, then the other player

		refresh(List.of(), List.of(), false, 2);                 // logout: table empty
		events.diffActors(ActorTable.npcs(), ActorTable.players());
		assertEquals(2, l.pDespawned.size());
		assertEquals(0, l.despawned.size());
	}
}

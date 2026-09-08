// The shim's actor-surface smoke test, in the shape a Hub plugin is written -- and the live diagnosis
// tool for it: nothing here has been seen in-game, so it reports what it sees (status line, one
// [actors] KEWL_LOG probe per login) so the next run tells whether text heights, hulls and spawn
// events are right.
//
// What it exercises: client.getNpcs()/getPlayers(), NPC identity across ticks (spawn/despawn counts),
// Actor.getConvexHull/getCanvasTilePoly/getCanvasTextLocation, OverlayUtil, and the logical-height
// tuning knob (the "Hull height" slider feeds Actor.setLogicalHeight live).
package kewl.rl;

import java.awt.Polygon;
import java.awt.Shape;
import java.util.List;

import javax.inject.Inject;

import com.google.inject.Provides;

import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Test Actors",
	description = "Exercises the RuneLite shim's NPC/player actor surface"
)
public class TestActors extends net.runelite.client.plugins.Plugin implements StatusSource
{
	@Inject
	private Client client;

	@Inject
	private TestActorsConfig config;

	@Inject
	private TestActorsOverlay overlay;

	@Inject
	private OverlayManager overlayManager;

	private int npcSpawns, npcDespawns, playerSpawns, playerDespawns;
	private boolean probeLogged;
	private volatile String status = "";

	@Provides
	TestActorsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TestActorsConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		status = "";
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		npcSpawns++;
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		npcDespawns++;
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned event)
	{
		playerSpawns++;
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event)
	{
		playerDespawns++;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// The slider is the live knob for the prism height; push it every tick so a panel edit lands
		// without a restart and the status line reads the value back.
		Actor.setLogicalHeight(config.hullHeight());

		List<NPC> npcs = client.getNpcs();
		List<Player> players = client.getPlayers();
		int named = 0;
		for (NPC n : npcs)
		{
			if (!n.getName().isEmpty())
			{
				named++;
			}
		}
		Player me = client.getLocalPlayer();
		String mePos = "absent";
		if (me != null)
		{
			LocalPoint lp = me.getLocalLocation();
			mePos = "(" + lp.getX() + "," + lp.getY() + ",h" + kewl.api.Game.me().height() + ")";
		}
		status = "npcs=" + npcs.size() + " players=" + players.size() + " named=" + named + "/" + npcs.size()
			+ " spawn=+" + npcSpawns + "/-" + npcDespawns + " pspawn=+" + playerSpawns + "/-" + playerDespawns
			+ " me=" + mePos + " hull=" + Actor.logicalHeight();

		if (npcs.isEmpty())
		{
			probeLogged = false;          // logged out or empty scene: re-arm the probe for the next login
		}
		else if (!probeLogged)
		{
			probeLogged = true;
			logProbe(nearest(npcs));
		}
	}

	private NPC nearest(List<NPC> npcs)
	{
		NPC best = null;
		int bestDist = Integer.MAX_VALUE;
		for (NPC n : npcs)
		{
			int d = n.getWorldLocation().distanceTo(client.getLocalPlayer() == null
				? n.getWorldLocation() : client.getLocalPlayer().getWorldLocation());
			if (d < bestDist)
			{
				bestDist = d;
				best = n;
			}
		}
		return best;
	}

	/**
	 * One line per login for the nearest NPC, so the next KEWL_LOG says whether the feet/head pixels
	 * and the hull make sense: head should sit ~one model above feet; tilePoly null means off screen.
	 */
	private void logProbe(NPC n)
	{
		if (n == null)
		{
			return;
		}
		LocalPoint lp = n.getLocalLocation();
		int h = kewlHeight(n);
		java.awt.Point feet = kewl.api.Game.projectFine(lp.getX(), h, lp.getY());
		java.awt.Point head = kewl.api.Game.projectFine(lp.getX(), h - Actor.logicalHeight(), lp.getY());
		Polygon tile = n.getCanvasTilePoly();
		Shape hull = n.getConvexHull();
		int hullPts = hull instanceof Polygon p ? p.npoints : (hull == null ? 0 : -1);
		System.out.println("[actors] uid=" + n.getIndex() + " id=" + n.getId() + " name='" + n.getName()
			+ "' scene=(" + n.getWorldLocation().getX() + "," + n.getWorldLocation().getY() + " world)"
			+ " fine=(" + lp.getX() + "," + h + "," + lp.getY() + ")"
			+ " feet=" + pt(feet) + " head@+" + Actor.logicalHeight() + "=" + pt(head)
			+ " tilePoly=" + (tile == null ? "null" : "ok") + " hull=" + hullPts + " pts");
	}

	private static int kewlHeight(NPC n)
	{
		// The actor's own ground height; the package-private primitive is not reachable from here,
		// so read it back off the same snapshot the table holds.
		for (kewl.api.Entity e : kewl.api.Game.npcs())
		{
			if (e.uid() == n.getIndex())
			{
				return e.height();
			}
		}
		return kewl.api.Game.groundHeightGuess();
	}

	private static String pt(java.awt.Point p)
	{
		return p == null ? "null" : "(" + p.x + "," + p.y + ")";
	}

	@Override
	public String status()
	{
		return status;
	}
}

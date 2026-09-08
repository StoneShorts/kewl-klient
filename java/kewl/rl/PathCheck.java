// A path computation the user can actually see the result of.
//
// "Shortest Path should be able to calculate paths, I have no idea if it's working" -- in-game
// targeting needs a logged-in account, but the pathfinder itself does not: its collision map and
// transports are resources in the jar, and the client object only matters for transport gating
// (varbits, quests), which a not-logged-in shim client simply fails closed. So the debug tab runs
// the exact route the ported test suite asserts on -- Lumbridge to Varrock, on foot -- through the
// REAL PathfinderConfig + Pathfinder this plugin uses in-game, and prints tiles + milliseconds.
// A green line here means the 3,000-line pathfinding core is alive in the shipped client; anything
// the in-game UX then fails at is targeting, presentation or auto-walk, not the maths.
package kewl.rl;

import java.util.Set;

import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import shortestpath.ShortestPathConfig;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.Pathfinder;
import shortestpath.pathfinder.PathfinderConfig;

public final class PathCheck {

	private PathCheck() {}

	/** The result line shown in the debug tab; replaced by every run(). */
	private static String result = "(not run yet)";

	public static synchronized String result()
	{
		return result;
	}

	/** Run the check synchronously. Costs a few hundred ms of frame time -- the debug tab runs it once. */
	public static synchronized void run()
	{
		try
		{
			long t0 = System.nanoTime();

			// A throwaway config namespace: the self-check must not touch the plugin's real settings,
			// and the proxy needs SOME kewl Config to declare its settings into.
			ConfigManager configs = new ConfigManager(new EventBus(), new kewl.config.Config());
			ShortestPathConfig config = configs.getConfig(ShortestPathConfig.class);

			PathfinderConfig pathfinderConfig = new PathfinderConfig(Client.get(), config);
			pathfinderConfig.refresh();

			int lumbridge = WorldPointUtil.packWorldPoint(3222, 3219, 0);
			int varrock = WorldPointUtil.packWorldPoint(3213, 3424, 0);
			Pathfinder pathfinder = new Pathfinder(pathfinderConfig, lumbridge, Set.of(varrock));
			pathfinder.run();

			long ms = (System.nanoTime() - t0) / 1_000_000L;
			int tiles = pathfinder.getPath() == null ? 0 : pathfinder.getPath().size();
			result = tiles == 0
				? "FAILED: no path in " + ms + " ms"
				: "OK: " + tiles + " tiles in " + ms + " ms";
			System.out.println("[pathcheck] " + result);
		}
		catch (Throwable t)
		{
			result = "threw: " + t;
			System.out.println("[pathcheck] threw: " + t);
		}
	}
}

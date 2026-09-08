// Shim of net.runelite.client.ui.overlay.worldmap.WorldMapPointManager (BSD-2, RuneLite) -- the
// collection of markers plugins add (Shortest Path's target marker is the only user).
//
// RuneLite binds this as a Guice singleton and its world-map overlay renders the points; here
// kewl.rl.OverlayRenderer does the rendering, and it is outside the injector graph, so it cannot be
// handed the instance the injector built. The constructor therefore records itself and the renderer
// reads that instance back through get().
package net.runelite.client.ui.overlay.worldmap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class WorldMapPointManager
{
	private static WorldMapPointManager instance;

	private final List<WorldMapPoint> points = new ArrayList<>();

	public WorldMapPointManager()
	{
		instance = this;
	}

	/**
	 * The most recently built instance -- the one the injector injected into the plugins. Null until
	 * the first plugin is constructed.
	 */
	public static WorldMapPointManager get()
	{
		return instance;
	}

	public void add(WorldMapPoint point)
	{
		points.add(point);
	}

	public void removeIf(Predicate<WorldMapPoint> filter)
	{
		points.removeIf(filter);
	}

	public List<WorldMapPoint> getWorldMapPoints()
	{
		return points;
	}
}

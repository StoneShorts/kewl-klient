// Shim of net.runelite.api.WorldEntity (BSD-2, RuneLite) -- a named sub-world (e.g. a boat). Only
// used by the on-a-boat branch of WorldPointUtil.fromLocalInstance, which cannot trigger until
// instance views are readable.
package net.runelite.api;

import net.runelite.api.coords.LocalPoint;

public class WorldEntity
{
	private final int id;
	private final LocalPoint localLocation;

	WorldEntity(int id)
	{
		this.id = id;
		this.localLocation = new LocalPoint(0, 0);
	}

	public int getId()
	{
		return id;
	}

	public LocalPoint getLocalLocation()
	{
		return localLocation;
	}
}

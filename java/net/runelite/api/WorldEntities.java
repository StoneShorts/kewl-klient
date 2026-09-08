// Shim of net.runelite.api.WorldEntities (BSD-2, RuneLite) -- the sub-world registry. No instances
// exist yet, so byIndex always reports "not found" the same way upstream does (null).
package net.runelite.api;

public class WorldEntities
{
	static final WorldEntities EMPTY = new WorldEntities();

	public WorldEntity byIndex(int index)
	{
		ShimSupport.note("WorldEntities.byIndex", ShimSupport.Kind.NEEDS_OFFSET,
			"reads null for every index, upstream's \"no such sub-world\": boats and other world"
				+ " entities are not readable, so a path is always computed from the shore rather"
				+ " than from a moving deck");
		return null;
	}
}

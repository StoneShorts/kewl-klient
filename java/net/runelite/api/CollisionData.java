// Shim of net.runelite.api.CollisionData (BSD-2, RuneLite). Until collision flags are read from the
// game, one all-zero map per plane: no walls anywhere, which is the neutral default.
package net.runelite.api;

public class CollisionData
{
	static final CollisionData[] EMPTY = {
		new CollisionData(), new CollisionData(), new CollisionData(), new CollisionData()
	};

	private final int[][] flags = new int[Constants.SCENE_SIZE][Constants.SCENE_SIZE];

	public int[][] getFlags()
	{
		return flags;
	}
}

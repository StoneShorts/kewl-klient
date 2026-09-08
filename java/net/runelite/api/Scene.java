// Shim of net.runelite.api.Scene (BSD-2, RuneLite), cut to what LocalPoint and WorldArea call.
package net.runelite.api;

public class Scene
{
	static final Scene INSTANCE = new Scene();

	private static final Tile[][][] EMPTY_TILES = new Tile[Constants.MAX_Z][Constants.SCENE_SIZE][Constants.SCENE_SIZE];

	public int getBaseX()
	{
		return kewl.api.Game.sceneBaseX();
	}

	public int getBaseY()
	{
		return kewl.api.Game.sceneBaseY();
	}

	public boolean isInstance()
	{
		ShimSupport.note("Scene.isInstance", ShimSupport.Kind.NEEDS_OFFSET,
			"reads false ALWAYS: instance views (raids, the Inferno, boats, POH) are not readable, so"
				+ " inside one every world coordinate the shim reports is the raw scene coordinate"
				+ " rather than the template-mapped one -- positions inside an instance are wrong,"
				+ " not merely missing");
		return false;
	}

	public int[][][] getInstanceTemplateChunks()
	{
		ShimSupport.note("Scene.getInstanceTemplateChunks", ShimSupport.Kind.NEEDS_OFFSET,
			"reads an EMPTY chunk array -- the other half of Scene.isInstance");
		return new int[0][0][0];
	}

	public int getWorldViewId()
	{
		return WorldView.TOPLEVEL;
	}

	/** Tile objects are not readable yet; every slot is null, which callers already handle. */
	public Tile[][][] getTiles()
	{
		ShimSupport.note("Scene.getTiles", ShimSupport.Kind.NEEDS_OFFSET,
			"reads an array of NULLS: there is no scene tile-object offset, so WorldArea's"
				+ " line-of-sight helpers find no walls and treat every tile as open");
		return EMPTY_TILES;
	}
}

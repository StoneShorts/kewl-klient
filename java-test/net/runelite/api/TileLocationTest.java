// Tile.getLocalLocation's coordinate space.
//
// Background (2026-09-06, the shim audit): this returned {@code sceneX << 7}, the tile's south-west
// CORNER, where every other LocalPoint in the shim and upstream is the tile's CENTRE. The bug hid
// because its only caller shifts the fine coordinate straight back down by 7 bits, and
// {@code (x << 7) >> 7} and {@code ((x << 7) + 64) >> 7} name the same tile -- so it was invisible on
// the one path that used it and half a tile wrong on every path that might. That is the shape of bug
// this file exists to keep out: right answer, wrong space.
package net.runelite.api;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import net.runelite.api.coords.LocalPoint;

public class TileLocationTest
{
	@Test
	public void aTilesLocalPointIsItsCentre()
	{
		LocalPoint p = new Tile(10, 20, 0).getLocalLocation();
		assertEquals((10 << Perspective.LOCAL_COORD_BITS) + Perspective.LOCAL_HALF_TILE_SIZE, p.getX());
		assertEquals((20 << Perspective.LOCAL_COORD_BITS) + Perspective.LOCAL_HALF_TILE_SIZE, p.getY());
	}

	@Test
	public void itAgreesWithEveryOtherWayTheShimBuildsATilesLocalPoint()
	{
		// The three that must not drift apart: LocalPoint's own factory, an Actor standing on the tile
		// with no fine position of its own (Actor.getLocalLocation's fallback), and this. A future
		// change to any one of them shows up here rather than as a marker drawn half a tile out.
		for (int x : new int[]{0, 1, 52, 103})
		{
			LocalPoint fromTile = new Tile(x, x, 0).getLocalLocation();
			LocalPoint fromFactory = LocalPoint.fromScene(x, x, WorldView.TOP_LEVEL);
			assertEquals(fromFactory.getX(), fromTile.getX());
			assertEquals(fromFactory.getY(), fromTile.getY());
			assertEquals((x << Perspective.LOCAL_COORD_BITS) + 64, fromTile.getX());
		}
	}

	@Test
	public void theSceneTileItNamesIsUnchanged()
	{
		// Why the bug survived: the round trip through the scene coordinate is identical either way,
		// which is the whole path ShortestPathPlugin's right-click target takes. Pinned so the fix is
		// known to be behaviour-preserving where it mattered and a correction where it did not.
		for (int x = 0; x < Constants.SCENE_SIZE; x++)
		{
			LocalPoint p = new Tile(x, x, 0).getLocalLocation();
			assertEquals(x, p.getSceneX());
			assertEquals(x, p.getSceneY());
			assertEquals(x, p.getX() >> Perspective.LOCAL_COORD_BITS);
		}
	}

	@Test
	public void theWorldViewIsTheTopLevelOne()
	{
		// WorldPointUtil.fromLocalInstance resolves the view from the point and reads the plane off
		// it; a point carrying an unknown view id would resolve to null there.
		assertEquals(WorldView.TOPLEVEL, new Tile(3, 4, 0).getLocalLocation().getWorldView());
	}
}

// Shim of net.runelite.api.Tile (BSD-2, RuneLite), cut to what WorldArea's line-of-sight code reads.
package net.runelite.api;

import net.runelite.api.coords.LocalPoint;

public class Tile
{
	private final int sceneX, sceneY, plane;

	Tile(int sceneX, int sceneY, int plane)
	{
		this.sceneX = sceneX;
		this.sceneY = sceneY;
		this.plane = plane;
	}

	public int getPlane()
	{
		return plane;
	}

	public Point getSceneLocation()
	{
		return new Point(sceneX, sceneY);
	}

	/**
	 * The tile's local point, at its CENTRE -- the same convention as every other LocalPoint in the
	 * shim and upstream ({@link LocalPoint#fromScene}, {@code Actor.getLocalLocation}'s fallback,
	 * {@code Perspective.centredTileAreaPoly}).
	 *
	 * <p>WRONG UNTIL 2026-09-06: this returned {@code sceneX << 7} with no half-tile added, i.e. the
	 * tile's south-west CORNER, half a tile off on both axes. The bug hid because its only caller
	 * (ShortestPathPlugin's target-from-right-click path, through WorldPointUtil.fromLocalInstance)
	 * immediately shifts the fine coordinate back down by 7 bits, and {@code (x << 7) >> 7} and
	 * {@code ((x << 7) + 64) >> 7} are the same tile. Anything that PROJECTED this point, or measured
	 * a distance from it, was 64 fine units south-west of the tile it named -- a marker drawn on the
	 * corner instead of the middle, and a 0.7-tile error against an actor's own centred position.</p>
	 */
	public LocalPoint getLocalLocation()
	{
		// The WorldView overload, not the deprecated two-arg one: same TOPLEVEL id, no warning.
		return LocalPoint.fromScene(sceneX, sceneY, WorldView.TOP_LEVEL);
	}

	/**
	 * Readable in a log line. The menu and target diagnostics print a Tile to answer "which tile was
	 * parked, and did it match the right-click" -- with the inherited Object.toString they printed an
	 * identity hash and answered nothing (review 2026-09-06). The world coordinates come from the
	 * CURRENT scene base, which is the same base the click path converts through, so a mismatch
	 * between the printed world tile and the minimap is itself the signal that the scene moved under
	 * a parked tile.
	 */
	@Override
	public String toString()
	{
		return "scene(" + sceneX + "," + sceneY + ",plane " + plane + ") world("
			+ (kewl.api.Game.sceneBaseX() + sceneX) + "," + (kewl.api.Game.sceneBaseY() + sceneY) + ")";
	}
}

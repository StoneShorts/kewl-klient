// The one refusal in the click-to-walk path that can be tested without a game: whether a projected
// point is somewhere we are willing to post a click. Everything else in Actions.walk needs the client
// (projection, game state, the canvas size), so this covers the pure half and AutoWalkTest covers the
// driver that consumes the answers.
package kewl.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ActionsClickGuardTest
{
	private static final int W = 800, H = 600;

	@Test
	public void acceptsAPointWellInsideTheCanvas()
	{
		assertTrue(Actions.clickable(400, 300, W, H));
	}

	@Test
	public void refusesPointsOutsideTheCanvas()
	{
		// A tile behind the player with the camera low projects to a negative y; a tile far to the
		// side projects past the right edge. Neither may be clamped into range -- clamping would walk
		// somewhere the caller never asked for.
		assertFalse(Actions.clickable(-1, 300, W, H));
		assertFalse(Actions.clickable(400, -20, W, H));
		assertFalse(Actions.clickable(W, 300, W, H));
		assertFalse(Actions.clickable(400, H, W, H));
	}

	@Test
	public void refusesTheEdgeItself()
	{
		// A tile projecting onto the border is half-clipped by the camera; the game's own hit test is
		// least likely to agree with ours there.
		assertFalse(Actions.clickable(0, 300, W, H));
		assertFalse(Actions.clickable(3, 300, W, H));
		assertTrue(Actions.clickable(4, 300, W, H));
		assertFalse(Actions.clickable(W - 1, 300, W, H));
		assertTrue(Actions.clickable(W - 5, 300, W, H));
	}

	/**
	 * A zero canvas is what {@code Input.target} reports when the window is gone or not yet found.
	 * Every point must be refused then, rather than clicking into a window we cannot measure.
	 */
	@Test
	public void refusesEverythingWhenTheCanvasSizeIsUnknown()
	{
		assertFalse(Actions.clickable(0, 0, 0, 0));
		assertFalse(Actions.clickable(400, 300, 0, 0));
		assertFalse(Actions.clickable(1, 1, 8, 8));
	}

	/**
	 * The humanised click is offset inside the destination tile in FINE units (128 to a tile) and
	 * projected from there, rather than by nudging the projected pixel -- a pixel offset is a
	 * different number of tiles at every camera angle, so it would walk somewhere else. This is the
	 * one number that decides whether the offset can leave the tile it was aimed at: a tile centre is
	 * at +64, so anything past +/-64 is the neighbouring tile and 44 keeps a clear margin.
	 */
	@Test
	public void aHumanisedClickCanNeverLeaveItsOwnTile()
	{
		assertTrue(Actions.MAX_TILE_JITTER < 64);
		assertEquals(0, Actions.clampJitter(0));
		assertEquals(20, Actions.clampJitter(20));
		assertEquals(-20, Actions.clampJitter(-20));
		assertEquals(Actions.MAX_TILE_JITTER, Actions.clampJitter(1000));
		assertEquals(-Actions.MAX_TILE_JITTER, Actions.clampJitter(-1000));
		assertEquals(Actions.MAX_TILE_JITTER, Actions.clampJitter(Actions.MAX_TILE_JITTER + 1));
	}
}

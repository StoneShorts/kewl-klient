package shortestpath;

import shortestpath.ShortestPathPlugin.SelectionSource;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The branch table behind "Set target": {@link ShortestPathPlugin#selectionSourceFor}.
 *
 * <p>Two user reports from 2026-09-07 live in this file, and they pull in opposite directions. Every
 * case below is one or the other, so that a change made for one cannot quietly re-open the other.</p>
 *
 * <p>REPORT ONE, "shortestpath doesnt do anything when setting a target". The table then made the
 * SCENE branch exclusive to "the map looks closed". The world-map interface group stays loaded once
 * the map has been opened even once, so {@code getWidget(MAP_CONTAINER)} answers non-null forever
 * after, and every right-click on the scene fell through to a map branch that cannot resolve on this
 * build. The resolver answered {@link WorldPointUtil#UNDEFINED}, and {@code setTarget(UNDEFINED)} is a
 * full teardown, so pressing "Set target" deleted the path instead of setting one.</p>
 *
 * <p>REPORT TWO, "when i open the worldmap and choose a location in it. its fucked up because it
 * chooses on the gamescreen not worldmap". The fix for report one fell back to the SCENE for
 * everything that was not a trusted map click -- so a click ON the open map, whose rectangle this
 * build cannot resolve, silently became a target at whatever scene tile was parked underneath.</p>
 *
 * <p>The table now asks three questions instead of two: (a) is the map on screen, (b) did the click
 * land on it, (c) can the point become a tile. The one that was missing is (b) being UNANSWERABLE --
 * the map is up and its rectangle does not resolve -- and that case now refuses instead of guessing.
 * The two invariants those reports leave behind:</p>
 *
 * <ol>
 *   <li>WITH THE MAP OFF SCREEN, a scene right-click always resolves. No map input of any kind may
 *       change that -- see {@link #mapOffScreenAlwaysResolvesFromTheSceneWhateverElseIsTrue}, which is
 *       the test that fails if a future edit re-couples them.</li>
 *   <li>WITH THE MAP ON SCREEN, a click that cannot be shown to be off the map never becomes a scene
 *       target. Refusing is visible and one keypress from recovery; a target the user did not choose
 *       is invisible and gets walked to.</li>
 * </ol>
 */
public class SelectedWorldPointBranchTest
{
	/** Stands in for "the rectangle is not a canvas rectangle", i.e. (b) is UNANSWERABLE. */
	private static final String NO_RECTANGLE =
		"the map container's rectangle is PARENT-RELATIVE (its parent chain did not reach a root)";

	/** Stands in for "(c) says no": the rectangle is fine, the scale is not. */
	private static final String NOT_INVERTIBLE =
		"the map scale is the PLACEHOLDER 4.0 px/tile, not a measurement";

	/** The escape-hatch argument, off in every case that does not name it. */
	private static final boolean NO_HATCH = false;
	private static final boolean HATCH = true;

	private static final boolean ON_MAP = true;
	private static final boolean OFF_MAP = false;
	private static final boolean SCENE_TILE = true;
	private static final boolean NO_SCENE_TILE = false;

	/**
	 * Case 1: the world map has never been opened, so the interface group is not loaded, or it has
	 * been opened and closed again so the container is hidden. Either way (a) says the map is not on
	 * screen and the scene tile answers. This is the only case upstream models.
	 */
	@Test
	public void mapOffScreenResolvesFromTheScene()
	{
		assertEquals(SelectionSource.SCENE, ShortestPathPlugin.selectionSourceFor(
			false, "the world map interface is not loaded", OFF_MAP, null, SCENE_TILE, NO_HATCH));
	}

	/**
	 * THE LOAD-BEARING CONSTRAINT, asserted exhaustively rather than by example.
	 *
	 * <p>With the map off screen, EVERY combination of every other input must resolve from the scene
	 * when a scene tile is parked, and must never produce a refusal. This is report one, and the
	 * property that makes it structural is that {@code mapOnScreen == false} returns from the first
	 * statement of the method, before any map input is read. If a future edit moves map reasoning
	 * above that early return -- or makes the presence question depend on the geometry, which is the
	 * specific way this could regress -- one of these 32 rows fails.</p>
	 */
	@Test
	public void mapOffScreenAlwaysResolvesFromTheSceneWhateverElseIsTrue()
	{
		String[] containment = {null, NO_RECTANGLE};
		String[] inversion = {null, NOT_INVERTIBLE};
		boolean[] flags = {false, true};

		for (String c : containment)
		{
			for (boolean inside : flags)
			{
				for (String i : inversion)
				{
					for (boolean scene : flags)
					{
						for (boolean hatch : flags)
						{
							String row = "containment=" + c + " inside=" + inside + " inversion=" + i
								+ " sceneTile=" + scene + " hatch=" + hatch;
							SelectionSource got =
								ShortestPathPlugin.selectionSourceFor(false, c, inside, i, scene, hatch);
							assertEquals("map off screen must ignore every map input: " + row,
								scene ? SelectionSource.SCENE : SelectionSource.NONE, got);
							if (scene)
							{
								assertFalse("a scene right-click with the map closed must never refuse: "
									+ row, ShortestPathPlugin.isRefusal(got));
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Case 2: the map is on screen and its rectangle resolves, but the user right-clicked the SCENE,
	 * outside the map. (b) is ANSWERED, and the answer is no, so the scene owns the click. Inverting a
	 * scene click through the map projection would set a target far from the tile pointed at, which
	 * with auto-walk on is the "it shows random places" half of report one.
	 */
	@Test
	public void mapOnScreenAndAnswerableButClickOutsideItResolvesFromTheScene()
	{
		assertEquals(SelectionSource.SCENE, ShortestPathPlugin.selectionSourceFor(
			true, null, OFF_MAP, null, SCENE_TILE, NO_HATCH));
		assertEquals("still the scene even when the map could not invert anything anyway",
			SelectionSource.SCENE, ShortestPathPlugin.selectionSourceFor(
				true, null, OFF_MAP, NOT_INVERTIBLE, SCENE_TILE, NO_HATCH));
	}

	/**
	 * REPORT TWO, the case this whole change exists for. The map is on screen, its rectangle does not
	 * resolve, so nobody can say whether the click landed on the map. The old table answered SCENE
	 * here and set a target the user never chose. It must refuse, and it must name question (b) while
	 * doing it.
	 */
	@Test
	public void mapOnScreenWithUnanswerableContainmentRefusesInsteadOfTakingTheScene()
	{
		SelectionSource got = ShortestPathPlugin.selectionSourceFor(
			true, NO_RECTANGLE, OFF_MAP, NOT_INVERTIBLE, SCENE_TILE, NO_HATCH);
		assertEquals(SelectionSource.MAP_CONTAINMENT_UNKNOWN, got);
		assertTrue("MAP_CONTAINMENT_UNKNOWN must read as a refusal to the caller",
			ShortestPathPlugin.isRefusal(got));
	}

	/**
	 * The refusal above is about the MAP, not about the absence of a scene tile, so it does not change
	 * when the scene has nothing parked and it does not change when the containment boolean happens to
	 * be true. A boolean computed against a rectangle we do not trust carries no information either
	 * way, and the table must not read it.
	 */
	@Test
	public void unanswerableContainmentIgnoresTheSceneTileAndTheMeaninglessBoolean()
	{
		for (boolean inside : new boolean[]{false, true})
		{
			for (boolean scene : new boolean[]{false, true})
			{
				assertEquals("inside=" + inside + " sceneTile=" + scene,
					SelectionSource.MAP_CONTAINMENT_UNKNOWN, ShortestPathPlugin.selectionSourceFor(
						true, NO_RECTANGLE, inside, NOT_INVERTIBLE, scene, NO_HATCH));
			}
		}
	}

	/**
	 * The escape hatch, and the only thing that reopens report two's failure mode. It exists because
	 * the refusal is keyed on the world-map container's hidden flag; if that ever reported a CLOSED
	 * map as open, "Set target" on the scene would stop working with no way back short of a rebuild.
	 * With the flag set, an unanswerable containment falls back to the scene exactly as it used to.
	 */
	@Test
	public void theEscapeHatchRestoresTheSceneFallbackUnderAnOpenMap()
	{
		assertEquals(SelectionSource.SCENE, ShortestPathPlugin.selectionSourceFor(
			true, NO_RECTANGLE, OFF_MAP, NOT_INVERTIBLE, SCENE_TILE, HATCH));
		assertEquals("with no scene tile there is still nothing to fall back to",
			SelectionSource.NONE, ShortestPathPlugin.selectionSourceFor(
				true, NO_RECTANGLE, OFF_MAP, NOT_INVERTIBLE, NO_SCENE_TILE, HATCH));
	}

	/**
	 * The hatch is scoped to the UNANSWERABLE case only. It is a fallback for "we cannot tell", not a
	 * licence to resolve a click that is KNOWN to be on the map from the scene underneath it.
	 */
	@Test
	public void theEscapeHatchDoesNotTouchAKnownMapClick()
	{
		assertEquals(SelectionSource.MAP_NOT_INVERTIBLE, ShortestPathPlugin.selectionSourceFor(
			true, null, ON_MAP, NOT_INVERTIBLE, SCENE_TILE, HATCH));
		assertEquals(SelectionSource.MAP, ShortestPathPlugin.selectionSourceFor(
			true, null, ON_MAP, null, SCENE_TILE, HATCH));
	}

	/**
	 * (a) yes, (b) yes, (c) no: the click really is on the map and the map cannot invert it -- today
	 * because the scale is a placeholder. A scene tile is parked and is deliberately not used: it is
	 * under the map, not where the user pointed.
	 */
	@Test
	public void aClickOnTheMapThatCannotBeInvertedRefusesRatherThanFallingToTheScene()
	{
		SelectionSource got = ShortestPathPlugin.selectionSourceFor(
			true, null, ON_MAP, NOT_INVERTIBLE, SCENE_TILE, NO_HATCH);
		assertEquals(SelectionSource.MAP_NOT_INVERTIBLE, got);
		assertTrue(ShortestPathPlugin.isRefusal(got));
	}

	/**
	 * Case 4: the map is on screen, the rectangle resolves, the click landed inside it and the point
	 * can be inverted. The only combination that may run {@code calculateMapPoint}.
	 */
	@Test
	public void mapOnScreenAnswerableInsideAndInvertibleTakesTheMapBranch()
	{
		assertEquals(SelectionSource.MAP, ShortestPathPlugin.selectionSourceFor(
			true, null, ON_MAP, null, SCENE_TILE, NO_HATCH));
		assertEquals("a map click does not need a scene tile to exist", SelectionSource.MAP,
			ShortestPathPlugin.selectionSourceFor(true, null, ON_MAP, null, NO_SCENE_TILE, NO_HATCH));
	}

	/** Every one of the MAP branch's three conjuncts is load-bearing; drop any one and it is not MAP. */
	@Test
	public void theMapBranchNeedsAllThreeQuestionsAnswered()
	{
		assertEquals("(a) no", SelectionSource.SCENE,
			ShortestPathPlugin.selectionSourceFor(false, null, ON_MAP, null, SCENE_TILE, NO_HATCH));
		assertEquals("(b) unanswerable", SelectionSource.MAP_CONTAINMENT_UNKNOWN,
			ShortestPathPlugin.selectionSourceFor(true, NO_RECTANGLE, ON_MAP, null, SCENE_TILE, NO_HATCH));
		assertEquals("(b) no", SelectionSource.SCENE,
			ShortestPathPlugin.selectionSourceFor(true, null, OFF_MAP, null, SCENE_TILE, NO_HATCH));
		assertEquals("(c) no", SelectionSource.MAP_NOT_INVERTIBLE,
			ShortestPathPlugin.selectionSourceFor(true, null, ON_MAP, NOT_INVERTIBLE, SCENE_TILE, NO_HATCH));
	}

	/**
	 * NONE stays what it always was: "there is genuinely nothing to resolve to". It is not a synonym
	 * for "the map refused" -- that is what the two MAP_ constants are for, and keeping them apart is
	 * what lets one log line say which question failed.
	 */
	@Test
	public void noneMeansNeitherSurfaceHasAnything()
	{
		assertEquals("map off screen, no scene tile", SelectionSource.NONE,
			ShortestPathPlugin.selectionSourceFor(false, "not loaded", OFF_MAP, null, NO_SCENE_TILE, NO_HATCH));
		assertEquals("map on screen, click provably off it, no scene tile", SelectionSource.NONE,
			ShortestPathPlugin.selectionSourceFor(true, null, OFF_MAP, null, NO_SCENE_TILE, NO_HATCH));
	}

	/** The caller's view of the enum: exactly the three refusal constants leave the target alone. */
	@Test
	public void refusalConstantsAreExactlyTheOnesThatResolveToNothing()
	{
		assertFalse(ShortestPathPlugin.isRefusal(SelectionSource.MAP));
		assertFalse(ShortestPathPlugin.isRefusal(SelectionSource.SCENE));
		assertTrue(ShortestPathPlugin.isRefusal(SelectionSource.MAP_CONTAINMENT_UNKNOWN));
		assertTrue(ShortestPathPlugin.isRefusal(SelectionSource.MAP_NOT_INVERTIBLE));
		assertTrue(ShortestPathPlugin.isRefusal(SelectionSource.NONE));
	}

	/**
	 * The whole table in one place, so a future edit that reorders the conditions has to change a
	 * table rather than a sentence. Rows are
	 * {mapOnScreen, containmentUnanswerable, clickInsideRect, notInvertible, sceneTileAvailable} with
	 * the escape hatch off throughout -- it has its own cases above.
	 */
	@Test
	public void wholeBranchTable()
	{
		boolean[][] inputs = {
			// onScreen, containmentUnanswerable, insideRect, notInvertible, sceneTile
			{false, true, false, true, true},    // map off screen, scene tile parked
			{false, true, false, true, false},   // map off screen, nothing selected
			{false, false, true, false, true},   // map off screen, every map input favourable
			{true, false, false, false, true},   // map up and answerable, click off the map
			{true, false, false, true, true},    // map up, click off the map, map could not invert
			{true, false, false, false, false},  // map up, click off the map, nothing selected
			{true, false, true, false, true},    // map up, click on the map, invertible
			{true, false, true, false, false},   // same, and no scene tile needed
			{true, false, true, true, true},     // map up, click on the map, NOT invertible
			{true, true, false, true, true},     // map up, containment UNANSWERABLE  <- report two
			{true, true, true, true, true},      // same, with a boolean that means nothing
			{true, true, false, true, false},    // same, and no scene tile either
		};
		SelectionSource[] expected = {
			SelectionSource.SCENE,
			SelectionSource.NONE,
			SelectionSource.SCENE,
			SelectionSource.SCENE,
			SelectionSource.SCENE,
			SelectionSource.NONE,
			SelectionSource.MAP,
			SelectionSource.MAP,
			SelectionSource.MAP_NOT_INVERTIBLE,
			SelectionSource.MAP_CONTAINMENT_UNKNOWN,
			SelectionSource.MAP_CONTAINMENT_UNKNOWN,
			SelectionSource.MAP_CONTAINMENT_UNKNOWN,
		};

		for (int i = 0; i < inputs.length; i++)
		{
			boolean[] row = inputs[i];
			assertEquals("row " + i + ": onScreen=" + row[0] + " containmentUnanswerable=" + row[1]
					+ " insideRect=" + row[2] + " notInvertible=" + row[3] + " sceneTile=" + row[4],
				expected[i],
				ShortestPathPlugin.selectionSourceFor(row[0], row[1] ? NO_RECTANGLE : null, row[2],
					row[3] ? NOT_INVERTIBLE : null, row[4], NO_HATCH));
		}
	}
}

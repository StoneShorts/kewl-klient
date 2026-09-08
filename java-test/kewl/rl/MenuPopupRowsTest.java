// The rows our right-click popup shows, with no game behind them.
//
// This exists because of one user report -- "when i click walk here its buggy af" -- and one fix:
// MenuPopup no longer shows a synthetic "Walk here" row. It could not: the game's OWN right-click
// menu is open underneath ours with a real "Walk here" in it, so ours was a duplicate, and ours
// walked by POSTING a click at a tile it re-derived, so a single user click could put two walk
// requests into the client at two different screen points.
//
// MenuPopup.open() itself needs the game (it reads the selected scene tile through a native), so the
// part that is pinned here is the pure one: which pending entries become rows. That is where the
// deleted row would have to come back through, and it is the filter that makes a row impossible
// unless a plugin actually attached an action to it.
package kewl.rl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;

public class MenuPopupRowsTest
{
	/** An entry shaped like the one ShortestPathPlugin contributes: RUNELITE type, with an action. */
	private static MenuEntry pluginRow(String option)
	{
		return new MenuEntry()
			.setOption(option)
			.setTarget("")
			.setType(MenuAction.RUNELITE)
			.onClick(e -> { });
	}

	/**
	 * The WALK entry open() builds to carry the MenuEntryAdded event. It is what plugins key off
	 * (ShortestPathPlugin gates "Set target" on MenuAction.WALK), and it must never be a row.
	 */
	private static MenuEntry walkHereEvent()
	{
		return new MenuEntry()
			.setOption("Walk here")
			.setType(MenuAction.WALK);
	}

	@Test
	public void aWalkHereWithNoActionIsNeverARow()
	{
		// The regression this file is named after: our "Walk here" duplicated the game's own and
		// posted a second click. Without an onClick it cannot become a row at all.
		assertTrue(MenuPopup.rowsFrom(List.of(walkHereEvent())).isEmpty());
	}

	@Test
	public void aWalkHereNeverSlipsInBesideRealPluginRows()
	{
		MenuEntry setTarget = pluginRow("Set target");
		MenuEntry clearPath = pluginRow("Clear path");

		// Even if the event-carrying entry somehow reached the pending list, the popup shows the two
		// plugin rows and nothing else -- and in the order the plugin created them.
		List<MenuEntry> rows = MenuPopup.rowsFrom(List.of(walkHereEvent(), setTarget, clearPath));

		assertEquals(2, rows.size());
		assertSame(setTarget, rows.get(0));
		assertSame(clearPath, rows.get(1));
	}

	/**
	 * An entry with no action is not a row whatever it is called. A row that only dismisses the popup
	 * is what the deleted "Walk here" was before it was given an onClick to make it walk, and giving
	 * it one is what created the double-click bug: neither shape is allowed back in.
	 */
	@Test
	public void anyActionlessEntryIsDropped()
	{
		MenuEntry examine = new MenuEntry().setOption("Examine").setType(MenuAction.EXAMINE_NPC);
		MenuEntry setTarget = pluginRow("Set target");

		List<MenuEntry> rows = MenuPopup.rowsFrom(List.of(examine, setTarget));

		assertEquals(List.of(setTarget), rows);
	}

	/** No pending entries at all: no rows, so open() leaves the popup closed and the game's menu alone. */
	@Test
	public void nothingPendingMeansNoPopup()
	{
		assertTrue(MenuPopup.rowsFrom(List.of()).isEmpty());
	}

	// -- the hit box ---------------------------------------------------------------------------------
	//
	// A 120x(2 rows) popup at (400, 300). Rows start 2px below the top, 22px each, so row 0 is
	// y 302..323 and row 1 is y 324..345.

	private static final int PX = 400, PY = 300, PW = 120, ROWS = 2;

	@Test
	public void aPointInsideTheBoxHitsItsRow()
	{
		assertEquals(0, MenuPopup.rowAt(PX + 5, PY + 3, PX, PY, PW, ROWS));
		assertEquals(0, MenuPopup.rowAt(PX + PW - 1, PY + 23, PX, PY, PW, ROWS));
		assertEquals(1, MenuPopup.rowAt(PX + 60, PY + 24, PX, PY, PW, ROWS));
		assertEquals(1, MenuPopup.rowAt(PX, PY + 45, PX, PY, PW, ROWS));
	}

	/**
	 * THE REGRESSION. rowAt had a left edge and no right one, so a click out in the middle of the
	 * game world -- hundreds of pixels clear of the popup, but at a row's height -- invoked that
	 * plugin row instead of dismissing the popup. Part of what "when i click walk here its buggy af"
	 * felt like, and the same class of fault as the duplicate "Walk here" row this file removed.
	 */
	@Test
	public void aPointRightOfTheBoxIsNotARow()
	{
		assertEquals(-1, MenuPopup.rowAt(PX + PW, PY + 5, PX, PY, PW, ROWS));
		assertEquals(-1, MenuPopup.rowAt(PX + 900, PY + 5, PX, PY, PW, ROWS));
		assertEquals(-1, MenuPopup.rowAt(PX + 900, PY + 30, PX, PY, PW, ROWS));
	}

	@Test
	public void pointsOutsideTheOtherThreeEdgesAreNotRows()
	{
		assertEquals(-1, MenuPopup.rowAt(PX - 1, PY + 5, PX, PY, PW, ROWS));   // left
		assertEquals(-1, MenuPopup.rowAt(PX + 5, PY + 1, PX, PY, PW, ROWS));   // above the first row
		assertEquals(-1, MenuPopup.rowAt(PX + 5, PY + 46, PX, PY, PW, ROWS));  // below the last row
	}

	/**
	 * Width 0 is "render() has not drawn this popup yet", i.e. there is nothing on screen to aim at.
	 * Nothing is hit, so the click dismisses -- which is what a click on no row already does.
	 */
	@Test
	public void anUndrawnPopupHasNoRows()
	{
		assertEquals(-1, MenuPopup.rowAt(PX + 5, PY + 5, PX, PY, 0, ROWS));
	}
}

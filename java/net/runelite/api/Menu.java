// Shim of net.runelite.api.Menu (BSD-2, RuneLite).
//
// Entries created here are held by the shim. kewl.rl.MenuPopup collects them on a right-click and
// draws them itself; a click fires the stored onClick. Creating one is harmless either way -- an
// entry nobody collects just queues.
//
// THEY ARE NOT INJECTED INTO THE GAME'S MENU, and this header said they would be ("the menu() native
// (Phase D) injects them into the client's own right-click menu") until 2026-09-07. That plan is not
// the one to build. The game's menu was derived from the binary that day -- see the header of
// kewl/rl/MenuPopup.java for the chain and the instruction behind every offset -- and the finding is
// that APPENDING to it is the riskiest way to get what it buys: an entry is 336 bytes of which 24 are
// understood, both its strings are SSO/heap unions, and the node vector reallocates out of a pool we
// do not own. The client's own AddEntry exists (it is bound to the client's Lua layer) but is not
// located. The derivable path is MIRROR + INVOKE: READ the game's entries into this list so plugins
// see real MenuEntryAdded events, and invoke a game row by calling the client's own executor
// (FUN_14037E990) rather than writing anything. Neither half is wired yet -- both need a native that
// can reach the client object, which Java cannot.
package net.runelite.api;

import java.util.ArrayList;
import java.util.List;

public class Menu
{
	public static final Menu INSTANCE = new Menu();

	private final List<MenuEntry> pending = new ArrayList<>();

	/** Entries waiting to be injected into the client's menu (or never, pre-Phase D). */
	public List<MenuEntry> getPending()
	{
		return pending;
	}

	public MenuEntry[] getMenuEntries()
	{
		return pending.toArray(new MenuEntry[0]);
	}

	public void setMenuEntries(MenuEntry[] entries)
	{
		pending.clear();
		for (MenuEntry e : entries)
		{
			pending.add(e);
		}
	}

	public MenuEntry createMenuEntry(int index)
	{
		MenuEntry entry = new MenuEntry();
		if (index >= 0 && index <= pending.size())
		{
			pending.add(index, entry);
		}
		else
		{
			pending.add(entry);
		}
		return entry;
	}

	public MenuEntry createMenuEntry(MenuAction action)
	{
		return createMenuEntry(-1).setType(action);
	}

	public void removeMenuEntry(MenuEntry entry)
	{
		pending.remove(entry);
	}

	public void clear()
	{
		pending.clear();
	}
}

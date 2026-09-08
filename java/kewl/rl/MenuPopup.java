// The right-click menu, drawn by us.
//
// We detect the right-click ourselves from the input() snapshot, fire the same MenuOpened/
// MenuEntryAdded events RuneLite would fire so plugins create their entries through the normal API,
// and then draw the RUNELITE-type entries ourselves. A click invokes the entry's onClick callback --
// exactly the path an injected menu would take, so plugins cannot tell the difference.
//
// WHERE THE GAME'S OWN MENU IS. This header used to say the menu struct was "still unread" and that
// "nothing can read where the game's menu is, never mind write into it". That is no longer true, and
// the correction is what answers the user's "cant we inject into the menuentry instead of creating
// another menu entry?". Derived 2026-09-07 from build/game/osclient.exe (client-240-6, the sha256
// offsets.hpp records); every instruction quoted here was re-read out of that binary with objdump.
// NONE OF IT IS VERIFIED AGAINST A RUNNING GAME -- see the probe at the bottom of this file, which is
// how it gets confirmed.
//
//   * THE MENU IS NOT AN INTERFACE. It is a native C++ object with vtable 0x140BC91B0 -- the vtable
//     client/offsets.hpp already names in its DO_ACTION block -- installed by its constructor
//     FUN_14037DFE0. It is not in the interface manager, so dumpWidgetText/loadedGroups/widgetObj can
//     never reach it and looking for it there is wasted time.
//   * minimenu = *(clientObj + 0x413400) + 0x140. The client constructor stores the holder with
//     `mov %rax,0x413400(%rdi)` at 0x14005738E, immediately after calling the holder constructor
//     0x140190600 at 0x140057384; that constructor builds the minimenu at `lea 0x140(%rsi),%rcx`
//     (0x140190646). Both re-read, byte for byte, 2026-09-07.
//   * ENTRIES hang off minimenu+0x140 as a {begin,end,capacity} vector (`lea 0x140(%r14),%rdx` in the
//     minimenu constructor, 0x14037E0B1) of 24-byte nodes {poolPtr, index, childrenPtr}; the tree
//     walker FUN_14038EEB0 counts them as ([+8]-[+0])/24 and follows node+0x10 for submenus.
//   * entry = *(poolPtr + 0x18) + index * 0x150. Seen twice, independently:
//     `imul $0x150,0x8(%rsi),%rbp; add 0x18(%rax),%rbp` at 0x14037EA30, and the same pair at
//     0x1403802B7. An entry is 336 bytes.
//   * INSIDE AN ENTRY, only these are understood: an NxtString at +0x50 (heap flag +0x67 -- 0x1403802C3
//     does exactly the decode client/game.hpp nxtString() implements: `lea 0x50(%rax),%rdx; movzbl
//     0x67(%rax),%eax; shr $0x7,%al; test $0x1,%al; ...; mov (%rdx),%rdx`), a second NxtString at
//     +0xB8 (0x140374DF0 / `movsbq 0x17(%rdx),%rcx` at 0x140374DFE), an int32 at +0x134 whose
//     "invalid" sentinel is 0x7FFFFFFE (`cmpl $0x7ffffffe,0x134(%rcx)` at 0x140381760), a byte at
//     +0x147 the client's own menu-entry swapper compares against 2 (0x140374DF7), and a byte at
//     +0x14D (0x14038176C). WHICH of +0x50 and +0xB8 is the option, the target, or the joined
//     coloured line is NOT settled statically -- that is question one for the probe.
//
// WHY WE STILL DRAW OUR OWN MENU, both reasons load-bearing and neither a guess:
//   1. NOTHING IN JAVA CAN REACH THAT CHAIN TODAY. Every step starts at the client object and no
//      native hands Java a client pointer -- kewl.Natives has widget()/entities()/peek()/findString()
//      and nothing else. Enumerating the game's rows needs a `dumpMenu` native in client/game.hpp,
//      the same shape of guarded pointer walk widgetObj() and forEachEntity() already are. That file
//      is not this agent's to edit; the probe below is what a person writing it should read first.
//   2. APPENDING AN ENTRY IS A BAD IDEA even once the chain is live: 24 of an entry's 336 bytes are
//      understood, both strings are SSO/heap unions, and the node vector reallocates out of a pool we
//      do not own. The client does ship its own AddEntry -- the Lua binding name cluster
//      "Cancel"/"Examine"/"AddEntry"/"Minimenu" sits at 0x140BED1E8 -- but it is NOT located, and a
//      hand-rolled 0x150-byte entry is a heap corruption that surfaces minutes later somewhere else.
//
// SO: THE ANSWER TO "CAN'T WE INJECT INTO THE MENU ENTRY?" IS THAT WE CAN DO BETTER, FOR LESS RISK --
// MIRROR + INVOKE, not inject:
//   * READ the game's entries and post a real MenuEntryAdded per row, then draw the game's rows and
//     ours as ONE list. That also fixes ShortestPathPlugin.java:757, whose own comment records its
//     FIND_CLOSEST branch as unreachable because the shim only ever posts the single synthetic WALK
//     entry open() builds below.
//   * INVOKE a game row by calling the client's OWN executor and writing nothing: FUN_14037E990
//     (rva 0x37E990, vtable 0x140BC91B0 slot 1 -- already a DO_ACTION candidate in offsets.hpp)
//     resolves an entry by index path, calls the real executor 0x14037E7A0 (the call at 0x14037EA51,
//     re-read 2026-09-07) and then flushes the pending-action record. That is the client's own "the
//     user clicked this row" path end to end. HOOK-AND-LOG IT BEFORE CALLING IT: reading memory wrong
//     shows a wrong number, calling the wrong address crashes the game.
//
// HAZARD FOR WHOEVER WIRES DO_ACTION, found on the way here and NOT yet fixed in offsets.hpp: the
// PENDING_ACTION_* block there describes +0x90..+0x9F as fields on the CLIENT OBJECT. They are not.
// FUN_140086030 is `mov 0x413400(%rcx),%rax; ret` and FUN_140086040 is `call FUN_140086030; add
// $0x7D0,%rax; ret`, and FUN_14037E990 reads the +0x9C sequence and the +0x9F pending flag off that
// pointer -- so the record lives at *(client + 0x413400) + 0x7D0 + 0x90..0x9F. client/game.hpp's
// doAction() passes clientObj() as argument 1, so setting DO_ACTION = 0x373F80 as it stands would
// make the client scribble on client+0x90..0x9F, the wrong object entirely.
//
// What this file still cannot do, and does not pretend to: show the game's own entries, and block the
// game from also handling the right-click. The game draws its own menu underneath ours. Assumed, not
// verified: we produce no click records, but the game also receives these clicks, and how NXT selects
// its own menu row from real mouse messages -- including whether it can pick a row that is underneath
// ours -- is unverified.
//
// WE DO NOT SHOW A "Walk here" ROW, and this file used to (removed 2026-09-06 after the user reported
// "when i click walk here its buggy af"). The reasoning for having one was that a popup reading
// "Set target" alone looks like a menu missing its first row. The reasoning against it is that the row
// was a lie that fired a second action:
//   * The game's OWN right-click menu is open underneath ours -- we cannot suppress it, the menu
//     struct is unread -- and it already has a real "Walk here" that walks by the game's own hit test.
//     Ours was a duplicate of an entry the user already had.
//   * Ours did not walk the way the game's does. It re-derived the tile and POSTED a synthetic click
//     at it, so one user click could put two walk requests into the client: the real click the game
//     handled through its own menu, and our posted one. Two requests at two slightly different
//     screen points is exactly "buggy af".
// So the popup now shows only what plugins actually contributed ("Set target", "Clear path", ...), and
// walking on a right-click is left entirely to the game, which was always better at it. There is no
// Actions call anywhere in this file any more.
//
// We still POST a WALK-type MenuEntryAdded when the popup opens: that event is not a row, it is the
// API contract. RuneLite's own menu emits "Walk here" first on a ground right-click and plugins hang
// their entries off it -- ShortestPathPlugin.onMenuEntryAdded gates "Set target" on
// event.getType() == MenuAction.WALK.getId(). Dropping the event would silently remove every plugin
// row; dropping the ROW is what removes the duplicate action.
//
// KNOWN AND NOT FIXED HERE: the user's left click on one of our rows is a real mouse click, so the
// game's menu (still open underneath) also gets it and selects whatever row of ITS OWN sits under the
// cursor. Suppressing that needs the menu struct, which is unread. Shift-gating the popup (below)
// keeps this off every plain right-click; the residue is that a shift-right-click followed by picking
// one of our rows may also trip a game entry. Nothing in this file can post a second walk any more,
// which was the half of the collision we owned.
package kewl.rl;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

import kewl.Natives;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.util.Text;

public class MenuPopup
{
	private static final int ROW_H = 22;
	private static final int PAD_X = 10;
	private static final Color BACKGROUND = new Color(30, 30, 34);
	private static final Color BORDER = new Color(70, 70, 78);
	private static final Color HOVER = new Color(60, 60, 68);
	private static final Color TEXT = new Color(230, 230, 235);

	private final EventBus eventBus;

	private boolean open;
	private int x, y;                        // popup top-left in canvas coordinates
	/**
	 * The width render() last drew this popup at, in canvas pixels; 0 while it has not drawn one.
	 * The hit test needs a right edge and tick() cannot compute one -- the widest row is a text
	 * measurement and text needs a Graphics2D. render() runs after tick() every frame and a row
	 * click needs a fresh left-button edge, which is at least one frame after the right-click that
	 * opened the popup, so by the time this is read it has been set. See {@link #rowAt(int, int,
	 * int, int, int, int)}.
	 */
	private int renderedW;
	private int hover = -1;
	private boolean prevRbutton, prevLbutton;
	/** Entries this popup shows: the RUNELITE-type entries created in response to MenuEntryAdded. */
	private List<MenuEntry> entries = List.of();
	/**
	 * The tile under the cursor when the menu opened -- the tile every row of this popup is ABOUT
	 * ("Set target" here, "Clear path" through here). Re-picking at click time would not do: a click
	 * on row N lands 20+ px below where the right-click did, and the tile picker's 100 px radius is
	 * happy to call that the neighbouring tile. A copy is parked in the ClientState when the menu
	 * opens (see open()), so WorldView.getSelectedSceneTile() answers with this tile for as long as
	 * the menu is up -- plugin row clicks resolve their world point through that same path.
	 *
	 * <p>Held here only to park and un-park it, and for the log line. This popup never acts on it
	 * itself: walking on a right-click belongs to the game's own menu (see the header).</p>
	 */
	private Tile openedTile;
	/**
	 * The scene base when the popup opened. {@link #openedTile} is SCENE-relative, and the scene
	 * re-centres while you walk: a popup left up across a re-centre would convert its parked (sx,sy)
	 * against the NEW base at click time and act on a tile dx/dy away from the one right-clicked
	 * (review 2026-09-06 -- auto-walk is on by default, so "Set target" would set off in the wrong
	 * direction). The popup is discarded when these stop matching rather than translated: the rows
	 * were built for a frame that is gone, and the game's own menu closes on movement too.
	 */
	private int openBaseX, openBaseY;
	/**
	 * Frames left before {@link GameMenuProbe} fires, or 0 for "not armed". A right-click arms it and
	 * it fires a few frames later, because the game builds its own menu on ITS thread in response to
	 * the same click: scanning in the frame we see the button edge in would scan a menu that does not
	 * exist yet. See {@link GameMenuProbe} for what it does and why it is off unless asked for.
	 */
	private int probeCountdown;

	public MenuPopup(EventBus eventBus)
	{
		this.eventBus = eventBus;
	}

	/** One frame of input edge detection and click dispatch. Called once per plugin per frame. */
	public void tick()
	{
		var state = Client.get().state();
		if (open && (kewl.api.Game.sceneBaseX() != openBaseX || kewl.api.Game.sceneBaseY() != openBaseY))
		{
			// The scene re-centred under an open popup: every parked scene tile now means a different
			// world tile (see openBaseX). Drop the popup rather than act on the wrong tile.
			close();
		}
		// isMenuOpen() for this frame: the game's own menu-open flag is unread, and the only menu
		// with entries in it right now is this one. Only an OPEN popup may raise the flag -- the
		// registry can hold several RlitePlugins, each with its own MenuPopup, and a closed one must
		// not clear the flag another plugin's open popup raised this frame. close() lowers it, and
		// only when it was actually open (an onDisable force-close of a not-open popup must not
		// kick the flag out from under a popup that belongs to a different plugin).
		if (open)
		{
			state.setPopupMenuOpen(true);
		}
		boolean rbutton = state.rbuttonDown();
		boolean lbutton = state.lbuttonDown();

		// The probe, before any of the popup logic: it is about the GAME's menu, not ours, so it must
		// run on a right-click whether or not we open anything (and whether or not shift was held).
		// Off unless KEWL_MENU_PROBE is set, once per session across every MenuPopup that exists.
		if (probeCountdown > 0 && --probeCountdown == 0)
		{
			GameMenuProbe.runOnce();
		}
		if (rbutton && !prevRbutton && GameMenuProbe.armed())
		{
			probeCountdown = GameMenuProbe.DELAY_FRAMES;
		}

		if (state.isMenuOpen() && !open)
		{
			// Another plugin's popup owns the open menu. Stand down for this frame -- opening a
			// second menu over it (and re-posting MenuOpened with our entries) would duplicate
			// rows -- but keep the edge trackers current so a fresh right-click after it closes
			// still opens ours.
			prevRbutton = rbutton;
			prevLbutton = lbutton;
			return;
		}

		if (open)
		{
			Point mouse = state.getMouseCanvasPosition();
			hover = rowAt(mouse);

			if (lbutton && !prevLbutton)
			{
				MenuEntry clicked = (hover >= 0 && hover < entries.size()) ? entries.get(hover) : null;
				if (clicked != null)
				{
					try
					{
						// Invoke while the menu is still marked open and the captured tile is still
						// parked: the plugin resolves its world point through
						// getSelectedSceneTile(), which answers with the tile from open() for
						// exactly this reason -- at click time the cursor sits on row N, tens of
						// pixels below where the right-click did.
						if (clicked.getOnClick() != null)
						{
							// Diagnostic trail for the live pass (2026-09-06): which row the click landed
							// on and what the plugin was handed -- the popup closing tells nothing.
							if (System.getenv("KEWL_LOG") != null)
							{
								System.out.println("[menu] click row " + hover + ": option=\"" + clicked.getOption()
									+ "\" target=\"" + clicked.getTarget() + "\" tile=" + openedTile);
							}
							clicked.getOnClick().accept(clicked);
						}
					}
					catch (Throwable t)
					{
						System.out.println("[menu] onClick threw: " + t);
					}
					close();
				}
				else
				{
					close();                     // a click anywhere else dismisses, like the game's menu
				}
			}
			else if (rbutton && !prevRbutton)
			{
				close();                         // a second right-click closes it; opening again waits for a fresh press after release
			}
			else
			{
				for (int vk : state.keyEdges())
				{
					if (vk == 27)                // VK_ESCAPE
					{
						close();
						break;
					}
				}
			}
		}
		// SHIFT-ONLY. Our popup cannot suppress the game's own right-click menu (the menu struct is
		// unread, see the header), so on a plain right-click BOTH open and ours sits on top of the
		// game's -- the user aims at the game's row and hits ours. Live 2026-09-06 that made the game
		// feel broken: the world map would not close, the minimap would not walk. Every entry any
		// hosted plugin contributes today is shift-gated anyway (shortestpath adds "Set target" only
		// while shift is held), so requiring shift costs nothing and gives the plain right-click back
		// to the game.
		else if (rbutton && !prevRbutton && overCanvas(state)
			&& state.isKeyPressed(net.runelite.api.KeyCode.KC_SHIFT))
		{
			// What the popup thought it saw. An ordinary left click opened it live (2026-09-06), so
			// this records the whole input row that justified the open.
			if (System.getenv("KEWL_LOG") != null)
			{
				Point m = state.getMouseCanvasPosition();
				System.out.println("[menu] opening: rbutton=" + rbutton + " lbutton=" + lbutton
						+ " shift=" + state.isKeyPressed(net.runelite.api.KeyCode.KC_SHIFT)
						+ " mouse=" + (m == null ? "null" : m.getX() + "," + m.getY()));
			}
			open(state);
		}

		prevRbutton = rbutton;
		prevLbutton = lbutton;
	}

	private void open(net.runelite.api.ClientState state)
	{
		Menu menu = Client.get().getMenu();
		entries = List.of();
		// Capture the tile here, while the right-click coordinates are still the true ones -- the
		// menu-open flag is not up yet, so the picker still scans. By the time a row click
		// dispatches, the cursor is on the row, and the picker would name a neighbour tile or
		// nothing. The same tile goes to the ClientState, where WorldView.getSelectedSceneTile()
		// reads it back from for every frame the menu is open (it also runs the plugin's own world
		// point resolution off that path).
		// Remember what was parked before us: every hosted plugin has its own MenuPopup and they all
		// see the same right-click in the same frame, so a popup that ends up with NO rows must hand
		// back whatever another popup parked, not null it (live 2026-09-06: Player Indicators' empty
		// popup wiped the tile Shortest Path's popup had just parked, and "Set target" resolved to
		// nothing every time).
		Tile parkedBefore = state.getMenuOpenedTile();
		openedTile = Client.get().getTopLevelWorldView().getSelectedSceneTile();
		state.setMenuOpenedTile(openedTile);

		// A fresh menu, then the same events the game's menu opening would fire. Both posts are
		// synchronous, so by the time open() returns, plugin-created entries are in pending.
		menu.setMenuEntries(new MenuEntry[0]);
		eventBus.post(new MenuOpened());

		// The WALK-type entry the game's own menu would have emitted first on a ground right-click.
		// It exists ONLY to carry the event -- plugins key off it (ShortestPathPlugin gates "Set
		// target" on event.getType() == MenuAction.WALK.getId()) -- and it is deliberately NOT given
		// an onClick and NOT added to the rows below. It is not in menu.getPending() either (a bare
		// `new MenuEntry()` never enters the menu; only Menu.createMenuEntry does), so the collection
		// loop cannot pick it up by accident. See the header: a "Walk here" row of ours duplicated
		// the game's own and posted a second click, which is the bug the user hit.
		MenuEntry walk = new MenuEntry()
			.setOption("Walk here")
			.setType(MenuAction.WALK);
		eventBus.post(new MenuEntryAdded(walk));

		List<MenuEntry> ours = rowsFrom(menu.getPending());
		if (ours.isEmpty())
		{
			// Nothing to show: undo the capture above, or a Tile from this scene stays parked in the
			// ClientState across scene reloads and world hops. close() is the normal un-parker, but
			// the popup is about to not open at all, so nothing else would clear it.
			state.setMenuOpenedTile(parkedBefore);
			openedTile = null;
			return;                              // nothing to show; stay closed
		}
		// Plugin rows only, in the order they were created. No synthetic "Walk here" is prepended:
		// the game's own menu is open underneath with a real one.
		entries = ours;

		Point mouse = state.getMouseCanvasPosition();
		x = mouse.getX();
		y = mouse.getY();
		open = true;
		openBaseX = kewl.api.Game.sceneBaseX();
		openBaseY = kewl.api.Game.sceneBaseY();
		// Raise the flag HERE, not on the next tick (review 2026-09-06). Every hosted plugin has its
		// own MenuPopup and they all tick in the same frame off the same right-click edge: with the
		// flag going up only on the following tick, a second popup that ticks after this one still
		// saw isMenuOpen()==false and opened on top of us. Both would then be open, and the next
		// left-click edge would be consumed twice -- the first close() nulls the parked tile, so the
		// second popup dispatches its row with nothing parked. The per-tick re-raise in tick() stays:
		// it is what keeps the flag up for as long as this popup lives.
		state.setPopupMenuOpen(true);
		hover = -1;
	}

	/**
	 * Which pending entries become rows: the ones a plugin made actionable by attaching an onClick.
	 *
	 * <p>Static and pure so the invariant the user's "walk here is buggy af" report cost us is
	 * testable with no game behind it (java-test/kewl/rl/MenuPopupRowsTest.java): an entry with no
	 * onClick is NOT a row. A row that does nothing when clicked would just dismiss the popup, and
	 * the one entry that used to be given an onClick purely so it would not do that -- our synthetic
	 * "Walk here" -- was duplicating the game's own entry and posting a second click for it. The
	 * WALK entry open() builds for the MenuEntryAdded event deliberately has no onClick and is not
	 * in the pending list, so it can never reach this filter, but the filter is the backstop.</p>
	 */
	static List<MenuEntry> rowsFrom(List<MenuEntry> pending)
	{
		List<MenuEntry> rows = new ArrayList<>();
		for (MenuEntry e : pending)
		{
			if (e.getOnClick() != null)
			{
				rows.add(e);
			}
		}
		return rows;
	}

	/**
	 * Dismiss the popup and clear everything that outlives a frame of it: the entries, the captured
	 * tile, and the ClientState flag and tile. Public because it is also called from outside the
	 * tick loop -- RlitePlugin.onDisable must force-close, or a popup left up when the plugin is
	 * switched off ticks no more and holds isMenuOpen() (and the stale captured tile) forever.
	 */
	public void close()
	{
		boolean wasOpen = open;
		open = false;
		entries = List.of();
		hover = -1;
		renderedW = 0;                       // nothing is drawn: the next popup gets its own width
		openedTile = null;
		// Lower the flag only if this popup is the one that raised it: with several RlitePlugins
		// alive, a force-close from onDisable of a NOT-open popup must not clear another plugin's
		// open popup's flag (its own next tick re-raises it, but the frame in between renders with
		// the tile-parking bypass silently off).
		if (wasOpen)
		{
			Client.get().state().setPopupMenuOpen(false);
			Client.get().state().setMenuOpenedTile(null);
		}
	}

	/** Draw the popup. Runs inside the plugin's render pass, in canvas coordinates. */
	public void render(Graphics2D g)
	{
		if (!open || entries.isEmpty())
		{
			return;
		}

		int[] view = Natives.viewport();
		if (view.length == 4)
		{
			// Keep the menu on the canvas: a menu hanging off the edge is unusable half off-screen.
			int w = entryWidth(g) + PAD_X * 2;
			int h = entries.size() * ROW_H + 4;
			if (x + w > view[2])
			{
				x = Math.max(0, view[2] - w);
			}
			if (y + h > view[3])
			{
				y = Math.max(0, view[3] - h);
			}
		}

		int w = entryWidth(g) + PAD_X * 2;
		int h = entries.size() * ROW_H + 4;
		renderedW = w;                       // the hit test's right edge: see rowAt

		g.setFont(g.getFont().deriveFont(Font.PLAIN, 13f));
		g.setColor(BACKGROUND);
		g.fillRect(x, y, w, h);
		g.setColor(BORDER);
		g.drawRect(x, y, w, h);

		if (hover >= 0 && hover < entries.size())
		{
			g.setColor(HOVER);
			g.fillRect(x + 1, y + 2 + hover * ROW_H, w - 2, ROW_H);
		}

		int ty = y + 2;
		for (MenuEntry e : entries)
		{
			// "option target", minus the colour tags the game's menu would render as colour -- our
			// plain popup has no coloured spans, so they would show as raw markup.
			String line = Text.removeTags(e.getOption() + " " + e.getTarget()).trim();
			g.setColor(TEXT);
			g.drawString(line, x + PAD_X, ty + ROW_H - 7);
			ty += ROW_H;
		}
	}

	private int entryWidth(Graphics2D g)
	{
		int w = 40;
		for (MenuEntry e : entries)
		{
			String line = Text.removeTags(e.getOption() + " " + e.getTarget()).trim();
			int tw = g.getFontMetrics(g.getFont().deriveFont(Font.PLAIN, 13f)).stringWidth(line);
			if (tw > w)
			{
				w = tw;
			}
		}
		return w;
	}

	private int rowAt(Point mouse)
	{
		if (mouse == null)
		{
			return -1;
		}
		return rowAt(mouse.getX(), mouse.getY(), x, y, renderedW, entries.size());
	}

	/**
	 * Which row a canvas point lands on, or -1 for "not on this popup". Pure and static so the hit
	 * box is tested rather than eyeballed (java-test/kewl/rl/MenuPopupRowsTest.java).
	 *
	 * <p>The RIGHT edge is the fix here (review 2026-09-06). This test used to be
	 * {@code mouseX < x -> miss} and nothing else, so it had a left edge, a top edge and a bottom
	 * edge but no right one: every point to the right of the popup, all the way across the canvas,
	 * was "on" whichever row shared its y band. A left click out in the middle of the game world at
	 * the popup's height therefore invoked a plugin row instead of dismissing the popup -- the same
	 * class of fault as the "Walk here" duplicate this file already removed, and part of what "when
	 * i click walk here its buggy af" felt like. The bound is the width the popup was last DRAWN at
	 * ({@link #renderedW}), because that is the only width that exists: the text metrics need a
	 * Graphics2D, which tick() does not have.</p>
	 *
	 * <p>{@code width <= 0} means render() has not run since this popup opened -- nothing is on
	 * screen to aim at -- so nothing is hit and the click dismisses, which is what a click on no row
	 * already does.</p>
	 */
	static int rowAt(int mouseX, int mouseY, int popupX, int popupY, int width, int rowCount)
	{
		if (width <= 0 || mouseX < popupX || mouseX >= popupX + width)
		{
			return -1;
		}
		int my = mouseY - popupY - 2;
		if (my < 0)
		{
			return -1;
		}
		int row = my / ROW_H;
		return row < rowCount ? row : -1;
	}

	/** The click must be inside the game canvas -- over the side panel it is somebody else's click. */
	private boolean overCanvas(net.runelite.api.ClientState state)
	{
		Point mouse = state.getMouseCanvasPosition();
		if (mouse == null)
		{
			return false;
		}
		int[] view = Natives.viewport();
		return view.length == 4
			&& mouse.getX() >= 0 && mouse.getX() < view[2]
			&& mouse.getY() >= 0 && mouse.getY() < view[3];
	}

	// ================================================================================================
	// The live probe for the game's own menu.
	// ================================================================================================

	/**
	 * Confirms -- or refutes -- the entry layout quoted in this file's header, against a running game,
	 * without a DLL change and without writing a byte.
	 *
	 * <p><b>Why this shape and not the obvious one.</b> The obvious probe walks
	 * {@code *(clientObj + 0x413400) + 0x140} and prints the entry vector. Java cannot: no native
	 * hands it a client pointer. So this comes in from the other end, through the two diagnosis
	 * natives that already ship. {@link Natives#findString} returns the ADDRESSES whose bytes equal a
	 * needle, and a menu entry's text is an NxtString at entry+0x50 which, for anything shorter than
	 * 24 bytes, is stored INLINE -- so a hit on "Cancel" while the menu is open is literally the
	 * address {@code entry + 0x50}, and {@link Natives#peek} reads the 336 bytes around it.</p>
	 *
	 * <p><b>The self-test is the point.</b> findString scans all read/write memory, so most hits are
	 * unrelated -- the JVM's own heap holds copies of these very strings (this class declares them).
	 * A hit only counts as an entry when the bytes at {@code hit - 0x50} decode, by the project's own
	 * NxtString rule, back to the needle we searched for. That single check is a real test of the
	 * derivation: it can only pass if the text really is an inline NxtString at +0x50 of something.
	 * If nothing passes, the header's +0x50 is wrong (or the string is heap-stored, or the menu was
	 * shut) and NOBODY should write a dumpMenu native against those numbers yet.</p>
	 *
	 * <p><b>What one run settles</b>, which is most of what a person needs before touching C++:
	 * whether the entry stride really is 0x150 (the neighbour walk below steps by it and the rows
	 * either come out as the menu you can see on screen or they do not); which of +0x50 and +0xB8 is
	 * the option, the target, or the joined coloured line; what +0x134, +0x147 and +0x14D hold per row
	 * kind; and, from the raw dump, where the rest of an entry's 336 bytes are.</p>
	 *
	 * <p><b>It is OFF unless asked for, and it runs once.</b> {@code findString} is a full scan of the
	 * process's writable memory and it is not free; a memory-scanning probe has crashed this game
	 * before. Set {@code KEWL_MENU_PROBE=1} to arm it (or {@code KEWL_MENU_PROBE=Attack,Trade} to
	 * search your own needles instead of the defaults), then right-click once in-game. It fires on
	 * that one right-click, prints to stdout, and disarms itself for the rest of the session.</p>
	 *
	 * <p>READ-ONLY. It calls findString and peek and nothing else -- there is no write path in this
	 * class and no call into game code.</p>
	 */
	static final class GameMenuProbe
	{
		// The layout under test. Every one of these is quoted with its instruction in this file's
		// header; they are DERIVED STATICALLY and this probe is what would make them measured.
		static final int ENTRY_STRIDE = 0x150;   // imul $0x150 at 0x14037EA30 and 0x1403802B7
		static final int ENTRY_TEXT   = 0x50;    // NxtString, heap flag at +0x67 (0x1403802C3)
		static final int ENTRY_TEXT2  = 0xB8;    // second NxtString, flag at +0xCF (0x140374DF0)
		static final int ENTRY_ID     = 0x134;   // int32, "invalid" sentinel 0x7FFFFFFE (0x140381760)
		static final int ENTRY_KIND   = 0x147;   // u8, compared against 2 by the swapper (0x140374DF7)
		static final int ENTRY_KIND2  = 0x14D;   // u8, compared against 0 at 0x14038176C
		/** An NxtString's flag byte, the same +0x17 client/game.hpp's nxtString() reads. */
		static final int NXT_FLAG     = 0x17;
		/** Sentinel the client itself tests +0x134 against before treating an entry as real. */
		static final int ID_INVALID   = 0x7FFFFFFE;

		/**
		 * Frames between the right-click and the scan. The game builds its menu on its own thread off
		 * the same click, so scanning immediately would scan a menu that is not there. Ten frames is
		 * roughly 150 ms at 60 fps -- long enough for the menu to exist, far shorter than the time
		 * anyone takes to pick a row, so the menu is still open when the scan runs.
		 */
		static final int DELAY_FRAMES = 10;

		/**
		 * Neighbouring pool slots dumped either side of a confirmed entry. Entries of one menu are
		 * consecutive indices into one pool ({@code entry = *(pool+0x18) + index*0x150}), so stepping
		 * by the stride should walk the rest of the rows on screen. That is the stride's test AND the
		 * cheapest way to see several row kinds at once; a wrong stride shows up as neighbours that
		 * decode to nothing.
		 */
		static final int NEIGHBOURS = 6;

		/** At most this many confirmed entries are expanded. Two is plenty and bounds the reads. */
		private static final int MAX_CONFIRMED = 2;

		/**
		 * Static, not per-instance: several RlitePlugins each own a MenuPopup and they all see the
		 * same right-click, so a per-instance flag would run the scan once per hosted plugin.
		 */
		private static boolean done;

		private GameMenuProbe() {}

		/** True while the probe is asked for and has not run yet. */
		static boolean armed()
		{
			return !done && System.getenv("KEWL_MENU_PROBE") != null;
		}

		/** Fire, at most once per session. Safe to call when not armed: it does nothing. */
		static void runOnce()
		{
			if (done)
			{
				return;
			}
			String cfg = System.getenv("KEWL_MENU_PROBE");
			if (cfg == null)
			{
				return;
			}
			done = true;                         // set BEFORE the scan: a throw must not re-arm it

			List<String> needles = needlesFrom(cfg);
			System.out.println("[menuprobe] read-only scan for the game's menu entries; needles="
				+ needles + ". Testing entry+0x50 (see MenuPopup's header). Nothing is written.");
			if (needles.isEmpty())
			{
				System.out.println("[menuprobe] no usable needle (findString refuses under 3 chars).");
				return;
			}
			try
			{
				for (String needle : needles)
				{
					scan(needle);
				}
			}
			catch (Throwable t)
			{
				// Including UnsatisfiedLinkError: an older DLL has no findString/peek. A probe must
				// never take a frame down with it.
				System.out.println("[menuprobe] aborted: " + t);
			}
			System.out.println("[menuprobe] done; it will not run again this session.");
		}

		/**
		 * The needles to search for. {@code "1"}, {@code "true"} or empty means the defaults; anything
		 * else is a comma-separated list, so a user who can SEE a row we did not guess ("Attack",
		 * "Trade with") can probe with its exact text.
		 *
		 * <p>The defaults are the two rows the client always builds ("Cancel" is its menu header's
		 * companion and "Examine" appears on nearly everything) plus "Walk here" for a ground click.
		 * They are deliberately plain: the game colours its targets with {@code <col=...>} markup, so
		 * a needle that spans the option AND the target would never match the stored bytes.</p>
		 */
		static List<String> needlesFrom(String cfg)
		{
			List<String> out = new ArrayList<>();
			String trimmed = cfg == null ? "" : cfg.trim();
			if (trimmed.isEmpty() || trimmed.equals("1") || trimmed.equalsIgnoreCase("true"))
			{
				out.add("Walk here");
				out.add("Examine");
				out.add("Cancel");
				return out;
			}
			for (String part : trimmed.split(","))
			{
				String needle = part.trim();
				// findString refuses a needle under three characters (it would match everywhere), and
				// 23 is the most an inline NxtString holds -- a longer one is stored on the heap, and
				// then the hit is the heap buffer rather than entry+0x50 and the self-test cannot pass.
				if (needle.length() >= 3 && needle.length() <= 23 && !out.contains(needle))
				{
					out.add(needle);
				}
			}
			return out;
		}

		private static void scan(String needle)
		{
			long[] hits = Natives.findString(needle);
			System.out.println("[menuprobe] \"" + needle + "\": " + hits.length + " address(es) in RW memory");
			int confirmed = 0;
			for (long hit : hits)
			{
				if (confirmed >= MAX_CONFIRMED)
				{
					break;
				}
				long entry = hit - ENTRY_TEXT;
				String hex = read(entry, ENTRY_STRIDE);
				// THE SELF-TEST. Most hits are somebody else's copy of the same characters -- the JVM
				// heap holds these very literals. A hit is an entry only if the bytes at hit-0x50
				// decode, by the NxtString rule, back to what we searched for.
				if (hex.isEmpty() || !needle.equals(inlineText(hex, ENTRY_TEXT)))
				{
					continue;
				}
				confirmed++;
				System.out.println("[menuprobe]   CONFIRMED entry 0x" + Long.toHexString(entry)
					+ " (text at 0x" + Long.toHexString(hit) + ")");
				describe("    ", hex);
				neighbours(entry);
			}
			if (confirmed == 0)
			{
				System.out.println("[menuprobe]   no hit had the entry shape. That means one of: the"
					+ " menu was not open when the scan ran; this text is stored on the heap rather"
					+ " than inline; or +0x50 is NOT where an entry keeps its text and the header's"
					+ " layout is wrong. Do not write a dumpMenu native against those offsets until"
					+ " some needle confirms.");
			}
		}

		/**
		 * The pool slots either side of a confirmed entry. A menu's rows are consecutive indices in
		 * one pool, so if the stride is right these are the other rows of the menu on screen -- which
		 * is simultaneously the stride's test and the fastest way to see how several row kinds fill
		 * +0x134 / +0x147 / +0x14D.
		 */
		private static void neighbours(long entry)
		{
			System.out.println("[menuprobe]     neighbours at +/- " + ENTRY_STRIDE + " (the stride's test:"
				+ " these should read back as the other rows of the menu on screen)");
			for (int k = -NEIGHBOURS; k <= NEIGHBOURS; k++)
			{
				if (k == 0)
				{
					continue;
				}
				long at = entry + (long) k * ENTRY_STRIDE;
				String hex = read(at, ENTRY_STRIDE);
				if (hex.isEmpty())
				{
					continue;                    // not mapped: the pool block ends here
				}
				String t1 = text(hex, ENTRY_TEXT);
				String t2 = text(hex, ENTRY_TEXT2);
				if (t1.isEmpty() && t2.isEmpty())
				{
					continue;                    // nothing string-shaped: not a live entry
				}
				System.out.println("[menuprobe]     [" + (k > 0 ? "+" : "") + k + "] 0x"
					+ Long.toHexString(at) + " +0x50=\"" + t1 + "\" +0xB8=\"" + t2 + "\""
					+ " +0x134=" + idText(hex) + " +0x147=" + hexByte(hex, ENTRY_KIND)
					+ " +0x14D=" + hexByte(hex, ENTRY_KIND2));
			}
		}

		/** One entry in full: the fields we claim to know, then the raw bytes for the ones we do not. */
		private static void describe(String pad, String hex)
		{
			System.out.println("[menuprobe]" + pad + "+0x50  text = \"" + text(hex, ENTRY_TEXT) + "\""
				+ (heapStored(hex, ENTRY_TEXT) ? " (heap)" : " (inline)"));
			System.out.println("[menuprobe]" + pad + "+0xB8  text = \"" + text(hex, ENTRY_TEXT2) + "\""
				+ (heapStored(hex, ENTRY_TEXT2) ? " (heap)" : " (inline)"));
			System.out.println("[menuprobe]" + pad + "+0x134 id   = " + idText(hex)
				+ "   +0x147 = " + hexByte(hex, ENTRY_KIND)
				+ "   +0x14D = " + hexByte(hex, ENTRY_KIND2));
			// The 336 bytes, in the four chunks a reader can actually line up against the offsets
			// above. Everything outside +0x50/+0xB8/+0x134/+0x147/+0x14D is unclaimed, and this is
			// where whoever extends the layout looks next.
			System.out.println("[menuprobe]" + pad + "raw +0x00 " + slice(hex, 0x00, 0x50));
			System.out.println("[menuprobe]" + pad + "raw +0x50 " + slice(hex, 0x50, 0x68));
			System.out.println("[menuprobe]" + pad + "raw +0xB8 " + slice(hex, 0xB8, 0xD0));
			System.out.println("[menuprobe]" + pad + "raw +0xD0 " + slice(hex, 0xD0, ENTRY_STRIDE));
		}

		/** {@code peek}, with every failure folded into "" the way nxtString() folds its own. */
		private static String read(long at, int len)
		{
			if (at <= 0 || len <= 0)
			{
				return "";
			}
			try
			{
				String hex = Natives.peek(at, len);
				return hex == null ? "" : hex;
			}
			catch (Throwable t)
			{
				return "";
			}
		}

		/**
		 * An NxtString at {@code off} within a dump, INLINE OR HEAP. The heap case costs a second
		 * peek, which is why {@link #inlineText} exists separately: the self-test must not follow a
		 * pointer out of a struct it has not established is a struct yet.
		 */
		private static String text(String hex, int off)
		{
			if (!heapStored(hex, off))
			{
				return inlineText(hex, off);
			}
			long ptr = hexLong(hex, off);
			int len = (int) Math.min(200, Math.max(0, hexLong(hex, off + 8)));
			if (ptr == 0 || len == 0)
			{
				return "";
			}
			return ascii(read(ptr, len));
		}

		/** True when this NxtString keeps its characters on the heap (flag bit 0x80, per nxtString()). */
		static boolean heapStored(String hex, int off)
		{
			int flag = hexByte(hex, off + NXT_FLAG);
			return flag >= 0 && (flag & 0x80) != 0;
		}

		/**
		 * An INLINE NxtString at {@code off}: up to 23 bytes in place, with {@code 0x17 - length} in
		 * the flag byte at {@code +0x17}. "" for a heap string, a torn one, or a flag that is not a
		 * length -- the same refusals client/game.hpp's nxtString() makes, kept identical on purpose
		 * so a disagreement between this probe and the DLL means a real disagreement.
		 */
		static String inlineText(String hex, int off)
		{
			int flag = hexByte(hex, off + NXT_FLAG);
			if (flag < 0 || flag > NXT_FLAG)
			{
				return "";                       // heap, or not a length byte: torn or not a string
			}
			return ascii(slice(hex, off, off + (NXT_FLAG - flag)));
		}

		/** +0x134 as text, naming the client's own "this entry is not real" sentinel when it is set. */
		private static String idText(String hex)
		{
			long id = hexInt(hex, ENTRY_ID);
			if (id == ID_INVALID)
			{
				return "0x7FFFFFFE (the client's INVALID sentinel)";
			}
			return Long.toString(id) + " (0x" + Long.toHexString(id & 0xFFFFFFFFL) + ")";
		}

		// -- pure hex helpers. Static and side-effect free so the decoding is tested with no game
		//    behind it (java-test/kewl/rl/GameMenuProbeTest.java); a probe whose parser is wrong
		//    reports a wrong layout, which is worse than reporting nothing.

		/** The byte at {@code index} of a peek() dump, or -1 when the dump does not reach that far. */
		static int hexByte(String hex, int index)
		{
			int p = index * 2;
			if (hex == null || index < 0 || p + 2 > hex.length())
			{
				return -1;
			}
			int hi = Character.digit(hex.charAt(p), 16);
			int lo = Character.digit(hex.charAt(p + 1), 16);
			return (hi < 0 || lo < 0) ? -1 : (hi << 4) | lo;
		}

		/** Little-endian int32 at {@code index}, sign-extended; 0 when any byte is missing. */
		static int hexInt(String hex, int index)
		{
			int v = 0;
			for (int i = 3; i >= 0; i--)
			{
				int b = hexByte(hex, index + i);
				if (b < 0)
				{
					return 0;
				}
				v = (v << 8) | b;
			}
			return v;
		}

		/** Little-endian u64 at {@code index}; 0 when any byte is missing. */
		static long hexLong(String hex, int index)
		{
			long v = 0;
			for (int i = 7; i >= 0; i--)
			{
				int b = hexByte(hex, index + i);
				if (b < 0)
				{
					return 0;
				}
				v = (v << 8) | b;
			}
			return v;
		}

		/** Bytes {@code [from, to)} of a dump as hex, clamped to what the dump holds. */
		static String slice(String hex, int from, int to)
		{
			StringBuilder sb = new StringBuilder();
			for (int i = Math.max(0, from); i < to; i++)
			{
				int b = hexByte(hex, i);
				if (b < 0)
				{
					break;
				}
				if (sb.length() > 0)
				{
					sb.append(' ');
				}
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		}

		/**
		 * Hex bytes as text, cut at the first NUL so an inline buffer's tail never leaks, with
		 * anything unprintable shown as '.' -- the entry text may carry the game's {@code <col=...>}
		 * markup, which is printable and must survive, but it may equally be garbage if an offset is
		 * wrong, and garbage must not scramble the log it is being read out of.
		 */
		static String ascii(String hex)
		{
			String packed = hex == null ? "" : hex.replace(" ", "");
			StringBuilder sb = new StringBuilder();
			for (int i = 0; ; i++)
			{
				int b = hexByte(packed, i);
				if (b <= 0)
				{
					break;                       // -1 end of dump, 0 the NUL terminator
				}
				sb.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
			}
			return sb.toString();
		}
	}
}

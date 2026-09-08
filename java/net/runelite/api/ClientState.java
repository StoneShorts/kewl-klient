// The seam between the RuneLite shim and the memory offsets that do not exist yet.
//
// Every method here is either wired to kewl (nothing left to do) or holds an honest default with a
// comment naming the offset it is waiting for. Phase D of the port re-derives those offsets one at a
// time; each lands by filling in its method here plus a native in kewl.Natives -- nothing else in the
// shim needs to change.
package net.runelite.api;

import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import net.runelite.api.worldmap.WorldMap;

public class ClientState
{
	// -- varps -------------------------------------------------------------------------------------
	//
	// LIVE as of client-240-6: the varp native reads the client's global varp array (offsets.hpp,
	// VARP_ARRAY_PTR, found via the client's own getVarp Lua binding). Varbit reads decompose on top of
	// it with the definition table below -- pure data from an OSRS cache dump, no memory reads of its
	// own -- so a varbit id we have no row for still reads 0 (fail closed), but every id the plugin and
	// the transport data reference is covered by VarbitTable.
	//
	// There is deliberately no setVarps caching layer any more: the native is a pass-through read, and
	// the shim asks for individual ids a handful of times per frame. A stale snapshot behind an API
	// that looks live is worse than a slightly slower live read.

	/** varbit id -> {varp index, low bit, bit count}. */
	private static final Map<Integer, int[]> VARBITS = loadVarbitTable();

	public int getVarpValue(int id)
	{
		if (id < 0 || !kewl.api.Game.ready())
		{
			return -1;
		}
		return kewl.Natives.varp(id);
	}

	/**
	 * Unknown or unset varbits read 0, not -1. Callers test these with {@code != 0} ("is the gate
	 * open?"), so an unknown sentinel of -1 would answer "open" to every gate -- PathfinderConfig's
	 * SAILING_BOARDED_BOAT check turned "no varp data at all" into "permanently on a boat" that way.
	 * Zero fails closed: gated transports are treated as unusable until their varbit is known.
	 */
	public int getVarbitValue(int id)
	{
		int[] def = VARBITS.get(id);
		if (def == null)
		{
			return 0; // unknown varbit id: fail closed
		}
		if (id < 0 || !kewl.api.Game.ready())
		{
			return 0; // varps not up yet: fail closed
		}
		int varp = kewl.Natives.varp(def[0]);
		return (varp >> def[1]) & ((1 << def[2]) - 1);
	}

	/**
	 * Two sources, merged. The built-in {@link VarbitTable} covers exactly the ids the plugin and the
	 * vendored transport data read, generated from the cache's own varbit archive; a full
	 * resources/varbits.csv dump (one id,varp,lowBit,bits row per line) is loaded over it when present,
	 * so a fresher dump can be dropped in without touching this class.
	 */
	private static Map<Integer, int[]> loadVarbitTable()
	{
		Map<Integer, int[]> out = new HashMap<>();
		for (int[] row : VarbitTable.ROWS)
		{
			out.put(row[0], new int[]{row[1], row[2], row[3]});
		}
		InputStream in = ClientState.class.getResourceAsStream("/varbits.csv");
		if (in == null)
		{
			return out;
		}
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in)))
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				if (line.startsWith("#") || line.isBlank())
				{
					continue;
				}
				String[] parts = line.split(",");
				if (parts.length < 4)
				{
					continue;
				}
				out.put(Integer.parseInt(parts[0].trim()),
					new int[]{Integer.parseInt(parts[1].trim()),
						Integer.parseInt(parts[2].trim()),
						Integer.parseInt(parts[3].trim())});
			}
		}
		catch (IOException e)
		{
			System.err.println("varbits.csv unreadable: " + e);
		}
		return out;
	}

	// -- game state ----------------------------------------------------------------------------------
	//
	// LIVE as of client-240-6: the client's own state field at client+0x2160 (offsets.hpp GAME_STATE,
	// found via the client's own isLoggedIn Lua leaf, which compares exactly this dword to 30). The
	// values are the same numbering the Java client uses, so GameState.of() maps them directly and the
	// LOGIN_SCREEN -> LOGGING_IN -> LOADING -> LOGGED_IN chain RuneLite code expects happens for real
	// instead of being replayed synthetically. 0 before the client object exists maps to STARTING,
	// which is the truthful answer.
	//
	// The current world id is still NOT derivable statically (every lead eliminated in the deob notes);
	// getWorldType stays empty and WorldChanged keeps its despawn-based trigger.

	public GameState getGameState()
	{
		return GameState.of(kewl.Natives.gameState());
	}

	public EnumSet<WorldType> getWorldType()
	{
		ShimSupport.note("Client.getWorldType", ShimSupport.Kind.NEEDS_OFFSET,
			"reads an EMPTY set, so every world looks like a plain members world: not seasonal, not"
				+ " a PvP world, not deadman. The current world id is what would answer this and every"
				+ " static lead to it has been eliminated on client-240-6 (see the deob notes), so"
				+ " this one needs a live search, not another read of the binary. Callers that gate"
				+ " on a world type get the permissive branch");
		return EnumSet.noneOf(WorldType.class);
	}

	// -- window, camera, input -----------------------------------------------------------------------
	//
	// Waiting on: layout mode, minimap zoom, camera yaw (all near the camera/viewport code; anchor:
	// worldToScreenCoord's camera reads). The mouse position and modifier keys are pure Win32 in the
	// input() native and are pushed in here by the bridge every frame.

	private volatile Point mouseCanvasPosition = new Point(0, 0);
	private volatile boolean shiftDown;
	private volatile boolean ctrlDown;
	private volatile boolean altDown;
	private volatile boolean lbuttonDown;
	private volatile boolean rbuttonDown;
	private volatile boolean mbuttonDown;
	/**
	 * Set by kewl's menu popup for the frames it is on screen. The game's own menu-open flag lives in
	 * the menu struct that is not readable yet (Phase D), so "is a menu open" is answered by the menu
	 * we drew ourselves -- which is the only menu entries get created for right now anyway.
	 */
	private volatile boolean popupMenuOpen;
	/**
	 * The scene tile the right-click landed on, parked here by kewl's menu popup when it opens. While
	 * the popup is up the cursor is over a menu row rather than over the scene, so WorldView's
	 * nearest-tile-to-cursor scan would answer with the row's neighbour; getSelectedSceneTile()
	 * returns this tile instead for every frame isMenuOpen() is true. Not game state.
	 */
	private volatile Tile menuOpenedTile;
	/** Virtual-key codes that turned pressed this frame; replaced wholesale by setInputState. */
	private volatile int[] keyEdges = new int[0];
	private int lastInputFrame = -1;

	public void setMouseCanvasPosition(int x, int y)
	{
		mouseCanvasPosition = new Point(x, y);
	}

	/**
	 * Per-frame snapshot from the input() native: {mouseX, mouseY, shift, ctrl, alt, leftButton,
	 * rightButton, middleButton, then the virtual-key codes that went from up to down since the
	 * previous frame}. The eight leading slots are fixed and their buttons are held-down flags; the
	 * trailing entries are key EDGES, up to 16 of them (the native's buffer is 8 + 16 slots, and an
	 * edge past the 16th in one frame is dropped -- its held/released state still updates, so a key
	 * kept down does not re-fire next frame). The bridge dispatches the edges as KeyEvents, so a
	 * Keybind hotkey works the way it does in RuneLite, and kewl's menu popup watches rightButton
	 * for the right-click that opens it.
	 *
	 * <p>The frame check makes this idempotent per frame: the native computes edges against the
	 * previous frame, so a second call in the same frame (two bridged plugins tick in sequence) must
	 * not consume them -- both read the same snapshot. The token is kewl's own frame counter, not
	 * anything read from game memory: a stale offset that returned a constant would otherwise collapse
	 * every frame into one and silently kill the whole input pipeline.</p>
	 */
	public void setInputState(int[] buttons)
	{
		int frame = kewl.KewlKlient.frame();
		if (frame == lastInputFrame)
		{
			return;
		}
		lastInputFrame = frame;
		if (buttons.length >= 3)
		{
			this.shiftDown = buttons[2] != 0;
			this.ctrlDown = buttons[3] != 0;
			this.altDown = buttons[4] != 0;
			this.mouseCanvasPosition = new Point(buttons[0], buttons[1]);
		}
		if (buttons.length >= 8)
		{
			this.lbuttonDown = buttons[5] != 0;
			this.rbuttonDown = buttons[6] != 0;
			this.mbuttonDown = buttons[7] != 0;
		}
		int n = Math.max(0, buttons.length - 8);
		int[] edges = new int[n];
		System.arraycopy(buttons, 8, edges, 0, n);
		this.keyEdges = edges;
	}

	/** Left button held, from the same snapshot as the rest of setInputState. */
	public boolean lbuttonDown()
	{
		return lbuttonDown;
	}

	/** Right button held, from the same snapshot as the rest of setInputState. */
	public boolean rbuttonDown()
	{
		return rbuttonDown;
	}

	/** Middle button held, from the same snapshot as the rest of setInputState. */
	public boolean mbuttonDown()
	{
		return mbuttonDown;
	}

	/** The key edges for the current frame, oldest first; the same array until the next snapshot. */
	public int[] keyEdges()
	{
		return keyEdges;
	}

	public Point getMouseCanvasPosition()
	{
		return mouseCanvasPosition;
	}

	public boolean isKeyPressed(int keyCode)
	{
		if (keyCode == KeyCode.KC_SHIFT)
		{
			return shiftDown;
		}
		if (keyCode == KeyCode.KC_CONTROL)
		{
			return ctrlDown;
		}
		if (keyCode == KeyCode.KC_ALT)
		{
			return altDown;
		}
		return false;
	}

	/**
	 * True when the client is in a RESIZABLE layout, false in the 765x503 fixed one. DERIVED, not
	 * read: there is still no layout-mode field, but there no longer needs to be one, because the
	 * widget natives came alive and the layout mode is simply which top-level interface the client
	 * has built.
	 *
	 * <p>Three groups can be that top level -- fixed (Toplevel, 548), resizable-modern
	 * (ToplevelOsrsStretch, 161) and resizable-classic (ToplevelPreEoc, 164) -- and exactly one of
	 * them is laid out at a time. {@link #resolveMinimapWidget} asks the client which one actually
	 * resolves to a usable minimap rectangle and that answer, cached per frame, IS the layout mode.
	 * This used to be a hardcoded {@code true}, which meant the fixed-mode branch of every caller
	 * (this shim's {@link #getMinimapDrawWidget} and the ported plugin's own copy of it) was
	 * unreachable: a player in fixed mode got resizable-mode minimap widgets, which do not resolve,
	 * and therefore no minimap overlays at all.</p>
	 *
	 * <p>Before anything resolves -- pre-login, or with no interface loaded -- it answers true, the
	 * old behaviour and the commoner layout, and says so through {@link ShimSupport} rather than
	 * letting the guess pass for a reading.</p>
	 */
	public boolean isResized()
	{
		Widget minimap = resolveMinimapWidget();
		if (minimap == null)
		{
			ShimSupport.note("Client.isResized", ShimSupport.Kind.NEEDS_OFFSET,
				"reads true (the commoner layout) because no top-level minimap widget has resolved"
					+ " yet -- normally pre-login. Once one resolves this is derived, not guessed;"
					+ " a real layout-mode field would answer even with no interface built");
			return true;
		}
		return minimap.getId() != InterfaceID.Toplevel.MINIMAP;
	}

	/**
	 * Minimap pixels per tile. NOT read from the client, and not derivable the way the yaw below is:
	 * the projection natives describe the 3D viewport, while the minimap is a separate raster the
	 * client renders itself, so no pair of projected points measures its scale, and no minimap-zoom
	 * field is derived in client/offsets.hpp.
	 *
	 * <p>4.0 is not a guess pulled out of the air, though: it is the vanilla OSRS minimap scale, and
	 * it is what the 152x152 minimap widget and upstream's own 20-tile draw radius both imply
	 * (152 / 4 = 38 tiles across = +-19 from the centre). Since the widget now resolves, that
	 * corroboration is CHECKED rather than assumed -- {@link Perspective#minimapScaleNote} judges the
	 * resolved rectangle against this number and the minimap's one-time "resolved" line says whether
	 * it held. A non-152 minimap would mean the client is drawing at a scale nobody has measured, and
	 * the log says exactly that instead of quietly drawing wrong.</p>
	 */
	public double getMinimapZoom()
	{
		ShimSupport.note("Client.getMinimapZoom", ShimSupport.Kind.UNSUPPORTABLE,
			"reads the vanilla " + VANILLA_MINIMAP_ZOOM + " px/tile, which is corroborated but not"
				+ " measured: the projection natives describe the 3D viewport and the minimap is a"
				+ " separate raster the client rasterises itself, so no pair of projected points can"
				+ " measure it, and no minimap-zoom field is derived in client/offsets.hpp. See"
				+ " Perspective.minimapScaleNote for the cross-check against the widget's size");
		return VANILLA_MINIMAP_ZOOM;
	}

	/** The vanilla OSRS minimap scale: 152 px across at 4 px/tile is 38 tiles. See above. */
	static final double VANILLA_MINIMAP_ZOOM = 4.0;

	// -- camera orientation ------------------------------------------------------------------------
	//
	// Derived, not read: there is no camera-orientation offset on this build, but the game's own
	// projection is trusted and its shape pins BOTH angles exactly. See the two blocks above
	// Perspective.yawFromScreenBasis and Perspective.pitchFromScreenBasis for the derivations; this
	// is the plumbing around them -- six projections, done once per frame, with the previous values
	// kept when they cannot be had.
	//
	// One probe sweep answers both questions, which is why they share a cache line here rather than
	// each keeping their own: the yaw needs the four horizontal probes, the pitch needs those same
	// four plus a vertical pair, and doing the sweep twice would double the per-frame native
	// projection cost for nothing.

	/**
	 * Half-extents (fine units) for the probe pairs, tried in order, largest first.
	 *
	 * <p>Two tiles is the sweet spot and it is a measured one, not a preference. The projection
	 * returns whole pixels, so a SHORT pair is quantised: the recovered yaw's worst-case error over
	 * a sweep of every yaw and pitch is ~8 units (1.4 degrees) at one tile and ~4 at two, because the
	 * pair spans twice the pixels. Going further does not keep helping -- the two points' depths
	 * diverge, which is the second-order term the derivation neglects, and a four-tile pair with the
	 * camera close in was the WORST of the lot at ~18 units. The shorter entries are the fallback for
	 * the case where two tiles cannot be projected at all: the player against the edge of the canvas,
	 * where nProject rejects anything past a 64px margin.</p>
	 */
	private static final int[] CAMERA_PROBE_EXTENTS = { 2 * Perspective.LOCAL_TILE_SIZE,
		Perspective.LOCAL_TILE_SIZE, 64, 32 };

	/** Last successfully derived yaw; 0 (north-up) until the first one, which is the old behaviour. */
	private volatile int cameraYaw;
	/**
	 * Last successfully derived pitch, in the same 0..2047 units. -1 until the first one, and -1 is
	 * what the accessors hand back: a pitch of 0 is a perfectly plausible level camera and would be
	 * indistinguishable from "never derived", which is the whole complaint this work started from.
	 */
	private volatile int cameraPitch = -1;
	private int cameraBasisFrame = Integer.MIN_VALUE;
	private boolean loggedYawLive;
	private boolean loggedYawFallback;
	private boolean loggedPitchLive;
	private boolean loggedPitchFallback;

	/**
	 * The camera yaw in 0..2047 (see {@link Perspective#YAW_UNITS}). Recomputed once per frame and
	 * cached: a frame's overlays ask for it once per drawn point, and all of them must rotate by the
	 * SAME yaw or the path shears across the minimap.
	 *
	 * <p>The frame token is kewl's own counter, the same one setInputState uses, not anything read
	 * from game memory -- a stale offset that returned a constant would otherwise freeze the yaw at
	 * whatever it was when the client started.</p>
	 */
	public int getCameraYawTarget()
	{
		refreshCameraBasis();
		return cameraYaw;
	}

	/**
	 * The camera pitch in 0..{@link Perspective#PITCH_STRAIGHT_DOWN}, or -1 when it has never been
	 * derived (before login, or while every probe point is off the canvas). Same frame cache as the
	 * yaw, and derived the same way -- see {@link Perspective#pitchFromScreenBasis}.
	 *
	 * <p>-1 rather than 0 for "unknown", unlike the yaw. The two cases are not symmetric: a yaw of 0
	 * is the north-up minimap the overlays drew for their whole life before the derivation landed, so
	 * it is a usable fallback, whereas a pitch of 0 is a camera looking flat along the ground, which
	 * OSRS never shows and which would foreshorten every height calculation to nothing. A caller that
	 * gets -1 must not treat it as a level camera; {@link ShimSupport} carries the reason.</p>
	 */
	public int getCameraPitch()
	{
		refreshCameraBasis();
		if (cameraPitch < 0)
		{
			ShimSupport.note("Client.getCameraPitch", ShimSupport.Kind.NEEDS_OFFSET,
				"reads -1 (unknown, NOT a level camera): the pitch is derived from the projection and"
					+ " no probe point has projected yet -- before login, or with the player jammed"
					+ " against the edge of the canvas. A camera-block offset would remove the"
					+ " dependency on probes entirely");
		}
		return cameraPitch;
	}

	/**
	 * Upstream distinguishes the camera's CURRENT angle from the angle it is animating TOWARDS; the
	 * derivation cannot, because it measures where the camera is actually looking right now. So the
	 * two are the same number here and they differ from upstream only during the few frames of a
	 * camera move -- where "where it is" is the more useful of the two for anything being drawn.
	 */
	public int getCameraPitchTarget()
	{
		return getCameraPitch();
	}

	/**
	 * Six projections around the player -- the same distance north, south, east and west at one
	 * height, plus one pair directly above and below -- reduced to a yaw and a pitch. Runs at most
	 * once per frame; leaves both cached values alone when it cannot get an answer.
	 *
	 * <p>The four horizontal probes share one height so the only thing that differs between them is
	 * the horizontal offset; the height itself is irrelevant to the screen-x terms the yaw comes out
	 * of, but a height that projects (the player's own ground, which is exact for the player) keeps
	 * the points on screen. The vertical pair is what makes the pitch an atan2 instead of an asin --
	 * see {@link Perspective#pitchFromScreenBasis(int, int, int, int, int)} -- and its failure is
	 * survivable: the four-probe form still answers, a little less precisely.</p>
	 */
	private void refreshCameraBasis()
	{
		int frame = kewl.KewlKlient.frame();
		if (frame == cameraBasisFrame)
		{
			return;
		}
		cameraBasisFrame = frame;

		kewl.api.Local me = kewl.api.Game.me();
		if (!me.exists())
		{
			return; // not in the world: the ordinary failure, and not worth a line
		}
		// The RENDER position when the client has one, else the tile centre: a zero render position
		// is how kewl's entity reader reports "no fine position for this entity" (see
		// Game.tileDistance), and projecting the scene origin instead of the player would put the
		// probe a hundred tiles away where the perspective divide is nothing like the player's.
		int fx = me.fineX(), fy = me.fineY();
		if (fx == 0 && fy == 0)
		{
			fx = (me.sceneX() << Perspective.LOCAL_COORD_BITS) + Perspective.LOCAL_HALF_TILE_SIZE;
			fy = (me.sceneY() << Perspective.LOCAL_COORD_BITS) + Perspective.LOCAL_HALF_TILE_SIZE;
		}
		int h = me.height();
		for (int d : CAMERA_PROBE_EXTENTS)
		{
			java.awt.Point north = kewl.api.Game.projectFine(fx, h, fy + d);
			java.awt.Point south = kewl.api.Game.projectFine(fx, h, fy - d);
			java.awt.Point east = kewl.api.Game.projectFine(fx + d, h, fy);
			java.awt.Point west = kewl.api.Game.projectFine(fx - d, h, fy);
			if (north == null || south == null || east == null || west == null)
			{
				continue; // one of the four fell off the canvas: try a tighter pair
			}
			int northDx = north.x - south.x;
			int northDy = north.y - south.y;
			int eastDx = east.x - west.x;
			int eastDy = east.y - west.y;

			int yaw = Perspective.yawFromScreenBasis(northDx, eastDx);
			if (yaw < 0)
			{
				continue; // degenerate basis at this extent; a longer pair spans more pixels
			}
			cameraYaw = yaw;
			if (!loggedYawLive)
			{
				loggedYawLive = true;
				System.out.println("[shim] camera yaw derived from the projection: " + yaw
					+ "/" + Perspective.YAW_UNITS + " -- minimap overlays now turn with the camera");
			}
			// The height axis is negative = up (kewl.api.Game.projectFine), so `h - d` is the RAISED
			// point and `h + d` the lowered one -- the order Perspective's upScreenDy is defined in.
			java.awt.Point up = kewl.api.Game.projectFine(fx, h - d, fy);
			java.awt.Point down = kewl.api.Game.projectFine(fx, h + d, fy);
			int pitch = up != null && down != null
				? Perspective.pitchFromScreenBasis(northDx, northDy, eastDx, eastDy, up.y - down.y)
				: Perspective.pitchFromScreenBasis(northDx, northDy, eastDx, eastDy);
			if (pitch >= 0)
			{
				cameraPitch = pitch;
				if (!loggedPitchLive)
				{
					loggedPitchLive = true;
					String cross = up != null && down != null
						? Perspective.pitchFormsNote(pitch,
							Perspective.pitchFromScreenBasis(northDx, northDy, eastDx, eastDy))
						: "the vertical probe pair would not project, so this is the asin form -- less"
							+ " precise near a vertical camera";
					System.out.println("[shim] camera pitch derived from the projection: " + pitch
						+ "/" + Perspective.YAW_UNITS + " (" + (up != null && down != null ? "atan2" : "asin")
						+ " form); " + cross);
				}
			}
			else if (!loggedPitchFallback)
			{
				loggedPitchFallback = true;
				System.out.println("[shim] camera pitch could not be derived from a basis the yaw"
					+ " accepted -- the vertical spread is inside the rounding floor; keeping "
					+ cameraPitch);
			}
			return;
		}
		if (!loggedYawFallback)
		{
			// Once, and only for the SURPRISING failure. Not being in the world is the ordinary one
			// -- it is true on every frame before login, and is returned above without a word, so the
			// single line is not burned on something nobody needs to hear. This is the other one: the
			// player is standing there and not one of the four probe points projected, which means
			// the camera is on the near plane or the player is jammed against the edge of the canvas
			// past nProject's 64px margin.
			loggedYawFallback = true;
			System.out.println("[shim] camera orientation could not be derived while in the world (no"
				+ " probe point projected); keeping the last yaw (" + cameraYaw + ") and pitch ("
				+ cameraPitch + ")");
		}
	}

	// -- containers ------------------------------------------------------------------------------------
	//
	// LIVE as of client-240-6: the container native walks the client's global container table
	// (offsets.hpp, CONTAINER_BUCKETS, found via the client's own invGetObjId/invGetNum bindings) and
	// returns one snapshot. Waiting on in-game verification of the ids used below.

	public ItemContainer getItemContainer(int id)
	{
		if (id < 0 || !kewl.api.Game.ready())
		{
			return null;
		}
		int[] flat = kewl.Natives.container(id);
		if (flat == null || flat.length == 0)
		{
			return null;
		}
		Item[] items = new Item[flat.length / 2];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(flat[2 * i], flat[2 * i + 1]);
		}
		return new ItemContainer(id, items);
	}

	// -- widgets -----------------------------------------------------------------------------------------
	//
	// LIVE as of client-240-6: the widget tree is the classic rs2lib IfType, looked up by the client's
	// own (group << 16) | component id encoding through the interface manager (offsets.hpp, IFACE_*,
	// found via the client's own ifType Lua binding and verified live -- real text and canvas-sized
	// bounds came back for the loaded groups). Children walk the same IfType records through the
	// widgetChild native; they are filled lazily, see fillChildren.

	public Widget getWidget(int... ids)
	{
		if (ids == null || ids.length == 0 || !kewl.api.Game.ready())
		{
			return null;
		}
		// RuneLite's convention: one argument is a packed id, two or more are groupId, componentId,
		// then nested child indices.
		int packed = ids.length == 1 ? ids[0] : (ids[0] << 16) | ids[1];
		int[] root = kewl.Natives.widget(packed);
		if (root.length == 0)
		{
			return null;
		}
		Widget w = fillWidget(packed, root);
		// Nested children: descend and accumulate the parents' x/y, because the widget stores its
		// position relative to its parent and RuneLite's getCanvasLocation is absolute. The parent's
		// bounds are now absolute themselves wherever its chain resolved, so this descent produces
		// absolute children for free -- and carries the parent's honesty flag down with them, because
		// a child of a widget we could not place is a widget we cannot place either.
		for (int i = 2; i < ids.length && w != null; i++)
		{
			Rectangle pb = w.getBounds();
			int[] child = kewl.Natives.widgetChild(packed, ids[i]);
			if (child.length == 0)
			{
				return null;
			}
			boolean parentAbsolute = w.isCanvasAbsolute();
			packed = childIndexId(packed, ids[i]);
			w = fillWidgetOffset(packed, child, pb.x, pb.y, parentAbsolute);
		}
		return w;
	}

	/** A component id under `parent` for a child found at childIndex. */
	private static int childIndexId(int parentGroupPacked, int childIndex)
	{
		return (parentGroupPacked & 0xFFFF0000) | childIndex;
	}

	/**
	 * A widget looked up by its own packed id -- the case that was wrong for this shim's whole life.
	 *
	 * <p>A single packed id used to take the component's stored x/y as if it were a canvas position.
	 * It is not: it is relative to the parent, and nothing here could walk UP a packed id to find the
	 * parent. That is why the minimap reported (53,8) while it visibly sat near x=1143, and why the
	 * two map overlays stood down. {@link kewl.Natives#widgetAbs} takes RuneLite's own sum in C++ now,
	 * and this is where every ported plugin gets it without being touched.</p>
	 *
	 * <p>IT WORKS, and that is a measurement rather than an expectation as of 2026-09-07. The running
	 * client printed "[shim] minimap widget resolved at (1156,8) 152x152 -- minimap overlays are live;
	 * 38.0x38.0 tiles across at 4.0 px/tile" on a 1356x900 canvas: (1156,8) is the right edge of that
	 * canvas, where the minimap visibly is, and (53,8) is what the parent-relative reading gives. So
	 * the whole-tree parent-link walk RUNS and returns complete=1 for a loaded group. Anything that
	 * still says the derivation may never have run is out of date; the remaining failure is a
	 * different one, below.</p>
	 *
	 * <p>When the chain does NOT reach a root the bounds stay exactly what they have always been and
	 * {@code canvasAbsolute} is false, so a caller that draws refuses instead of drawing wrong. The
	 * live cause of that is a group whose data is not resident: the same session printed "[shim]
	 * widgetAbs WORLDMAP 595:7 is not loaded", because the world-map group is not loaded until the
	 * map has been opened once. That is a LOADING state, not a broken link.</p>
	 */
	private static Widget fillWidget(int id, int[] v)
	{
		Widget w = new Widget(id);
		int[] abs = widgetAbs(id);
		boolean absolute = absoluteUsable(abs);
		if (absolute)
		{
			w.setBounds(abs[1], abs[2], abs[3], abs[4]);
			w.setChainDepth(abs[6]);
		}
		else
		{
			w.setBounds(v[1], v[2], v[3], v[4]);
		}
		return finishWidget(w, id, v, absolute);
	}

	private static Widget fillWidgetOffset(int id, int[] v, int offX, int offY, boolean parentAbsolute)
	{
		Widget w = new Widget(id);
		w.setBounds(offX + v[1], offY + v[2], v[3], v[4]);
		return finishWidget(w, id, v, parentAbsolute);
	}

	/** The half both fill paths share: the relative pair is kept whatever the bounds turned out to be. */
	private static Widget finishWidget(Widget w, int id, int[] v, boolean absolute)
	{
		w.setHidden(v[5] != 0);
		// The relative pair is kept even when the bounds are absolute: the two shapes must stay
		// distinguishable, and the scrollHeight derivation in fillChildren measures children by their
		// RELATIVE y, which a canvas y would silently inflate by every ancestor's offset.
		w.setRelativeX(v[1]);
		w.setRelativeY(v[2]);
		w.setCanvasAbsolute(absolute);
		w.setText(kewl.Natives.widgetText(id));
		// Children are the caller's problem: enumerating them costs one native call per child, which
		// getWidget has no reason to pay for a widget the caller only wanted the bounds of.
		w.setChildLoader(() -> fillChildren(w, id, w.getBounds().x, w.getBounds().y, w.isCanvasAbsolute()));
		return w;
	}

	// -- absolute widget rectangles ------------------------------------------------------------------
	//
	// One native call per widget per FRAME, not per caller: an overlay asks for the same rectangle
	// repeatedly while drawing (Perspective.localToMinimap wants the minimap rect for every point), and
	// getWidget already pays two JNI round trips. Keyed on kewl's own frame counter, exactly as
	// resolveMinimapWidget below is, and cleared whole rather than aged so nothing can go stale.

	/**
	 * Whether a {@code widgetAbs} answer may be used as a CANVAS rectangle. Pure, so the one decision
	 * this whole feature turns on is tested rather than argued about.
	 *
	 * <p>Both flags are required and neither is redundant. {@code ok} (index 0) says the id resolved
	 * at all; {@code complete} (index 7) says the parent chain reached a root. A chain that gave up
	 * partway still returns ok with a half-summed position, and drawing at that is exactly the failure
	 * this feature exists to prevent -- it is worse than the old refusal, because it looks fine. The
	 * length check covers a DLL older than the native.</p>
	 */
	static boolean absoluteUsable(int[] abs)
	{
		return abs != null && abs.length >= 8 && abs[0] != 0 && abs[7] != 0;
	}

	/**
	 * The root self-test: does a group's root component come out at the canvas origin, canvas-sized?
	 * Pure.
	 *
	 * <p>This is the reading that decides whether the stored x/y are the POST-LAYOUT rectangle (this
	 * shim's whole approach: sum the chain, the anchoring modes are already applied) or the CACHE
	 * ORIGINALS (in which case the position/size mode fields must be derived and the client's
	 * alignment re-implemented -- a much larger job). It needs no measurement by eye and no opinion
	 * about where a minimap "looks like" it is.</p>
	 */
	static boolean rootSelfTestPasses(int[] rootAbs, int canvasWidth, int canvasHeight)
	{
		return canvasWidth > 0 && canvasHeight > 0 && absoluteUsable(rootAbs)
			&& rootAbs[1] == 0 && rootAbs[2] == 0
			&& rootAbs[3] == canvasWidth && rootAbs[4] == canvasHeight;
	}

	// Concurrent, not plain: the shim is called from the render thread and from kewl's own tick, and a
	// plain HashMap resized by two threads at once does not throw -- it can spin forever inside get().
	// The frame counter itself is racy by a frame at worst, which costs one extra native call.
	private static final Map<Integer, int[]> ABSOLUTE_BOUNDS = new java.util.concurrent.ConcurrentHashMap<>();
	private static volatile int absoluteBoundsFrame = Integer.MIN_VALUE;
	/** Set once if the DLL predates the widgetAbs native, so a stale DLL degrades instead of throwing. */
	private static boolean widgetAbsMissing;

	private static int[] widgetAbs(int id)
	{
		if (widgetAbsMissing)
		{
			return null;
		}
		int frame = kewl.KewlKlient.frame();
		if (frame != absoluteBoundsFrame)
		{
			absoluteBoundsFrame = frame;
			ABSOLUTE_BOUNDS.clear();
		}
		int[] cached = ABSOLUTE_BOUNDS.get(id);
		if (cached != null)
		{
			return cached;
		}
		int[] v;
		try
		{
			v = kewl.Natives.widgetAbs(id);
		}
		catch (UnsatisfiedLinkError e)
		{
			// An older kewlklient.dll: every widget keeps the parent-relative bounds it had before this
			// work, every overlay keeps refusing, and nothing throws per frame.
			widgetAbsMissing = true;
			System.out.println("[shim] widgetAbs is not in this DLL -- widget positions stay"
				+ " parent-relative and map overlays stay off; rebuild the DLL");
			return null;
		}
		if (v == null)
		{
			v = new int[0];
		}
		ABSOLUTE_BOUNDS.put(id, v);
		return v;
	}

	/**
	 * Fills `parent`'s child list by walking the widgetChild native until it comes back empty. The
	 * native exposes one flat child array per widget, and it is the same list behind both accessors --
	 * see Widget.
	 *
	 * <p>This used to assert that the array carries "cache-defined static children and runtime-spawned
	 * dynamic children alike". Nothing ever measured that, and in the Java client the static tree is
	 * expressed by the parent link while the child array is the cc_create list. It is now MEASURABLE:
	 * {@code kewl.Natives.widgetTreeProbe} reports how many components appear as somebody's child. If
	 * that is near 0%, this list is dynamic-only and the static tree exists only through the parent
	 * link that {@code widgetAbs} walks.</p>
	 *
	 * <p>The child's own packed id assumes the client addresses children the way it addresses
	 * everything else, as {@code (group << 16) | index} into the group's component array -- the same
	 * encoding doAction targets use. A child that is not in that array resolves to no text rather than
	 * to its parent's, which is the honest failure.</p>
	 */
	private static void fillChildren(Widget parent, int id, int offX, int offY, boolean parentAbsolute)
	{
		int contentBottom = 0;
		for (int i = 0; i < Widget.MAX_CHILDREN; i++)
		{
			int[] child = kewl.Natives.widgetChild(id, i);
			if (child.length == 0)
			{
				break; // past the last child: the whole list has been read
			}
			Widget w = fillWidgetOffset(childIndexId(id, i), child, offX, offY, parentAbsolute);
			parent.getChildren().add(w);
			contentBottom = Math.max(contentBottom, w.getRelativeY() + w.getHeight());
		}
		if (contentBottom > 0)
		{
			parent.setScrollHeight(contentBottom);
		}
	}

	/**
	 * The minimap draw area, resolved by ASKING THE CLIENT rather than by guessing the layout mode.
	 *
	 * <p>Exactly one of the three top-level interfaces is built at a time, so at most one of their
	 * minimap components resolves to a real, non-hidden rectangle. Trying all three and keeping the
	 * one that does is strictly more information than the old form had: that one picked between the
	 * two RESIZABLE skins on the stone-arrangement varbit and could never reach fixed mode at all,
	 * because {@link #isResized} was hardcoded true. The varbit still decides the ORDER -- it is a
	 * live read and it is right about which resizable skin is in use, so honouring it keeps the
	 * common case to a single native call -- but it no longer decides the ANSWER.</p>
	 *
	 * <p>Cached per frame (kewl's own frame counter, not game state): the plugin asks for this from
	 * its clip-area path and the shim asks again from {@link Perspective#localToMinimap} for every
	 * drawn point, and three widget lookups per point would be three native calls per point.</p>
	 */
	/**
	 * The minimap draw area, or null when its rectangle is not a CANVAS rectangle.
	 *
	 * <p>The gate is new and it is the honest one. Both consumers of this widget use its bounds as a
	 * CLIP -- {@code kewl.rl.OverlayRenderer} and the ported plugin's own copy build an ellipse from
	 * it -- and a clip taken from a parent-relative rectangle is the failure that painted the world map
	 * solid black. Both already treat null as "not this frame", so refusing here costs nothing and
	 * removes a hole that {@code Perspective.minimapPlacementWarning} could not close: its left-half
	 * heuristic catches a relative x in RESIZABLE mode, but in fixed mode the minimap sits near x=844
	 * on a 1314 canvas and a relative rectangle would sail past it. Completeness is not a heuristic.</p>
	 *
	 * <p>{@link #isResized} deliberately does NOT go through this gate: which layout is built is a fact
	 * about the interface tree, and it must not become contingent on a memory derivation.</p>
	 */
	public Widget getMinimapDrawWidget()
	{
		Widget w = resolveMinimapWidget();
		if (w != null && !w.isCanvasAbsolute())
		{
			if (!loggedMinimapNotAbsolute)
			{
				loggedMinimapNotAbsolute = true;
				System.out.println("[shim] minimap widget " + (w.getId() >> 16) + ":" + (w.getId() & 0xFFFF)
					+ " resolved at " + w.getBounds().width + "x" + w.getBounds().height
					+ " but its parent chain did not reach a root, so (" + w.getBounds().x + ","
					+ w.getBounds().y + ") is PARENT-RELATIVE -- minimap overlays stay off. This is a"
					+ " REGRESSION, not the known state: on 2026-09-07 this same widget resolved"
					+ " canvas-absolute at (1156,8) 152x152 on a 1356-wide canvas. Run with KEWL_LOG"
					+ " for the widgetTreeProbe counts and the chain dump");
			}
			return null;
		}
		return w;
	}

	private static boolean loggedMinimapNotAbsolute;

	/** Preference order for the top-level minimap: the varbit's resizable skin, the other, then fixed. */
	private int[] minimapCandidates()
	{
		return getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1
			? new int[]{InterfaceID.ToplevelPreEoc.MINIMAP, InterfaceID.ToplevelOsrsStretch.MINIMAP,
				InterfaceID.Toplevel.MINIMAP}
			: new int[]{InterfaceID.ToplevelOsrsStretch.MINIMAP, InterfaceID.ToplevelPreEoc.MINIMAP,
				InterfaceID.Toplevel.MINIMAP};
	}

	private volatile Widget minimapWidget;
	private int minimapWidgetFrame = Integer.MIN_VALUE;

	/**
	 * The first candidate that resolves to a widget with a real size, or null when none does. A
	 * zero-sized or absent widget is the client saying "that interface is not the one that is built";
	 * a hidden one is a real widget the player has toggled off, and is returned as-is so callers see
	 * the same {@code isHidden()} upstream would show them.
	 */
	private Widget resolveMinimapWidget()
	{
		int frame = kewl.KewlKlient.frame();
		if (frame == minimapWidgetFrame)
		{
			return minimapWidget;
		}
		minimapWidgetFrame = frame;
		minimapWidget = null;
		for (int id : minimapCandidates())
		{
			Widget w = getWidget(id);
			if (w != null && w.getWidth() > 0 && w.getHeight() > 0)
			{
				minimapWidget = w;
				break;
			}
		}
		if (minimapWidget != null)
		{
			logWidgetChainOnce(minimapWidget);
		}
		return minimapWidget;
	}

	private static boolean loggedWidgetChain;

	/**
	 * Print the absolute-geometry evidence ONCE a session, under {@code KEWL_LOG}, so a wrong answer
	 * says which link is wrong instead of just being wrong.
	 *
	 * <p>Four readings, each of which fails differently:</p>
	 * <ol>
	 *   <li>the DERIVATION: which offset the whole-tree tally accepted as the parent link, with the
	 *       counts, and whether it found the {@code id} field as its positive control;</li>
	 *   <li>the MINIMAP CHAIN, hop by hop, ending in the computed canvas rectangle beside the canvas
	 *       size. The number to check is the last x -- the minimap sits near the right edge, never at
	 *       x=53;</li>
	 *   <li>the SELF-TEST, which needs no measurement by eye: the toplevel group's ROOT component must
	 *       come out {@code (0,0)} at exactly the canvas size. If it does not, the stored x/y are cache
	 *       ORIGINALS rather than the laid-out rectangle, and the whole approach is wrong -- that is
	 *       the fork recorded at the end of offsets.hpp's THE PARENT LINK block;</li>
	 *   <li>the WORLD MAP container (595:7), which is a different GROUP. Read live on 2026-09-07 and
	 *       it said "is not loaded": the world-map group's data is not resident until the map has been
	 *       opened at least once, and until then {@code getWidget(MAP_CONTAINER)} is null. So an
	 *       incomplete chain on this line, taken before the first open, is a LOADING state and not the
	 *       cross-group parent problem this comment used to attribute it to. Take the reading again
	 *       with the map open -- that is the one that says whether the cross-group term is real.</li>
	 * </ol>
	 *
	 * <p>A fifth check needs the human and no code: drag the window narrower. The absolute x must
	 * track the right edge while the stored relative x does not move. That is what distinguishes a
	 * correct chain from a coincidence at one window size.</p>
	 */
	private void logWidgetChainOnce(Widget minimap)
	{
		if (loggedWidgetChain || System.getenv("KEWL_LOG") == null)
		{
			return;
		}
		loggedWidgetChain = true;
		try
		{
			int[] vp = kewl.Natives.viewport();          // {x, y, width, height}
			int cw = vp != null && vp.length == 4 ? vp[2] : 0;
			int chh = vp != null && vp.length == 4 ? vp[3] : 0;
			for (String l : kewl.Natives.widgetTreeProbe().split("\n"))
			{
				if (!l.isEmpty())
				{
					System.out.println("[shim] widget tree: " + l);
				}
			}
			System.out.println("[shim] widgetAbs " + kewl.Natives.widgetChain(minimap.getId())
				+ "; canvas " + cw + "x" + chh);
			int rootId = minimap.getId() & 0xFFFF0000;   // component 0, the conventional group root
			int[] root = widgetAbs(rootId);
			System.out.println("[shim] widgetAbs SELF-TEST " + kewl.Natives.widgetChain(rootId) + " -- "
				+ (rootSelfTestPasses(root, cw, chh)
					? "PASS: the group root is (0,0) at the canvas size, so the stored x/y are the"
						+ " laid-out rectangle and this whole approach is sound"
					: "INCONCLUSIVE: the group root did not come out (0,0) at " + cw + "x" + chh
						+ ". Either component 0 is not this group's root, or the stored x/y are cache"
						+ " ORIGINALS and position/size MODE fields must be derived instead -- see the"
						+ " fork in client/offsets.hpp"));
			System.out.println("[shim] widgetAbs WORLDMAP "
				+ kewl.Natives.widgetChain(InterfaceID.Worldmap.MAP_CONTAINER)
				+ " -- \"is not loaded\" here is the EXPECTED reading before the world map has ever"
				+ " been opened (measured 2026-09-07); the group's data is not resident until then."
				+ " An INCOMPLETE chain with the map OPEN would be the cross-group case, which needs"
				+ " the client's component table");
		}
		catch (UnsatisfiedLinkError e)
		{
			System.out.println("[shim] widgetChain/widgetTreeProbe are not in this DLL -- rebuild it");
		}
	}

	// -- definitions, map, menu --------------------------------------------------------------------------

	/**
	 * True while kewl's own menu popup is on screen. The game's menu-open flag lives in the menu
	 * struct that is not readable yet (the menu native, Phase D) -- until then the only menu entries
	 * that exist are the ones the popup shows, so its state IS the menu state.
	 */
	public boolean isMenuOpen()
	{
		return popupMenuOpen;
	}

	/** Called by kewl's menu popup for the frames it is drawn; not game state. */
	public void setPopupMenuOpen(boolean open)
	{
		popupMenuOpen = open;
	}

	/**
	 * The tile the right-click landed on, for the frames kewl's popup menu is open; null otherwise.
	 * WorldView.getSelectedSceneTile() hands this back instead of scanning while isMenuOpen() is
	 * true, so a plugin resolving a world point from a row click gets the tile under the
	 * right-click, not the tile under whichever row the cursor has since moved onto.
	 */
	public Tile getMenuOpenedTile()
	{
		return menuOpenedTile;
	}

	/** Called by kewl's menu popup when it opens; cleared together with the flag when it closes. */
	public void setMenuOpenedTile(Tile tile)
	{
		menuOpenedTile = tile;
	}

	public ItemDefinition getItemDefinition(int itemId)
	{
		ShimSupport.note("Client.getItemDefinition", ShimSupport.Kind.NEEDS_CACHE_DATA,
			"returns a definition carrying only the id -- getName() is \"item <id>\" and isStackable()"
				+ " is false for everything. Item definitions live in the game CACHE, not in client"
				+ " memory, so this needs a bundled id->name table next to VarbitTable rather than an"
				+ " offset");
		return new ItemDefinition(itemId);
	}

	public WorldMap getWorldMap()
	{
		return WorldMap.INSTANCE;
	}

	public EnumComposition getEnum(int id)
	{
		// The rune-pouch enum is the only one the plugin reads; its bundled table lives in EnumTable.
		// Every other enum degrades to empty: quantities read -1 and gated teleports stay unusable.
		if (id == EnumID.RUNEPOUCH_RUNE)
		{
			final int[][] rows = EnumTable.RUNEPOUCH_RUNE;
			final int[] keys = new int[rows.length];
			final int[] vals = new int[rows.length];
			for (int i = 0; i < rows.length; i++)
			{
				keys[i] = rows[i][0];
				vals[i] = rows[i][1];
			}
			return new EnumComposition()
			{
				@Override public int size() { return rows.length; }
				@Override public int[] getKeys() { return keys; }
				@Override public int[] getIntVals() { return vals; }
				@Override public long[] getLongVals() { return new long[0]; }
				@Override public String[] getStringVals() { return new String[0]; }
				@Override public int getIntValue(int key)
				{
					for (int i = 0; i < keys.length; i++)
					{
						if (keys[i] == key)
						{
							return vals[i];
						}
					}
					return -1;
				}
				@Override public String getStringValue(int key) { return ""; }
				@Override public long getLongValue(int key) { return -1; }
			};
		}
		ShimSupport.note("Client.getEnum", ShimSupport.Kind.NEEDS_CACHE_DATA,
			"returns an EMPTY enum for every id except EnumID.RUNEPOUCH_RUNE (bundled in EnumTable):"
				+ " enums are cache data, so a caller reading one gets size 0, getIntValue -1 and"
				+ " getStringValue \"\". Bundle the rows to fix it -- no offset is involved");
		return new EnumComposition()
		{
			@Override public int size() { return 0; }
			@Override public int[] getKeys() { return new int[0]; }
			@Override public int[] getIntVals() { return new int[0]; }
			@Override public long[] getLongVals() { return new long[0]; }
			@Override public String[] getStringVals() { return new String[0]; }
			@Override public int getIntValue(int key) { return -1; }
			@Override public String getStringValue(int key) { return ""; }
			@Override public long getLongValue(int key) { return -1; }
		};
	}

	public Menu getMenu()
	{
		return Menu.INSTANCE;
	}
}

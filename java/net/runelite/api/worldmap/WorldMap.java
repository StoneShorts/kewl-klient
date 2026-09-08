// Shim of net.runelite.api.worldmap.WorldMap (BSD-2, RuneLite), adapted to KewlKlient.
//
// kewl.rl.Events pushes the map's centre here every frame, read from the native world-map object: the
// centre tile in world tiles is WM_ORIGIN + 48, which is the same quantity as 8 * WM_CENTRE (the
// scroll ints are in 8-tile units -- see the WM_ findings in client/offsets.hpp). isLive() says
// whether that data has actually arrived: the marker renderer and the plugin's map-click path gate on
// it, because acting on a stale centre turns a map click into a target somewhere the user did not
// click and auto-walk then walks there.
//
// ---------------------------------------------------------------------------------------------
// WHAT WAS MEASURED LIVE ON 2026-09-07, AND WHAT IT CORRECTS
// ---------------------------------------------------------------------------------------------
//
// This file used to say, in three places, that a widget's absolute rectangle was unproven and that
// the parent-link derivation might never resolve. THAT IS WRONG AND IS NOW CORRECTED. The running
// client printed, unprompted:
//
//     [shim] minimap widget resolved at (1156,8) 152x152 -- minimap overlays are live;
//            38.0x38.0 tiles across at 4.0 px/tile -- the vanilla 152x152 minimap, so the scale
//            checks out
//
// on a 1356x900 canvas. A parent-relative minimap reads (53,8); (1156,8) is the right edge of a
// 1356-wide canvas. So scanWidgetTree RUNS, widgetAbs returns complete=1, and
// Widget.isCanvasAbsolute() is TRUE for a LOADED interface group. Absolute geometry is a measurement
// now, not a hope, and no comment here may claim otherwise.
//
// The same session printed the OTHER half, which is what the map's problem actually is:
//
//     [shim] widgetAbs WORLDMAP 595:7 is not loaded
//
// The world-map group is not loaded until the map has been opened at least once, and before that
// client.getWidget(MAP_CONTAINER) is null. That is a LOADING state, not a broken parent chain: the
// old "cross-group parent link that was never derived" reading of an incomplete WORLDMAP chain was
// diagnosing an absent group.
//
// ---------------------------------------------------------------------------------------------
// THE SCALE: STILL NOT DERIVABLE, BUT THE MEASUREMENT IS NO LONGER BLOCKED
// ---------------------------------------------------------------------------------------------
//
// Asked again with absolute rectangles in hand: can the pixels-per-tile now be DERIVED? No, and the
// reason has not changed, so it is restated once and not reopened.
//
//   1. The client has no zoom field to read. offsets.hpp records that wm+0x54C4..0x54DD is fully
//      accounted for (centre, previous centre, per-frame deltas, a flag, a bool), that there is no
//      map-zoom Lua binding, and that the scaling happens unmeasured inside the render path.
//
//   2. The map container's TRUE rectangle -- which we can now read, while the map is open -- still
//      does not give a second reference point. A scale needs two points whose separation is known in
//      BOTH tiles and map pixels. The rectangle supplies pixels. The only other coordinate the map
//      object exposes is the origin, and the origin is NOT a point on the visible map: it is the
//      corner of the map-square LOAD WINDOW, pinned at centre-48 tiles on both axes (FUN_1401ce8b0
//      writes origin = 8*centre - 48; FUN_1401cefe0 loads squares over centre+-6 scroll units). A
//      window that is the same 96 tiles wide at every zoom level carries no scale information.
//      Origin and centre are one quantity in two encodings; there is no second point.
//
// So the scale must be MEASURED. What CHANGED on 2026-09-07 is that the measurement is reachable:
// {@link #pixelsPerTileFromDrag} needs the map container's canvas rectangle to know the cursor is on
// the map, and evidence (1) above proves a loaded group resolves canvas-absolute. Opening the map
// loads the group; the group being loaded makes the rectangle absolute; the absolute rectangle makes
// the drag measurable. THE BOOTSTRAP CLOSES. The old note that this state "cannot bootstrap itself"
// was written before the minimap line and is retracted here.
//
// The measurement itself: while the user drags the open map, the cursor moves P pixels and WM_CENTRE
// moves U scroll units, and one scroll unit is 8 world tiles (offsets.hpp), so
//
//     pixelsPerTile = P / (8 * U)
//
// ASSUMPTIONS, all three of which must hold for the number to mean anything, and none of which is
// derived: the map's drag is 1:1 pixel-to-map (the content follows the cursor exactly), there is no
// inertia or easing after the button is released, and the zoom did not change mid-drag. They are why
// this is only accepted over a LONG drag ({@link #MIN_DRAG_SCROLL_UNITS}): the centre is quantised to
// 8 tiles, so the last unit is worth +-1 and a short drag measures the quantisation rather than the
// scale. Every accepted measurement is logged with BOTH of its reference points -- the two cursor
// pixels and the two WM_CENTRE readings -- by {@link #dragMeasurementNote}, so the number can be
// checked rather than believed.
//
// The other way in is a HUMAN reading, {@link #setPixelsPerTile} /
// -Dkewl.worldmap.pixelsPerTile=<value>. {@link #humanBootstrapAction} is the single sentence that
// says what a person has to do; it is what every uncalibrated refusal ends with, because a refusal
// that does not name its one unblocking action is just a complaint.
//
// PLACEHOLDER_PIXELS_PER_TILE stays a placeholder until one of those lands. It only makes the
// plugin's own pixel<->tile maths self-consistent (a click converts to a tile and back to the same
// pixel). Everything drawn on the world map at the placeholder is ANCHORED AT THE RIGHT CENTRE AND
// SCALED WRONG.
//
// ---------------------------------------------------------------------------------------------
// FOUR QUESTIONS, NOT ONE
// ---------------------------------------------------------------------------------------------
//
// This file used to expose a single {@link #refusalFor}, and callers asked it about everything. That
// conflation caused real damage: ShortestPathPlugin's target resolver asked the MAP question and,
// when the map refused, threw away an answer the SCENE had already produced -- so a right-click with
// the map merely loaded silently cleared the plugin. The questions are now separate, because they
// need different things:
//
//   * IS THE MAP ON SCREEN AT ALL?          -> {@link #presenceRefusalFor}: loaded + not hidden ONLY.
//   * IS THIS CANVAS POINT ON THE MAP?      -> {@link #containmentRefusalFor}: the RECTANGLE.
//   * TURN THIS CANVAS POINT INTO A TILE    -> {@link #inversionRefusalFor}: rectangle + centre + scale.
//   * DRAW THIS TILE ONTO THE MAP           -> {@link #drawRefusalFor}: rectangle + centre + scale.
//
// A caller must ask the question it is actually asking. Using the strict one as a proxy for the
// permissive one is what deleted the scene answer.
//
// PRESENCE was split out on 2026-09-07, from the other half of the same user report ("when i open the
// worldmap and choose a location in it. its fucked up because it chooses on the gamescreen not
// worldmap"). Containment is UNANSWERABLE while the map's rectangle does not resolve, and a caller
// that cannot tell whether a click was on the map must at least be able to tell whether the map was
// on screen -- otherwise its only options are "invert a click that might be on the scene" and "take a
// scene tile the user never clicked". Presence answers that from two booleans no geometry can touch.
//
// A RESIDUAL ERROR, worth as much attention as the zoom and easier to miss. What Events pushes as the
// map CENTRE is `WM_ORIGIN + 48`, and offsets.hpp proves `WM_ORIGIN = 8*WM_CENTRE - 48`, so what we
// push is identically `8 * WM_CENTRE` -- ALWAYS A MULTIPLE OF 8 WORLD TILES. That is the
// block-quantised LOAD-WINDOW centre, not necessarily the view centre. If the client's map pans
// smoothly (one glance at the open map settles it) then a finer view centre exists that we do not
// read, and everything on the map is additionally off by up to +-4 tiles and snaps in 8-tile steps as
// the user pans. If it genuinely jumps in 8-tile steps, the centre is already exact. Unresolved
// either way, so it is stated in {@link #centreQuantisationNote} rather than assumed away.
package net.runelite.api.worldmap;

import net.runelite.api.Point;

import java.util.Locale;


public class WorldMap
{
	public static final WorldMap INSTANCE = new WorldMap();

	/**
	 * Placeholder pixels-per-tile. Not derived from the client and not derivable from the widget --
	 * see the header. Kept as a constant only so the plugin's projection maths can run at all; do not
	 * trust the scale it produces.
	 */
	public static final float PLACEHOLDER_PIXELS_PER_TILE = 4.0f;

	/** The property a live measurement can be fed in through, without a rebuild. */
	public static final String ZOOM_PROPERTY = "kewl.worldmap.pixelsPerTile";

	/**
	 * Sanity bounds on any calibration. A map that showed less than half a pixel per tile or more
	 * than 32 would be a mistyped property, not a zoom level, and a zero or negative one divides by
	 * zero inside the plugin's map maths.
	 */
	private static final float MIN_PIXELS_PER_TILE = 0.5f;
	private static final float MAX_PIXELS_PER_TILE = 32.0f;

	/**
	 * World tiles per unit of WM_CENTRE_X/Z. Derived, not chosen: offsets.hpp proves the client
	 * computes {@code origin = 8*centre - 48} on both axes and that the origin is in world tiles.
	 */
	public static final int TILES_PER_SCROLL_UNIT = 8;

	/**
	 * The shortest drag a scale may be measured from, in scroll units. The centre is quantised to
	 * {@link #TILES_PER_SCROLL_UNIT} tiles, so the last unit of any drag is worth +-1 unit of error:
	 * over 8 units that is ~12%, over 2 units it is 50% and the "measurement" is really a reading of
	 * the quantisation. 8 units is 64 world tiles, i.e. a drag of a couple of hundred pixels at any
	 * plausible scale -- long enough to be deliberate, short enough to happen while using the map.
	 */
	public static final int MIN_DRAG_SCROLL_UNITS = 8;

	/**
	 * Tiles the client is known to hold around the centre, per axis (6 scroll units, from
	 * FUN_1401cefe0's +-6 load window). Used only as a PLAUSIBILITY BOUND on a scale, never as a
	 * scale -- see {@link #scaleNote}.
	 */
	public static final int LOAD_WINDOW_TILES = 96;

	private volatile Point position = new Point(0, 0);
	private volatile float zoom = PLACEHOLDER_PIXELS_PER_TILE;
	private volatile boolean live = false;
	private volatile boolean zoomCalibrated = false;
	/** Where the scale in force came from, for the log and for {@link #calibrationProvenance}. */
	private volatile String calibrationSource;

	/**
	 * Package-private, not private, and only for the tests in this package. Everything in the client
	 * reads {@link #INSTANCE}; the mutation tests need an instance they can calibrate and clear
	 * without leaving a scale behind for the next test -- or for the running client, if these classes
	 * are ever loaded in it. A singleton whose state cannot be exercised in isolation gets tested by
	 * mutating the one every other class reads, which is worse.
	 */
	WorldMap()
	{
		String configured = System.getProperty(ZOOM_PROPERTY);
		if (configured != null && !setPixelsPerTile(parseOrNaN(configured),
			"-D" + ZOOM_PROPERTY + "=" + configured + " (a value a human measured by eye, not a"
				+ " reading off this client -- it describes ONE zoom level and is dropped the moment"
				+ " the zoom buttons are used)"))
		{
			System.out.println("[shim] " + ZOOM_PROPERTY + "=" + configured
				+ " is not a usable map scale (" + MIN_PIXELS_PER_TILE + ".." + MAX_PIXELS_PER_TILE
				+ " px/tile); staying on the placeholder " + PLACEHOLDER_PIXELS_PER_TILE);
		}
	}

	private static float parseOrNaN(String s)
	{
		try
		{
			return Float.parseFloat(s.trim());
		}
		catch (NumberFormatException e)
		{
			return Float.NaN;
		}
	}

	public Point getWorldMapPosition()
	{
		return position;
	}

	public float getWorldMapZoom()
	{
		if (!zoomCalibrated)
		{
			net.runelite.api.ShimSupport.note("WorldMap.getWorldMapZoom",
				net.runelite.api.ShimSupport.Kind.UNSUPPORTABLE,
				"reads the PLACEHOLDER " + PLACEHOLDER_PIXELS_PER_TILE + " px/tile, so everything drawn"
					+ " on the world map is at the right CENTRE and the wrong SCALE. The client has no"
					+ " zoom field (wm+0x54C4..0x54DD is fully accounted for and there is no map-zoom"
					+ " Lua binding) and the container's rectangle cannot recover one either -- we can"
					+ " read that rectangle now that loaded groups resolve canvas-absolute, and it is"
					+ " still only ONE reference point, because the map origin is a fixed +-48 tile load"
					+ " window at every zoom rather than a second point on the visible map. It is"
					+ " MEASURABLE though, and nothing blocks the measurement any more: "
					+ humanBootstrapAction(PLACEHOLDER_PIXELS_PER_TILE));
		}
		return zoom;
	}

	/** True once Events has pushed a centre read from the live world-map object; false until then. */
	public boolean isLive()
	{
		return live;
	}

	/**
	 * False while {@link #getWorldMapZoom()} is the placeholder -- i.e. while anything drawn on the
	 * world map is at the right centre and the wrong scale.
	 *
	 * <p>This IS a reason to refuse to draw and to refuse to INVERT a click, and both
	 * {@link #drawRefusalFor} and {@link #inversionRefusalFor} treat it as one. It is NOT a reason to
	 * refuse {@link #containmentRefusalFor}: asking whether a canvas point falls inside the map's
	 * rectangle needs no scale at all, and folding the scale into that question is what let a map
	 * refusal delete an answer the scene had already produced.</p>
	 */
	public boolean isZoomCalibrated()
	{
		return zoomCalibrated;
	}

	/**
	 * Feed in a MEASURED map scale, in widget pixels per world tile. Rejected -- leaving the
	 * placeholder in place and returning false -- unless it is finite and within sane bounds, because
	 * a bad scale here is silently wrong everywhere on the map rather than obviously wrong anywhere.
	 */
	public boolean setPixelsPerTile(float pixelsPerTile)
	{
		return setPixelsPerTile(pixelsPerTile, null);
	}

	/**
	 * As {@link #setPixelsPerTile(float)}, recording WHERE the number came from. The provenance is
	 * not decoration: a scale that is wrong is wrong everywhere on the map and invisible at a glance,
	 * so the one thing a reader needs is the two reference points it was computed from --
	 * {@link #dragMeasurementNote} builds that string for the drag path.
	 */
	public boolean setPixelsPerTile(float pixelsPerTile, String provenance)
	{
		if (!isUsableScale(pixelsPerTile))
		{
			return false;
		}
		this.zoom = pixelsPerTile;
		this.zoomCalibrated = true;
		this.calibrationSource = provenance;
		return true;
	}

	/** How the scale in force was arrived at, or null while it is the placeholder. */
	public String calibrationProvenance()
	{
		return calibrationSource;
	}

	/**
	 * Whether a number is a map scale at all. Pure, so the bounds are tested without mutating the
	 * singleton every other class reads. Stated as a pair of inclusive bounds on purpose: NaN
	 * compares false against both, so it is rejected without a special case, where the mirror-image
	 * {@code < lo || > hi} test would let NaN through and turn every map pixel into NaN.
	 */
	static boolean isUsableScale(float pixelsPerTile)
	{
		return pixelsPerTile >= MIN_PIXELS_PER_TILE && pixelsPerTile <= MAX_PIXELS_PER_TILE;
	}

	/**
	 * Called by Events each frame with the map's centre in world tiles. The zoom argument carries
	 * whatever scale is in force -- the placeholder, or a calibration someone measured.
	 */
	public void set(Point position, float zoom)
	{
		this.position = position;
		this.zoom = zoom;
		this.live = true;
	}

	/**
	 * The centre, without touching the scale. What the per-frame push actually wants: routing the
	 * zoom back through {@link #getWorldMapZoom()} every frame only existed to hand the same number
	 * back, and it dragged the uncalibrated-scale note along for the ride.
	 */
	public void setPosition(Point position)
	{
		this.position = position;
		this.live = true;
	}

	/** The scale in force, with no note fired -- for code that is comparing scales, not drawing at one. */
	public float pixelsPerTileQuiet()
	{
		return zoom;
	}

	/**
	 * Drop a calibration and fall back to the placeholder. Called when the evidence behind the stored
	 * value stops applying -- the user clicked ZOOM_IN/ZOOM_OUT, so the scale that was measured is
	 * the scale of a zoom level that is no longer on screen. Falling back to the placeholder makes
	 * every map surface stand down again (they gate on {@link #isZoomCalibrated()}), which is the
	 * honest state until the next drag re-measures it.
	 */
	public void clearCalibration()
	{
		this.zoom = PLACEHOLDER_PIXELS_PER_TILE;
		this.zoomCalibrated = false;
		this.calibrationSource = null;
	}

	/**
	 * The scale a completed map drag implies, or NaN when the drag does not support one. Pure, so the
	 * arithmetic and -- more importantly -- the REFUSALS are tested rather than argued about.
	 *
	 * <p>{@code pixelsMoved} is how far the cursor travelled on the canvas and {@code scrollUnits} is
	 * how far WM_CENTRE moved over the same interval, both as unsigned distances (the sign is the pan
	 * direction and carries no scale). One scroll unit is {@link #TILES_PER_SCROLL_UNIT} world tiles,
	 * so the scale is {@code pixels / (8 * units)}.</p>
	 *
	 * <p>This is a MEASUREMENT OF THE CLIENT'S BEHAVIOUR, not a derivation from its data, and it is
	 * only as good as the three assumptions in this file's header (1:1 drag, no inertia, no zoom
	 * change mid-drag). Short drags are refused because the centre is quantised: see
	 * {@link #MIN_DRAG_SCROLL_UNITS}.</p>
	 */
	public static float pixelsPerTileFromDrag(double pixelsMoved, int scrollUnits)
	{
		int units = Math.abs(scrollUnits);
		if (units < MIN_DRAG_SCROLL_UNITS || !(pixelsMoved > 0) || Double.isNaN(pixelsMoved)
			|| Double.isInfinite(pixelsMoved))
		{
			return Float.NaN;
		}
		return (float) (pixelsMoved / ((double) units * TILES_PER_SCROLL_UNIT));
	}

	/**
	 * Take a completed drag as the map scale. Returns true only when the drag supported a scale AND
	 * that scale is sane; a refused drag leaves whatever was in force untouched, so a stray click
	 * cannot wipe out a good calibration.
	 */
	public boolean calibrateFromDrag(double pixelsMoved, int scrollUnits)
	{
		return calibrateFromDrag(pixelsMoved, scrollUnits, null);
	}

	/** As above, keeping the two reference points the number came from. See {@link #dragMeasurementNote}. */
	public boolean calibrateFromDrag(double pixelsMoved, int scrollUnits, String provenance)
	{
		return setPixelsPerTile(pixelsPerTileFromDrag(pixelsMoved, scrollUnits), provenance);
	}

	/**
	 * How far the cursor must travel, at the scale currently believed, for a drag to clear
	 * {@link #MIN_DRAG_SCROLL_UNITS}. Pure. This is the number the human-facing instruction needs: at
	 * the 4.0 placeholder it is 256 px, which is a decisive swipe rather than a nudge, and saying
	 * "drag a good distance" instead of saying "about 256 px" is how a user ends up dragging four
	 * times and concluding the feature is broken.
	 *
	 * <p>It is an ESTIMATE of an instruction, not a threshold -- the real threshold is on the scroll
	 * units, which is exactly the quantity whose pixel size is unknown. When the belief is the
	 * placeholder the estimate is as wrong as the placeholder is, so it is always phrased with
	 * "about".</p>
	 */
	public static int minDragPixels(float pixelsPerTile)
	{
		float scale = isUsableScale(pixelsPerTile) ? pixelsPerTile : PLACEHOLDER_PIXELS_PER_TILE;
		return Math.round(MIN_DRAG_SCROLL_UNITS * TILES_PER_SCROLL_UNIT * scale);
	}

	/**
	 * The ONE thing a human has to do to unblock every world-map surface, in one sentence. Pure.
	 *
	 * <p>Every uncalibrated refusal in this file ends with this. The refusals used to say "drag the
	 * open map a good distance to calibrate it", which names an action without naming what makes it
	 * count -- and the drag threshold is on a quantity (scroll units) the user cannot see. This says
	 * the distance, says it must be one unbroken motion, and says why: releasing early restarts the
	 * measurement, because the pixel travel and the tile travel have to be read against the same
	 * button press.</p>
	 */
	public static String humanBootstrapAction(float believedScale)
	{
		return "OPEN THE WORLD MAP AND DRAG IT ONCE -- press inside the map, move about "
			+ minDragPixels(believedScale) + " px in one unbroken motion (roughly a third of the"
			+ " screen), then release. That single drag measures the scale and switches every"
			+ " world-map surface on. Opening the map is what loads its interface group, and a loaded"
			+ " group is what makes the container's rectangle a canvas rectangle (measured live"
			+ " 2026-09-07: the minimap resolved at (1156,8) 152x152 on a 1356-wide canvas), so"
			+ " nothing else is in the way. If the drag will not take, pass a measured value instead"
			+ " with -D" + ZOOM_PROPERTY + "=<pixels per tile>";
	}

	/**
	 * The accepted measurement, with BOTH of the reference points it came from. Pure.
	 *
	 * <p>A scale is the one number here that is wrong invisibly: a path drawn at half scale starts in
	 * the right place, points the right way, and ends somewhere else. So the log must carry enough to
	 * re-do the division by hand -- the two cursor pixels, the two WM_CENTRE readings, the tile count
	 * they imply and the quotient -- rather than just the answer.</p>
	 */
	public static String dragMeasurementNote(int fromX, int fromY, int toX, int toY,
		int fromCentreX, int fromCentreZ, int toCentreX, int toCentreZ,
		double pixelsMoved, int scrollUnits, float pixelsPerTile)
	{
		int units = Math.abs(scrollUnits);
		return String.format(Locale.ROOT,
			"%.2f px/tile, measured from TWO REFERENCE POINTS on the open map: the CURSOR went"
				+ " (%d,%d) -> (%d,%d) = %.0f px, and WM_CENTRE went (%d,%d) -> (%d,%d) = %d scroll"
				+ " units = %d world tiles; %.0f / (%d * %d) = %.2f. This measures the client's DRAG"
				+ " BEHAVIOUR, not a value read out of it: it assumes the map pans 1:1 with the cursor,"
				+ " does not glide after the release, and did not change zoom mid-drag",
			pixelsPerTile, fromX, fromY, toX, toY, pixelsMoved,
			fromCentreX, fromCentreZ, toCentreX, toCentreZ, units,
			units * TILES_PER_SCROLL_UNIT,
			pixelsMoved, units, TILES_PER_SCROLL_UNIT, pixelsPerTile);
	}

	// -- the refusals ------------------------------------------------------------------------------
	//
	// FOUR questions now, four entry points, three underlying levels. See the header: conflating them
	// is what let a world-map refusal throw away a target the SCENE had already resolved.
	//
	// The one added on 2026-09-07 is the WEAKEST and the most load-bearing:
	//
	//   * IS THE MAP ON SCREEN AT ALL?          -> presenceRefusal: presence + hidden ONLY.
	//   * IS THIS CANVAS POINT ON THE MAP?      -> rectangleRefusal: presence + the RECTANGLE.
	//   * TURN THIS CANVAS POINT INTO A TILE    -> inversionRefusal: rectangle + centre + scale.
	//   * DRAW THIS TILE ONTO THE MAP           -> drawRefusal: rectangle + centre + scale.
	//
	// Presence exists as its own question because a caller has to be able to ask "is the map up?"
	// WITHOUT touching the geometry chain. ShortestPathPlugin's target resolver needs exactly that:
	// its scene fallback is gated on the map NOT being on screen, and that gate must not be able to
	// fail because a rectangle failed to resolve -- see selectionSourceFor.

	/**
	 * Question (a): is the world map ON SCREEN? Or rather, why is it not -- null means it is. Pure.
	 *
	 * <p>Deliberately reads nothing but the two facts that survive a broken parent chain: whether the
	 * interface group is loaded, and whether the client says the container is hidden. No width, no
	 * height, no {@code isCanvasAbsolute}. A caller that must behave differently while the map is up
	 * needs an answer that CANNOT be perturbed by the geometry derivation, because geometry is exactly
	 * what is unreliable for this group (the header's cross-group parenting note).</p>
	 *
	 * <p>Note the asymmetry this creates, and it is the point: presence answering "on screen" does NOT
	 * imply the rectangle is usable, while the rectangle being usable DOES imply presence, because
	 * {@link #rectangleRefusal} starts by asking this.</p>
	 */
	public static String presenceRefusal(boolean present, boolean hidden)
	{
		if (!present)
		{
			return "the world map interface is not loaded (on this build its group only loads once the"
				+ " map has been opened, so this is the ordinary state before the first open, not a"
				+ " fault)";
		}
		if (hidden)
		{
			return "the world map is CLOSED (its group stays loaded on this build, so a non-null"
				+ " widget is not an open map)";
		}
		return null;
	}

	/** {@link #presenceRefusal} for a live widget. A null widget is the "never opened" case. */
	public static String presenceRefusalFor(net.runelite.api.widgets.Widget mapContainer)
	{
		return presenceRefusal(mapContainer != null, mapContainer != null && mapContainer.isHidden());
	}

	/**
	 * True when the world map is on screen -- question (a) as a predicate. See
	 * {@link #presenceRefusal} for why this is kept clear of the geometry.
	 */
	public static boolean isOnScreen(net.runelite.api.widgets.Widget mapContainer)
	{
		return presenceRefusalFor(mapContainer) == null;
	}

	/**
	 * Why the map container's rectangle may not be used as a CANVAS RECTANGLE, or null when it may.
	 * Pure.
	 *
	 * <p>This is the WEAKEST of the map's questions and the only one a containment test needs: "does
	 * this canvas point fall inside the map" is answered by the rectangle alone. It says nothing
	 * about whether the point can be turned into a tile -- that needs {@link #inversionRefusal}.</p>
	 *
	 * <ol>
	 *   <li>PRESENT. On this build the world-map group is not even loaded until the map has been
	 *       opened once ("[shim] widgetAbs WORLDMAP 595:7 is not loaded", live 2026-09-07), so a null
	 *       widget is the ordinary pre-first-open state, not a fault.</li>
	 *   <li>SHOWN. Once the group HAS loaded it stays loaded with the map closed, so a non-null widget
	 *       is not an open map -- gating on non-null alone is what let map overlays run over the scene
	 *       with the map shut.</li>
	 *   <li>A real rectangle. Zero-sized is the client saying the interface is not built.</li>
	 *   <li>CANVAS-ABSOLUTE bounds. The stored x/y of a component are relative to its parent; the
	 *       upstream maths adds {@code rect.x} to a canvas pixel and inverts a canvas click against
	 *       it, so a parent-relative rectangle is a CONSTANT offset injected into both.</li>
	 * </ol>
	 */
	public static String rectangleRefusal(boolean present, boolean hidden, boolean canvasAbsolute,
		int width, int height)
	{
		// Questions (a) then the rectangle. Delegating rather than repeating the two presence checks
		// keeps ONE definition of "the map is on screen": a caller that gates on presence and a caller
		// that gates on the rectangle must never disagree about whether the map is up.
		String presence = presenceRefusal(present, hidden);
		if (presence != null)
		{
			return presence;
		}
		if (width <= 0 || height <= 0)
		{
			return "the map container resolved with no rectangle (" + width + "x" + height + ")";
		}
		if (!canvasAbsolute)
		{
			return "the map container's rectangle is PARENT-RELATIVE (its parent chain did not reach a"
				+ " root). Drawing at it lands a fixed vector away from the map and inverts a click the"
				+ " same distance wrong. This is UNEXPECTED as of 2026-09-07: the chain derivation is"
				+ " proven to work for a loaded group -- the minimap resolved canvas-absolute at"
				+ " (1156,8) 152x152 on a 1356-wide canvas -- so if the world-map group is loaded and"
				+ " still reads relative, the cause is that group specifically (its root is parented"
				+ " through the client's component table), not the derivation";
		}
		return null;
	}

	/**
	 * Why a canvas point cannot be turned into a world tile, or null when it can. Pure.
	 *
	 * <p>The rectangle plus the centre plus the scale: {@code calculateMapPoint} subtracts the
	 * rectangle's origin from the click, divides the remainder by the scale, and adds the centre. All
	 * three terms are load-bearing, and an uncalibrated scale here does not produce a slightly wrong
	 * tile -- it produces a tile a long way off, which auto-walk then walks to.</p>
	 */
	public static String inversionRefusal(boolean present, boolean hidden, boolean canvasAbsolute,
		int width, int height, boolean live, boolean calibrated)
	{
		String rect = rectangleRefusal(present, hidden, canvasAbsolute, width, height);
		if (rect != null)
		{
			return rect;
		}
		if (!live)
		{
			return "the map centre is not live (the world-map object was not readable this frame)";
		}
		if (!calibrated)
		{
			return "the map scale is the PLACEHOLDER " + PLACEHOLDER_PIXELS_PER_TILE + " px/tile, not a"
				+ " measurement, and the scale is the divisor that turns a map pixel back into a tile"
				+ " -- inverting a click at a placeholder resolves it to a tile the user did not click."
				+ " To fix it: " + humanBootstrapAction(PLACEHOLDER_PIXELS_PER_TILE);
		}
		return null;
	}

	/**
	 * Why nothing may be drawn on the world map, or null when it may. Pure.
	 *
	 * <p>Drawing needs the same three terms as inverting -- the forward projection is the inverse of
	 * {@code calculateMapPoint} and multiplies by the same scale -- so this delegates. They are kept
	 * as separate names anyway, because a caller asking "can I draw" and a caller asking "can I
	 * invert" are asking different questions even where the answer coincides today, and the next
	 * person to need only one of them must not have to work out which one this was.</p>
	 */
	public static String drawRefusal(boolean present, boolean hidden, boolean canvasAbsolute,
		int width, int height, boolean live, boolean calibrated)
	{
		return inversionRefusal(present, hidden, canvasAbsolute, width, height, live, calibrated);
	}

	/**
	 * The historical name for {@link #drawRefusal}, kept so every existing caller keeps compiling and
	 * keeps its behaviour. New code should name the question it is asking.
	 */
	public static String geometryRefusal(boolean present, boolean hidden, boolean canvasAbsolute,
		int width, int height, boolean live, boolean calibrated)
	{
		return drawRefusal(present, hidden, canvasAbsolute, width, height, live, calibrated);
	}

	/**
	 * "Is a canvas point on this map?" for a live widget -- the RECTANGLE question only. A null widget
	 * is a legitimate input: it is the "the interface is not loaded" case.
	 *
	 * <p>Use this, and only this, for hit-testing: a menu deciding whether to OFFER a map action, or
	 * a walker deciding whether a click would land on the open map. Nothing here depends on the
	 * centre or the scale, so an uncalibrated map still answers it -- which is the point.</p>
	 */
	public static String containmentRefusalFor(net.runelite.api.widgets.Widget mapContainer)
	{
		return rectangleRefusal(
			mapContainer != null,
			mapContainer != null && mapContainer.isHidden(),
			mapContainer != null && mapContainer.isCanvasAbsolute(),
			mapContainer == null ? 0 : mapContainer.getWidth(),
			mapContainer == null ? 0 : mapContainer.getHeight());
	}

	/** "Can I turn a click on this map into a world tile?" for a live widget. See {@link #inversionRefusal}. */
	public static String inversionRefusalFor(net.runelite.api.widgets.Widget mapContainer, WorldMap state)
	{
		return inversionRefusal(
			mapContainer != null,
			mapContainer != null && mapContainer.isHidden(),
			mapContainer != null && mapContainer.isCanvasAbsolute(),
			mapContainer == null ? 0 : mapContainer.getWidth(),
			mapContainer == null ? 0 : mapContainer.getHeight(),
			state != null && state.isLive(),
			state != null && state.isZoomCalibrated());
	}

	/** "Can I draw a world tile onto this map?" for a live widget. See {@link #drawRefusal}. */
	public static String drawRefusalFor(net.runelite.api.widgets.Widget mapContainer, WorldMap state)
	{
		return inversionRefusalFor(mapContainer, state);
	}

	/**
	 * {@link #drawRefusalFor} under its historical name. Kept because the overlays and the plugin all
	 * call it; a caller that only needs the rectangle should move to
	 * {@link #containmentRefusalFor}.
	 */
	public static String refusalFor(net.runelite.api.widgets.Widget mapContainer, WorldMap state)
	{
		return drawRefusalFor(mapContainer, state);
	}

	/**
	 * The residual error that survives a perfect rectangle and a perfect scale: what we push as the
	 * centre is {@code 8 * WM_CENTRE}, so it can only ever be a multiple of 8 world tiles. Pure, and
	 * stated as a live-answerable question rather than as a fact, because which of the two answers is
	 * true is decided by one glance at the open map and nobody has taken it.
	 */
	public static String centreQuantisationNote(int centreX, int centreY)
	{
		return "map centre (" + centreX + "," + centreY + ") is 8 * WM_CENTRE and is therefore ALWAYS a"
			+ " multiple of " + TILES_PER_SCROLL_UNIT + " tiles -- it is the block-quantised LOAD-WINDOW"
			+ " centre. If the map pans SMOOTHLY there is a finer view centre in the client that is not"
			+ " read, and everything on the map is additionally off by up to +-"
			+ (TILES_PER_SCROLL_UNIT / 2) + " tiles and snaps in " + TILES_PER_SCROLL_UNIT
			+ "-tile steps as the user pans; if the map pans in " + TILES_PER_SCROLL_UNIT
			+ "-tile jumps, this centre is exact. One look at the open map decides it";
	}

	/**
	 * The world-map object is gone (Events' native returned nothing), so the centre last pushed is
	 * stale. Overlays and map clicks gate on {@link #isLive()} and must not act on it.
	 */
	public void clear()
	{
		live = false;
	}

	/**
	 * One log-ready line saying what the world-map overlays are actually drawing, given the map
	 * widget's size. Pure (no client, no natives) so the wording of the warning is tested rather than
	 * argued about; kewl.rl.Events prints it once, when the map is first open.
	 */
	public static String scaleNote(int widgetWidth, int widgetHeight, float pixelsPerTile,
		boolean calibrated)
	{
		String span = String.format(Locale.ROOT, "%dx%d px = %.0fx%.0f tiles at %.2f px/tile",
			widgetWidth, widgetHeight,
			widgetWidth / pixelsPerTile, widgetHeight / pixelsPerTile, pixelsPerTile);
		if (calibrated)
		{
			return span + " -- measured scale, world-map drawing is to scale" + loadWindowCheck(
				widgetWidth, pixelsPerTile);
		}
		return span + loadWindowCheck(widgetWidth, pixelsPerTile)
			+ " -- PLACEHOLDER scale: the client has no zoom field, and the container's rectangle"
			+ " (which we CAN read now that loaded groups resolve canvas-absolute) is only one"
			+ " reference point, because the map origin is a fixed +-48 tile load window at every"
			+ " zoom. So the path is drawn at the right CENTRE and the wrong SCALE. "
			+ humanBootstrapAction(pixelsPerTile) + " -- see -D" + ZOOM_PROPERTY;
	}

	/**
	 * A PLAUSIBILITY BOUND on a scale from the load window, appended to the scale note when it fails.
	 * Not a derivation and it must never be turned into one: the client loads map squares over
	 * {@code centre +- 6} scroll units, so only about {@link #LOAD_WINDOW_TILES} tiles per axis are
	 * guaranteed resident (rounded out to 64-tile squares, so somewhat more in practice). A scale
	 * claiming the widget shows far more tiles than that is claiming the map draws terrain the client
	 * has not loaded, which is a reason to DISTRUST the scale. It is not a measurement of the right
	 * one -- the load window is the same 96 tiles at every zoom level, which is exactly why the
	 * rectangle cannot recover a scale in the first place.
	 */
	private static String loadWindowCheck(int widgetWidth, float pixelsPerTile)
	{
		if (widgetWidth <= 0 || !(pixelsPerTile > 0))
		{
			return "";
		}
		int tiles = (int) Math.ceil(widgetWidth / pixelsPerTile);
		if (tiles <= LOAD_WINDOW_TILES)
		{
			return "";
		}
		return String.format(Locale.ROOT,
			" [IMPLAUSIBLE: %d tiles across, but the client only holds ~%d tiles per axis around the"
				+ " centre, so the real scale is probably at least %.2f px/tile -- a bound, not a"
				+ " measurement]",
			tiles, LOAD_WINDOW_TILES, (double) widgetWidth / LOAD_WINDOW_TILES);
	}
}

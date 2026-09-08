// Shim of net.runelite.api.Perspective (BSD-2, RuneLite), adapted to KewlKlient.
//
// The math that is ours (minimap projection) is ported verbatim. The part that is the game's (screen
// projection) goes through kewl's native projectFine, which calls the game's own worldToScreen -- so
// the game's camera maths stays the single source of truth. There is no heightmap yet, so
// getTileHeight is the ground under the local player (Game.groundHeightGuess) for every tile; actors
// carry their own exact height and Actor's projection helpers use it.
package net.runelite.api;

import net.runelite.api.widgets.Widget;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import net.runelite.api.coords.LocalPoint;

public class Perspective
{
	public static final double UNIT = 0.0030679615d; // ~pi/1024
	public static final double UNIT14 = 3.834951969714103E-4D; // ~pi/8192

	public static final int LOCAL_COORD_BITS = 7;
	public static final int LOCAL_TILE_SIZE = 1 << LOCAL_COORD_BITS;
	public static final int LOCAL_HALF_TILE_SIZE = LOCAL_TILE_SIZE / 2;

	public static final int SCENE_SIZE = Constants.SCENE_SIZE;

	/**
	 * The yaw circle in the units this API reports: 2048 to the turn, one unit = {@link #UNIT}
	 * radians. That is the contract, not a choice -- {@code PathMinimapOverlay} is an unchanged
	 * upstream port and rotates its marker by {@code client.getCameraYawTarget() * Perspective.UNIT},
	 * which is only a whole turn if the yaw runs 0..2047.
	 *
	 * <p>This class used to rotate the minimap through a 16384-entry SINE14 table indexed by
	 * {@code yaw & 0x3fff}, i.e. it read a 2048-unit angle as a 16384-unit one and rotated by an
	 * EIGHTH of the real yaw. That was invisible while {@code getCameraYawTarget()} was hardcoded to
	 * 0 (an eighth of nothing is nothing) and would have been wrong on every frame the moment a real
	 * yaw arrived, in the same picture as the overlay's own correctly-scaled marker rotation.</p>
	 */
	public static final int YAW_UNITS = 2048;

	private static final int[] SINE = new int[YAW_UNITS];
	private static final int[] COSINE = new int[YAW_UNITS];

	static
	{
		for (int i = 0; i < SINE.length; i++)
		{
			SINE[i] = (int) Math.round(65536d * Math.sin(UNIT * i));
			COSINE[i] = (int) Math.round(65536d * Math.cos(UNIT * i));
		}
	}

	private Perspective()
	{
	}

	@Nullable
	public static Point localToCanvas(Client client, LocalPoint point, int plane)
	{
		return localToCanvas(client, point, plane, 0);
	}

	/**
	 * No heightmap yet, so {@code plane} is ignored and every level projects at ground height.
	 */
	@Nullable
	public static Point localToCanvas(Client client, LocalPoint point, int plane, int heightOffset)
	{
		int tileHeight = getTileHeight(client, point, plane);
		return localToCanvas(client, point.getX(), point.getY(), tileHeight - heightOffset);
	}

	/**
	 * The fine coordinate units of LocalPoint (1/128 tile) are exactly what kewl's projection native
	 * takes, so this is a direct hand-off.
	 */
	@Nullable
	public static Point localToCanvas(Client client, int x, int y, int z)
	{
		java.awt.Point fine = kewl.api.Game.projectFine(x, z, y);
		return fine == null ? null : new Point(fine.x, fine.y);
	}

	public static int getTileHeight(Client client, LocalPoint point, int plane)
	{
		// No heightmap yet (no scene tile-height offset is derived -- see client/offsets.hpp), so the
		// best available answer is the ground under the local player, read off its own entity
		// (ENTITY_FINE_H, live 2026-09-05): exact on your tile, right on flat ground near you, off
		// by the slope elsewhere -- and ~290 px better than the datum 0 this returned before, which
		// drew every path tile well below the ground. kewl.api.Game.groundHeightGuess documents it.
		return kewl.api.Game.groundHeightGuess();
	}

	@Nullable
	public static Polygon getCanvasTilePoly(Client client, LocalPoint localLocation)
	{
		return getCanvasTilePoly(client, localLocation, 0);
	}

	/**
	 * Upstream's third parameter is a HEIGHT OFFSET (zOffset, raised above the ground), not a plane
	 * -- an earlier shim revision named it plane and ignored it. The ground is Game.groundHeightGuess
	 * (the local player's own height: no heightmap); an Actor draws its own tile through
	 * {@link Actor#getCanvasTilePoly} at its own height instead.
	 */
	@Nullable
	public static Polygon getCanvasTilePoly(Client client, LocalPoint localLocation, int zOffset)
	{
		int sceneX = localLocation.getSceneX();
		int sceneY = localLocation.getSceneY();
		return kewl.api.Game.tileOutline(sceneX, sceneY, kewl.api.Game.groundHeightGuess() - zOffset);
	}

	/**
	 * Upstream shape: the size x size tile square CENTRED on {@code localLocation}, at ground height.
	 * (An NPC's LocalPoint is its footprint centre upstream, so this is how a 2x2 NPC's tiles are drawn.)
	 */
	@Nullable
	public static Polygon getCanvasTileAreaPoly(Client client, LocalPoint localLocation, int size)
	{
		// Height: the ground under whichever entity stands on or next to this tile (Game.heightNear),
		// falling back to the local player's. NPC Indicators' true-tile / south-west-tile styles are
		// the callers, and the NPC itself is always within a tile of the tile it asks about, so this
		// is exact there -- and no worse than groundHeightGuess anywhere else.
		return getCanvasTileAreaPoly(client, localLocation, size,
			kewl.api.Game.heightNear(localLocation.getSceneX(), localLocation.getSceneY()));
	}

	/**
	 * Shim-only overload: the size x size tile square centred on {@code localLocation} at an explicit
	 * ground height (an actor's own, usually). Null when any corner is off screen.
	 */
	@Nullable
	public static Polygon getCanvasTileAreaPoly(Client client, LocalPoint localLocation, int size, int height)
	{
		return centredTileAreaPoly(localLocation.getX(), localLocation.getY(), size, height);
	}

	/**
	 * The size x size tile square CENTRED on a fine position, at an explicit height. This is upstream's
	 * shape for an actor's "highlight tile": centred on the RENDERED position, so the square slides
	 * with the walk animation instead of snapping to the server tile (that is "true tile", and the
	 * reason the two styles are two different pictures). Null when any corner is off screen.
	 */
	@Nullable
	static Polygon centredTileAreaPoly(int fineX, int fineY, int size, int height)
	{
		int s = Math.max(1, size);
		return areaPoly(fineX - s * LOCAL_HALF_TILE_SIZE, fineY - s * LOCAL_HALF_TILE_SIZE, s, height);
	}

	@Nullable
	private static Polygon areaPoly(int fineX0, int fineY0, int size, int height)
	{
		int span = size * LOCAL_TILE_SIZE;
		java.awt.Point a = kewl.api.Game.projectFine(fineX0, height, fineY0);
		java.awt.Point b = kewl.api.Game.projectFine(fineX0 + span, height, fineY0);
		java.awt.Point c = kewl.api.Game.projectFine(fineX0 + span, height, fineY0 + span);
		java.awt.Point d = kewl.api.Game.projectFine(fineX0, height, fineY0 + span);
		if (a == null || b == null || c == null || d == null)
		{
			return null;
		}
		Polygon poly = new Polygon();
		poly.addPoint(a.x, a.y);
		poly.addPoint(b.x, b.y);
		poly.addPoint(c.x, c.y);
		poly.addPoint(d.x, d.y);
		return poly;
	}

	/**
	 * Upstream shape: the canvas point for text centred over a local point raised by {@code zOffset}
	 * fine units; x is shifted left by half the text width, y is the baseline. Ground height is the
	 * guess (see getTileHeight); {@link Actor#getCanvasTextLocation} uses the actor's own height.
	 */
	@Nullable
	public static Point getCanvasTextLocation(Client client, Graphics2D graphics, LocalPoint localLocation,
		@Nullable String text, int zOffset)
	{
		int plane = client.getPlane();
		Point p = localToCanvas(client, localLocation, plane, zOffset);
		if (p == null)
		{
			return null;
		}
		int width = text == null ? 0 : graphics.getFontMetrics().stringWidth(text);
		return new Point(p.getX() - width / 2, p.getY());
	}

	/** Upstream shape: top-left of {@code image} centred on the raised local point. */
	@Nullable
	public static Point getCanvasImageLocation(Client client, LocalPoint localLocation, BufferedImage image,
		int zOffset)
	{
		Point p = localToCanvas(client, localLocation, client.getPlane(), zOffset);
		if (p == null)
		{
			return null;
		}
		return new Point(p.getX() - image.getWidth() / 2, p.getY() - image.getHeight() / 2);
	}

	/**
	 * AN APPROXIMATION of a model's convex hull, because no model/hull offsets exist on this build:
	 * the 2D hull of the eight corners of a prism -- footprint {@code size} tiles square centred on
	 * (fineX, fineY) at {@code height}, top at {@code height - logicalHeight} (negative = up). Every
	 * corner goes through the game's own projection, so the shape rotates with the camera by
	 * construction. Null when fewer than three corners project on screen.
	 */
	@Nullable
	static Shape approximateHull(int fineX, int fineY, int height, int size, int logicalHeight)
	{
		int half = Math.max(1, size) * LOCAL_HALF_TILE_SIZE;
		int top = height - logicalHeight;
		List<java.awt.Point> pts = new ArrayList<>(8);
		for (int h : new int[] { height, top })
		{
			for (int dx : new int[] { -half, half })
			{
				for (int dy : new int[] { -half, half })
				{
					java.awt.Point p = kewl.api.Game.projectFine(fineX + dx, h, fineY + dy);
					if (p != null)
					{
						pts.add(p);
					}
				}
			}
		}
		if (pts.size() < 3)
		{
			return null;
		}
		return convexHull(pts);
	}

	/**
	 * Andrew's monotone chain: the convex hull of a point set as a counter-clockwise Polygon. Pure
	 * 2D, no natives -- unit-tested. Fewer than three distinct points degenerate to whatever there is.
	 */
	public static Polygon convexHull(List<java.awt.Point> points)
	{
		List<java.awt.Point> p = new ArrayList<>(points);
		p.sort((a, b) -> a.x != b.x ? Integer.compare(a.x, b.x) : Integer.compare(a.y, b.y));
		int n = p.size();
		Polygon poly = new Polygon();
		if (n < 3)
		{
			for (java.awt.Point q : p)
			{
				poly.addPoint(q.x, q.y);
			}
			return poly;
		}
		java.awt.Point[] hull = new java.awt.Point[2 * n];
		int k = 0;
		for (int i = 0; i < n; i++)
		{
			while (k >= 2 && cross(hull[k - 2], hull[k - 1], p.get(i)) <= 0)
			{
				k--;
			}
			hull[k++] = p.get(i);
		}
		for (int i = n - 2, t = k + 1; i >= 0; i--)
		{
			while (k >= t && cross(hull[k - 2], hull[k - 1], p.get(i)) <= 0)
			{
				k--;
			}
			hull[k++] = p.get(i);
		}
		// The chain closes on its start point; drop the duplicate.
		for (int i = 0; i < k - 1; i++)
		{
			poly.addPoint(hull[i].x, hull[i].y);
		}
		return poly;
	}

	private static long cross(java.awt.Point o, java.awt.Point a, java.awt.Point b)
	{
		return (long) (a.x - o.x) * (b.y - o.y) - (long) (a.y - o.y) * (b.x - o.x);
	}

	// One line per session each, so a refusal AND the later lift both get said: the widget is
	// unresolved before the interface loads, and the interesting event is it becoming usable.
	private static boolean loggedMinimapRefusal;
	private static boolean loggedMinimapResolved;

	@Nullable
	public static Point localToMinimap(Client client, LocalPoint point)
	{
		final int r = 20 << LOCAL_COORD_BITS;
		final double s = 4d / client.getMinimapZoom();
		return localToMinimap(client, point, (int) (r * s));
	}

	/**
	 * Ported from RuneLite, with the camera focus pinned to the local player (the camera-focus entity
	 * is not readable yet) and the minimap draw widget resolved the same way the plugin does it.
	 */
	@Nullable
	public static Point localToMinimap(Client client, LocalPoint point, int distance)
	{
		// The local player is null across logout, and this is called from per-frame overlay renders;
		// returning null is what the @Nullable contract is for (PathTileOverlay's callers already
		// treat a null as "not this frame").
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return null;
		}
		LocalPoint focus = localPlayer.getLocalLocation();

		final int dx = point.getX() - focus.getX();
		final int dy = point.getY() - focus.getY();
		if (dx * dx + dy * dy >= distance * distance)
		{
			return null;
		}

		Widget minimapDrawWidget = client.getMinimapDrawWidget();
		if (minimapDrawWidget == null || minimapDrawWidget.isHidden())
		{
			return null;
		}

		final double zoom = client.getMinimapZoom() / LOCAL_TILE_SIZE;
		final int x = (int) (dx * zoom);
		final int y = (int) (dy * zoom);

		// 0..2047, the units getCameraYawTarget() is defined in -- see YAW_UNITS for what this used to
		// mask with and why the bug was invisible while the yaw was always zero.
		final int angle = client.getCameraYawTarget() & (YAW_UNITS - 1);

		final int sin = SINE[angle];
		final int cos = COSINE[angle];

		final int rx = cos * x + sin * y >> 16;
		final int ry = sin * x - cos * y >> 16;

		Point loc = minimapDrawWidget.getCanvasLocation();
		final int w = minimapDrawWidget.getWidth();
		final int h = minimapDrawWidget.getHeight();
		// RE-CHECKED 2026-09-06, after the widget native was fixed: the component pointer is the SECOND
		// half of each 16-byte shared_ptr entry, and the probe that concluded widgets were broken had
		// read +0 and seen every component as 1x1. Widgets return real data now, so this is no longer a
		// blanket refusal -- the rectangle is judged every frame and the minimap overlays light up on
		// their own the moment it is usable. Only the two failures {@link #minimapRefusal} names still
		// refuse, and the log line says WHICH of the two it is, because the fixes differ:
		//   SIZE 0x0             the widget did not resolve at all (wrong group for this layout mode,
		//                        or that group is not loaded) -- a widget/ids problem.
		//   POSITION (0,0)       ClientState.getWidget takes a single packed id's stored x/y as canvas
		//   with a real size     coordinates and accumulates no parent offsets, the one thing
		//                        client/offsets.hpp still records as unverified. The minimap sits in
		//                        the top RIGHT of the canvas, never at the origin, so (0,0) under a
		//                        real size can only be that -- a parent-accumulation problem.
		// Drawing anyway piles every minimap name in the canvas's top-left corner, over the game (seen
		// live 2026-09-06 with "Draw names on minimap" on), so refusing is still right for those two.
		// Placement is part of the refusal, not a footnote. A minimap whose rectangle lands in the
		// left half of the canvas is reporting a PARENT-RELATIVE x (live 2026-09-06: (53,8) on a
		// 1356px canvas, while the minimap really sits near x=1150). Drawing the right shape in the
		// wrong corner is worse than drawing nothing, so this now stops the overlay exactly like the
		// other two failures do.
		// The rectangle's HONESTY is now a measurement, not a heuristic: the widget says whether its
		// parent chain reached a root, and ClientState.getMinimapDrawWidget already returns null when
		// it did not. Re-checked here anyway because this is the drawing site and a silent wrong
		// placement is the failure being prevented -- but it means the left-half placement test below
		// goes back to being the WARNING its own javadoc always said it was, not a refusal. It has to:
		// it is a guess about where minimaps live, it can only ever catch a relative x that happens to
		// land in the left half, and now that a real completeness flag exists a guess must not be able
		// to veto it. (In fixed mode the minimap sits near x=844 on a 1314px canvas -- a relative
		// rectangle sails straight past that test, which is why the guess was never sufficient.)
		if (!minimapDrawWidget.isCanvasAbsolute())
		{
			if (!loggedMinimapRefusal)
			{
				loggedMinimapRefusal = true;
				System.out.println("[shim] minimap widget: its rectangle is PARENT-RELATIVE (the parent"
					+ " chain did not reach a root) -- minimap overlays stay off");
			}
			return null;
		}
		String refusal = minimapRefusal(loc.getX(), loc.getY(), w, h);
		if (refusal != null)
		{
			if (!loggedMinimapRefusal)
			{
				loggedMinimapRefusal = true;
				System.out.println("[shim] minimap widget: " + refusal + " -- minimap overlays stay off");
			}
			return null;
		}
		if (!loggedMinimapResolved)
		{
			loggedMinimapResolved = true;
			int[] canvas = kewl.Natives.viewport();   // {x, y, width, height}
			String placement = minimapPlacementWarning(loc.getX(), w,
				canvas != null && canvas.length == 4 ? canvas[2] : 0);
			System.out.println("[shim] minimap widget resolved at (" + loc.getX() + "," + loc.getY()
				+ ") " + w + "x" + h + " -- minimap overlays are live; "
				+ minimapScaleNote(w, h, client.getMinimapZoom())
				+ (placement == null ? "" : "; " + placement));
		}
		int miniMapX = loc.getX() + w / 2 + rx;
		int miniMapY = loc.getY() + h / 2 + ry;
		return new Point(miniMapX, miniMapY);
	}

	/**
	 * Why the minimap widget's rectangle cannot be used, or null when it can. Pure -- no client, no
	 * natives -- so the two refusals are unit-tested rather than argued about; see the call site for
	 * what each one means and what would fix it.
	 */
	@Nullable
	static String minimapRefusal(int x, int y, int width, int height)
	{
		if (width <= 0 || height <= 0)
		{
			return "SIZE is " + width + "x" + height + " -- the widget did not resolve at all";
		}
		if (x <= 0 && y <= 0)
		{
			return "SIZE is real (" + width + "x" + height + ") but POSITION reads (" + x + "," + y
				+ ") -- the minimap is never at the canvas origin, so the parent offsets behind this"
				+ " rectangle did not come out right even though the chain claimed to be complete";
		}
		return null;
	}

	// -- camera yaw, recovered from the projection -------------------------------------------------
	//
	// There is no camera-yaw offset on this build (client/offsets.hpp derives the camera POSITION at
	// +0x895D8 but no orientation), and the minimap is useless without one: every dot is placed by
	// rotating the player-relative offset by the yaw, so a yaw stuck at 0 draws a north-up minimap
	// over a client whose own minimap has turned with the camera, and every dot lands wrong the
	// moment the player turns.
	//
	// The yaw is nonetheless RECOVERABLE from data we already trust, with no new offset: the game's
	// own worldToScreen (Game.projectFine) is the projection, and the shape of that projection is
	// fixed. Writing `a` for the yaw, the horizontal screen coordinate of a point offset
	// (dEast, dNorth) from the camera focus is
	//
	//     screenX = centre + (dEast * cos a + dNorth * sin a) * scale / depth
	//
	// -- the PITCH terms appear only in screenY and in `depth`, never in the numerator of screenX.
	// So projecting two points a known EAST vector apart and two a known NORTH vector apart and
	// taking the screen-x DIFFERENCE of each pair kills the centre, and leaves
	//
	//     northDx ~ 2D * sin a * scale / depth      eastDx ~ 2D * cos a * scale / depth
	//
	// with the same positive factor on both (the pairs are symmetric about the same point, so their
	// depths differ only to second order in D/depth -- a fraction of a percent at D = one tile).
	// atan2(northDx, eastDx) is therefore the yaw, pitch-independent and zoom-independent, and it is
	// the SAME `a` the minimap rotation above uses, because both formulas are two views of one camera
	// rotation. Nothing here assumes a sign convention we cannot check: `a` is defined as whatever
	// value makes the projection true, and that is exactly what localToMinimap needs.

	/** Screen-pixel spread below which a projected basis is rounding noise, not a direction. */
	static final int MIN_BASIS_PIXELS = 2;

	/**
	 * The camera yaw in 0..2047 from the screen-x spread of the world's north and east basis vectors
	 * (see the block above), or -1 when the basis is degenerate and the caller must keep its previous
	 * value. Pure -- no client, no natives.
	 *
	 * @param northScreenDx screenX(focus + D north) - screenX(focus - D north)
	 * @param eastScreenDx  screenX(focus + D east)  - screenX(focus - D east)
	 */
	public static int yawFromScreenBasis(int northScreenDx, int eastScreenDx)
	{
		// Both components near zero is the one degenerate case: the projection collapsed (the focus
		// is on the near plane, or the leaf returned the same pixel for every input). atan2(0,0) is
		// 0.0 in Java -- a plausible-looking NORTH -- so this must be refused, not returned.
		if (Math.abs(northScreenDx) + Math.abs(eastScreenDx) < MIN_BASIS_PIXELS)
		{
			return -1;
		}
		return yawUnits(Math.atan2(northScreenDx, eastScreenDx));
	}

	/**
	 * Radians to the client's 0..2047 yaw, wrapped into range (atan2 returns -pi..pi, so half the
	 * circle arrives negative, and -1 unit must become 2047 rather than index a table at -1).
	 */
	public static int yawUnits(double radians)
	{
		int units = (int) Math.round(radians / UNIT);
		return ((units % YAW_UNITS) + YAW_UNITS) % YAW_UNITS;
	}

	// -- camera pitch, recovered from the same probes ----------------------------------------------
	//
	// There is no camera-orientation offset on this build, so the pitch was never read either -- but
	// unlike the minimap zoom it does NOT need one, and it falls out of projections the yaw
	// derivation is already paying for. Same camera model as the block above, carried one step
	// further into screen Y.
	//
	// Write `a` for the yaw and `p` for the pitch (down from horizontal), r^ for the camera's right
	// vector, h^ for the horizontal direction it faces, z^ for world up. Then
	//
	//     r^ = ( cos a, sin a, 0)        h^ = (-sin a, cos a, 0)
	//     f^ = h^ cos p - z^ sin p       u^ = h^ sin p + z^ cos p
	//
	// (u^ is the camera's screen-up vector: perpendicular to f^ and r^, and equal to z^ when p = 0,
	// which is what "a level camera has world up pointing up the screen" means.) For a point offset
	// `delta` from the focus, at focus depth R and projection scale S,
	//
	//     screenX = cx + (delta . r^) * S / depth        screenY = cy - (delta . u^) * S / depth
	//
	// -- the minus because canvas Y grows DOWNWARD while u^ points up. Take the symmetric pair
	// +-D along a world axis, as deriveCameraYaw already does, and the centre and the depth cancel to
	// first order, leaving (K = 2 D S / R, positive):
	//
	//     north pair:  dx = K sin a          dy = -K cos a sin p
	//     east  pair:  dx = K cos a          dy = +K sin a sin p
	//     up    pair:  dx = 0                dy = -K cos p
	//
	// Two facts come straight off that table.
	//
	//   1. eastDy*northDx - northDy*eastDx = K^2 sin p (sin^2 a + cos^2 a) = K^2 sin p, and
	//      northDx^2 + eastDx^2 = K^2. So the horizontal four probes alone already carry sin p, with
	//      the yaw dividing out -- no vertical probe, no new offset. That is pitchFromScreenBasis's
	//      four-argument form.
	//
	//   2. A fifth measurement, the screen-Y spread of a pair offset +-D along the HEIGHT axis, is
	//      K cos p. With both terms in hand the answer is an atan2 instead of an asin, which is
	//      better in two ways that matter: it stays well conditioned as the camera approaches
	//      straight down (where d(asin)/d(sin) blows up), and the common factor K cancels, so the
	//      answer is immune to the one assumption the four-probe form has to make -- that the
	//      client's projection uses the SAME scale S for screen X and screen Y. It does (the RS
	//      projection divides one `scale` into both numerators, which is also why the game's viewport
	//      never looks anamorphic), but "does not depend on it" beats "checked it once".
	//
	// The two forms are computed together at the call site precisely so they can be compared: their
	// disagreement IS the vertical-to-horizontal scale ratio, and {@link #pitchFormsNote} turns that
	// into a sentence. Nothing here assumes a sign convention that cannot be checked -- the height
	// axis's "negative = up" is the kewl.api.Game contract every projection in this file already
	// relies on, and a pitch that came out negative would mean the camera is looking ABOVE the
	// horizon, which OSRS never does.

	/** Straight down, in the same 0..2047 units as the yaw. OSRS clamps well inside this. */
	public static final int PITCH_STRAIGHT_DOWN = YAW_UNITS / 4;

	/**
	 * The camera pitch in 0..{@link #PITCH_STRAIGHT_DOWN} from the north and east probe pairs alone
	 * (see the block above), or -1 when the basis is degenerate and the caller must keep its previous
	 * value. Pure -- no client, no natives.
	 *
	 * <p>Prefer {@link #pitchFromScreenBasis(int, int, int, int, int)}: this form is an asin, so its
	 * error is multiplied by 1/cos(pitch) and grows without bound as the camera nears vertical, and
	 * it reads the vertical projection scale as pitch if the client ever used two different scales.
	 * MEASURED over a full sweep of every heading against a simulated projection at the two-tile
	 * probe distance (PerspectivePitchTest): worst case 64 units where the five-probe form's is 4.
	 * It exists as the fallback for the frame where the vertical probe pair will not project, where
	 * a shallow-camera answer that is right to a few units beats no answer at all.</p>
	 *
	 * @param northScreenDx screenX(focus + D north) - screenX(focus - D north)
	 * @param northScreenDy screenY of the same pair, same order
	 * @param eastScreenDx  screenX(focus + D east)  - screenX(focus - D east)
	 * @param eastScreenDy  screenY of the same pair, same order
	 */
	public static int pitchFromScreenBasis(int northScreenDx, int northScreenDy,
		int eastScreenDx, int eastScreenDy)
	{
		double k = basisScale(northScreenDx, eastScreenDx);
		if (k < 0)
		{
			return -1;
		}
		// sinTerm is K sin p; dividing by K leaves sin p, and a hair over 1 from pixel rounding at a
		// near-vertical camera must clamp rather than hand asin a NaN.
		double sin = sinTerm(northScreenDx, northScreenDy, eastScreenDx, eastScreenDy) / (k * k);
		return pitchUnits(Math.asin(Math.max(-1d, Math.min(1d, sin))));
	}

	/**
	 * The camera pitch in 0..{@link #PITCH_STRAIGHT_DOWN} from the north, east and UP probe pairs
	 * (see the block above), or -1 when the basis is degenerate. Pure -- no client, no natives. This
	 * is the form the shim uses: an atan2, so it neither loses precision near a vertical camera nor
	 * depends on the projection using one scale for both screen axes. MEASURED worst case over every
	 * heading and every pitch the game can show, with whole-pixel projection output at the two-tile
	 * probe distance: 4 units, 0.7 degrees -- the same budget the yaw recovery holds to.
	 *
	 * @param upScreenDy screenY(focus RAISED by D) - screenY(focus LOWERED by D). Negative in
	 *                   practice: raising a point moves it UP the canvas, and canvas Y grows down.
	 *                   Remember the client's height axis is negative = up, so "raised by D" is
	 *                   {@code height - D} (see kewl.api.Game.projectFine).
	 */
	public static int pitchFromScreenBasis(int northScreenDx, int northScreenDy,
		int eastScreenDx, int eastScreenDy, int upScreenDy)
	{
		double k = basisScale(northScreenDx, eastScreenDx);
		if (k < 0)
		{
			return -1;
		}
		double sin = sinTerm(northScreenDx, northScreenDy, eastScreenDx, eastScreenDy) / k; // K sin p
		double cos = -upScreenDy;                                                           // K cos p
		if (Math.abs(sin) + Math.abs(cos) < MIN_BASIS_PIXELS)
		{
			// Both terms noise: the vertical pair collapsed too, so there is nothing to divide. Never
			// answer 0 here -- a level camera is a perfectly plausible reading and would be a lie.
			return -1;
		}
		return pitchUnits(Math.atan2(sin, cos));
	}

	/** {@code K = 2 D S / R}, the common magnitude of the probe pairs, or -1 if the basis collapsed. */
	private static double basisScale(int northScreenDx, int eastScreenDx)
	{
		if (Math.abs(northScreenDx) + Math.abs(eastScreenDx) < MIN_BASIS_PIXELS)
		{
			return -1;
		}
		return Math.hypot(northScreenDx, eastScreenDx);
	}

	/** {@code K^2 sin p}: the cross term that kills the yaw and leaves the pitch. */
	private static double sinTerm(int northScreenDx, int northScreenDy, int eastScreenDx, int eastScreenDy)
	{
		return (double) eastScreenDy * northScreenDx - (double) northScreenDy * eastScreenDx;
	}

	/**
	 * Radians to the client's 0..2047 pitch. Unlike {@link #yawUnits} this does NOT wrap: a pitch is
	 * not a bearing, and a camera one unit above the horizon is a flat camera plus rounding noise,
	 * not a camera pointing 2047/2048 of a turn downwards. OSRS never lets the camera rise above the
	 * horizon at all (its own clamp is roughly 128..383 of these units), so a negative recovery can
	 * only be noise and is reported as level.
	 */
	public static int pitchUnits(double radians)
	{
		int units = (int) Math.round(radians / UNIT);
		return Math.max(0, Math.min(PITCH_STRAIGHT_DOWN, units));
	}

	/**
	 * What the disagreement between the two pitch forms says, as one log-ready phrase, or null when
	 * they agree closely enough to be worth nothing. Pure, so the wording is tested rather than
	 * argued about; {@link ClientState} prints it at most once per session.
	 *
	 * <p>The four-probe form reads the vertical projection scale as extra pitch; the five-probe form
	 * cancels it. So the gap between them is a measurement of something worth knowing -- either the
	 * client scales screen Y differently from screen X (it should not), or one of the probe pairs is
	 * being quantised harder than the derivation's error budget allows. Either way the five-probe
	 * answer is the one used, which is why this is a note and not a refusal.</p>
	 *
	 * @param atan2Units the five-probe pitch, the one in force
	 * @param asinUnits  the four-probe pitch, the cross-check
	 */
	@Nullable
	static String pitchFormsNote(int atan2Units, int asinUnits)
	{
		if (atan2Units < 0 || asinUnits < 0)
		{
			return null; // one of them refused; nothing to compare
		}
		int diff = Math.abs(atan2Units - asinUnits);
		if (diff <= PITCH_FORM_AGREEMENT_UNITS)
		{
			return "the two independent forms agree to " + diff + " units, so the projection uses one"
				+ " scale for both screen axes as assumed";
		}
		return "WARNING: the two forms disagree by " + diff + " units (atan2 " + atan2Units + ", asin "
			+ asinUnits + ") -- either the client scales screen Y differently from screen X, or a probe"
			+ " pair is too short to survive pixel rounding. The atan2 form is in force and is immune"
			+ " to the first of those";
	}

	/**
	 * How far the two pitch forms may differ before it means something. Four units is 0.7 degrees,
	 * the same budget the yaw sweep in PerspectiveYawTest holds the yaw to, and pixel rounding alone
	 * accounts for it.
	 */
	static final int PITCH_FORM_AGREEMENT_UNITS = 4;

	// -- minimap scale ------------------------------------------------------------------------------

	/** The vanilla minimap: 152x152 px at 4 px/tile, i.e. 38 tiles across. */
	static final int STANDARD_MINIMAP_PX = 152;

	/**
	 * Why the resolved minimap rectangle cannot be a CANVAS position, or null when it can be. Pure.
	 *
	 * <p>This is the SECOND half of the same parent-offset problem {@link #minimapRefusal} names, and
	 * the half that gets through it. The refusal only catches a rectangle at (0,0); a parent-relative
	 * position that is not the origin -- the live 2026-09-06 read was (53,8) for a 152x152 minimap --
	 * passes as resolved and then every dot is drawn at the top LEFT of the canvas while the client's
	 * own minimap sits at the top right. The check is the one thing that is certain about a minimap
	 * in every layout mode, fixed and resizable alike: it is anchored to the RIGHT edge, so a
	 * rectangle that ends inside the left half of the canvas is not where the minimap is, and the
	 * only thing it can plausibly be is a position relative to a parent nobody accumulated.</p>
	 *
	 * <p>A warning rather than a refusal, and now firmly so: the widget carries a real
	 * {@code isCanvasAbsolute()} flag derived from whether its parent chain reached a root, and THAT
	 * is the refusal. This stayed as a warning-shaped refusal for a while in between, which was a
	 * guess with a veto -- a legitimately absolute rectangle that happens to end in the left half
	 * (a narrow canvas, an unusual layout) would have been thrown out by it. It is kept because it is
	 * still worth SAYING when it fires: a completeness flag that says "absolute" while the rectangle
	 * lands where no minimap belongs would mean the derived parent link is wrong, and that is exactly
	 * the sentence somebody would need to see.</p>
	 */
	@Nullable
	static String minimapPlacementWarning(int x, int width, int canvasWidth)
	{
		if (canvasWidth <= 0 || width <= 0)
		{
			return null; // nothing to judge against
		}
		if (x + width > canvasWidth / 2)
		{
			return null;
		}
		return "WARNING: a " + width + "px minimap at x=" + x + " ends inside the left half of a "
			+ canvasWidth + "px canvas, but the minimap is anchored top-RIGHT in every layout -- this x"
			+ " is almost certainly still PARENT-RELATIVE (getWidget accumulates no parent offsets for"
			+ " a single packed id), so minimap overlays draw the right shape in the wrong place";
	}

	/**
	 * What the minimap widget's rectangle says about {@code pixelsPerTile}, as one log-ready phrase.
	 * Pure, so the judgement is tested rather than argued.
	 *
	 * <p>The scale itself is NOT derivable the way the yaw is: the projection describes the 3D
	 * viewport, and the minimap is a separate raster the client draws itself, so no pair of projected
	 * points measures it. The one honest cross-check available is the widget SIZE -- vanilla draws a
	 * 152x152 minimap at 4 px/tile (38 tiles across, which is also why upstream's localToMinimap
	 * culls at a 20-tile radius). A widget of that size corroborates the 4.0; any other size means
	 * the client is drawing a minimap this shim has never measured, and the caller is told so instead
	 * of being left to assume.</p>
	 */
	static String minimapScaleNote(int width, int height, double pixelsPerTile)
	{
		if (pixelsPerTile <= 0)
		{
			return "minimap scale " + pixelsPerTile + " px/tile is not a scale -- overlays will not place";
		}
		String span = String.format(Locale.ROOT, "%.1fx%.1f tiles across at %.1f px/tile",
			width / pixelsPerTile, height / pixelsPerTile, pixelsPerTile);
		if (width == STANDARD_MINIMAP_PX && height == STANDARD_MINIMAP_PX)
		{
			return span + " -- the vanilla 152x152 minimap, so the scale checks out";
		}
		return span + " -- NOT the vanilla " + STANDARD_MINIMAP_PX + "x" + STANDARD_MINIMAP_PX
			+ " minimap, so 4 px/tile is an assumption here, not a measurement";
	}
}

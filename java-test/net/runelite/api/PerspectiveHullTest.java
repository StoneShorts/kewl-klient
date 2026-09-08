// The pure 2D monotone-chain hull behind Actor.getConvexHull: interior points dropped, collinear
// edge points dropped, the result a simple polygon that contains every input. No natives.
package net.runelite.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Point;
import java.awt.Polygon;
import java.util.List;

import org.junit.Test;

public class PerspectiveHullTest
{
	@Test
	public void squareWithInteriorPointHullsToTheFourCorners()
	{
		Polygon hull = Perspective.convexHull(List.of(
			new Point(0, 0), new Point(10, 0), new Point(10, 10), new Point(0, 10), new Point(5, 5)));
		assertEquals(4, hull.npoints);
		for (Point p : List.of(new Point(0, 0), new Point(10, 0), new Point(10, 10), new Point(0, 10)))
		{
			assertTrue(p + " is a hull vertex", isVertex(hull, p));
		}
		assertTrue(hull.contains(5, 5));
	}

	@Test
	public void collinearEdgePointsAreDropped()
	{
		Polygon hull = Perspective.convexHull(List.of(
			new Point(0, 0), new Point(5, 0), new Point(10, 0), new Point(10, 10), new Point(0, 10)));
		assertEquals(4, hull.npoints);
	}

	@Test
	public void prismCornersProjectedLikeAnIsometricBoxHullToAHexagon()
	{
		// Eight corners of a box under a skewed "camera": the classic case for an actor hull -- two
		// of the eight are interior, the other six form a hexagon.
		Polygon hull = Perspective.convexHull(List.of(
			new Point(0, 0), new Point(10, 3), new Point(3, 8), new Point(13, 11),     // bottom face
			new Point(0, -20), new Point(10, -17), new Point(3, -12), new Point(13, -9))); // top face
		assertEquals(6, hull.npoints);
		// The exact hexagon: the near-bottom parallelogram's far corners plus the far-top one's near
		// corners. Naming all six pins WHICH points survived -- review 2026-09-06: asserting only
		// "npoints == 6" and "the two others are inside or on the boundary" passed a hull that had
		// swapped (10,3) in for (3,8), because a vertex counts as being on its own boundary.
		for (Point p : List.of(new Point(0, 0), new Point(3, 8), new Point(13, 11),
			new Point(13, -9), new Point(10, -17), new Point(0, -20)))
		{
			assertTrue(p + " is a hull vertex", isVertex(hull, p));
		}
		for (Point p : List.of(new Point(10, 3), new Point(3, -12)))
		{
			assertFalse(p + " is interior, so it must not be a vertex", isVertex(hull, p));
			assertTrue(p + " should be inside the hull", hull.contains(p));
		}
	}

	@Test
	public void fewerThanThreePointsDegradeQuietly()
	{
		assertEquals(0, Perspective.convexHull(List.of()).npoints);
		assertEquals(1, Perspective.convexHull(List.of(new Point(1, 1))).npoints);
		assertEquals(2, Perspective.convexHull(List.of(new Point(1, 1), new Point(4, 4))).npoints);
	}

	@Test
	public void duplicatePointsDoNotBreakTheChain()
	{
		Polygon hull = Perspective.convexHull(List.of(
			new Point(0, 0), new Point(0, 0), new Point(10, 0), new Point(10, 0), new Point(5, 9)));
		assertEquals(3, hull.npoints);
	}

	private static boolean isVertex(Polygon poly, Point p)
	{
		for (int i = 0; i < poly.npoints; i++)
		{
			if (poly.xpoints[i] == p.x && poly.ypoints[i] == p.y)
			{
				return true;
			}
		}
		return false;
	}
}

// Widget's own half of absolute geometry: the canvas position a caller gets, and the relative pair
// that must survive alongside it. No client, no natives.
package net.runelite.api.widgets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.runelite.api.Point;

import org.junit.Test;

public class WidgetCanvasLocationTest
{
	@Test
	public void aWidgetIsNotCanvasAbsoluteUntilSomebodySaysSo()
	{
		// Fail closed. A Widget built by anything that has not run the absolute path must not claim a
		// canvas position, because the position it holds is then the stored parent-relative one.
		assertFalse(new Widget(0x00a1_001e).isCanvasAbsolute());
	}

	@Test
	public void theCanvasLocationIsTheBoundsOrigin()
	{
		Widget w = new Widget(0x00a1_001e);
		w.setBounds(1143, 12, 152, 152);
		w.setCanvasAbsolute(true);
		Point p = w.getCanvasLocation();
		assertEquals(1143, p.getX());
		assertEquals(12, p.getY());
		assertTrue(w.isCanvasAbsolute());
	}

	@Test
	public void theRelativePairSurvivesAbsoluteBounds()
	{
		// Both shapes have callers and they must stay distinguishable: getScrollHeight measures a
		// child's extent from its RELATIVE y, which a canvas y would inflate by every ancestor's
		// offset -- silently, and only for widgets deep in a tree.
		Widget w = new Widget(0x00a1_001e);
		w.setBounds(1143, 12, 152, 152);     // absolute, summed up the chain
		w.setRelativeX(53);                  // what the component actually stores
		w.setRelativeY(8);
		w.setCanvasAbsolute(true);
		assertEquals(53, w.getRelativeX());
		assertEquals(8, w.getRelativeY());
		assertEquals(1143, w.getBounds().x);
		assertEquals(12, w.getBounds().y);
	}

	@Test
	public void anUnresolvedChainStillReportsTheRelativePositionSoNothingGetsWorse()
	{
		// The pre-existing behaviour, kept deliberately: when the chain does not resolve, the widget
		// carries exactly the bounds it always carried. The flag -- not a changed number -- is what
		// tells a caller to refuse.
		Widget w = new Widget(0x00a1_001e);
		w.setBounds(53, 8, 152, 152);
		w.setRelativeX(53);
		w.setRelativeY(8);
		assertFalse(w.isCanvasAbsolute());
		assertEquals(53, w.getCanvasLocation().getX());
		assertEquals(8, w.getCanvasLocation().getY());
	}

	@Test
	public void chainDepthIsCarriedForDiagnosis()
	{
		Widget w = new Widget(0x00a1_001e);
		w.setChainDepth(2);
		assertEquals(2, w.getChainDepth());
	}
}

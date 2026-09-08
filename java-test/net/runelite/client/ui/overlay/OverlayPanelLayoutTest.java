// What a panel's rows are told about their own width, with no game and no display behind them.
//
// This exists because of one user report -- "the overlay for shortestpath is buggy. its not aligned
// properly and shows the numbers of debug on the left where it shud show on the right" -- and its
// root cause: PanelComponent handed its children a width only through setPreferredSize, which is a
// no-op default on LayoutableRenderableEntity that LineComponent and TitleComponent do not override
// (their fields are final, so Lombok generates no such setter). Their only layout rectangle, bounds,
// therefore kept width 0 forever, and LineComponent draws its right-hand value at
// bounds.x + bounds.width - textWidth -- i.e. that many pixels LEFT of the panel. The numbers were
// literally drawn off the left edge, and the title was centred at -textWidth/2.
//
// A BufferedImage's Graphics2D gives real FontMetrics, so the whole layout is testable headless.
package net.runelite.client.ui.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.Test;

import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class OverlayPanelLayoutTest
{
	private static final int CHILD_WIDTH =
		ComponentConstants.STANDARD_WIDTH - 2 * ComponentConstants.STANDARD_BORDER;

	private static BufferedImage canvas()
	{
		return new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
	}

	/** THE regression guard for "the numbers show on the left". */
	@Test
	public void rowsAreToldHowWideTheyAre()
	{
		BufferedImage image = canvas();
		Graphics2D g = image.createGraphics();

		PanelComponent panel = new PanelComponent();
		LineComponent row = LineComponent.builder().left("Path Length:").right("143").build();
		TitleComponent title = TitleComponent.builder().text("Shortest Path Debug").color(Color.ORANGE).build();
		panel.getChildren().add(title);
		panel.getChildren().add(row);
		panel.render(g);
		g.dispose();

		assertEquals("row width", CHILD_WIDTH, row.getBounds().width);
		assertEquals("title width", CHILD_WIDTH, title.getBounds().width);
	}

	/**
	 * The consequence, stated the way the user saw it: the right-hand value's leftmost pixel is inside
	 * the panel, and its rightmost pixel is on the panel's right edge, not off to the left of it.
	 */
	@Test
	public void theRightHandValueIsDrawnOnTheRight()
	{
		BufferedImage image = canvas();
		Graphics2D g = image.createGraphics();

		PanelComponent panel = new PanelComponent();
		LineComponent row = LineComponent.builder().left("Path Length:").right("143").build();
		panel.getChildren().add(row);
		panel.render(g);

		int valueWidth = g.getFontMetrics(g.getFont()).stringWidth("143");
		g.dispose();

		// LineComponent draws its right text at bounds.x + bounds.width - valueWidth, inside a
		// Graphics2D the panel translated by STANDARD_BORDER.
		int valueX = ComponentConstants.STANDARD_BORDER + row.getBounds().width - valueWidth;
		assertTrue("value must start inside the panel, not left of it", valueX > 0);
		assertEquals("value must end on the panel's inner right edge",
			ComponentConstants.STANDARD_WIDTH - ComponentConstants.STANDARD_BORDER,
			valueX + valueWidth);
	}

	/** Rows are inset from the border on every side, so text does not sit flush on the box. */
	@Test
	public void panelKeepsItsStandardWidthAndInsetsItsRows()
	{
		BufferedImage image = canvas();
		Graphics2D g = image.createGraphics();

		PanelComponent panel = new PanelComponent();
		LineComponent row = LineComponent.builder().left("Nodes:").right("2100").build();
		panel.getChildren().add(row);
		Dimension dim = panel.render(g);
		g.dispose();

		assertEquals("panel keeps its standard width", ComponentConstants.STANDARD_WIDTH, dim.width);
		assertEquals("panel is its rows plus one border top and bottom",
			row.getBounds().height + 2 * ComponentConstants.STANDARD_BORDER, dim.height);
	}

	/**
	 * The background box is painted on the FIRST frame at its real height. It used to be drawn from
	 * the height measured on the previous frame, so a panel that had just appeared drew its rows with
	 * no box behind them.
	 */
	@Test
	public void backgroundIsPaintedOnTheFirstFrame()
	{
		BufferedImage image = canvas();
		Graphics2D g = image.createGraphics();

		PanelComponent panel = new PanelComponent();
		panel.getChildren().add(LineComponent.builder().left("Time:").right("12.00ms").build());
		Dimension dim = panel.render(g);
		g.dispose();

		int alphaTopLeft = (image.getRGB(2, 2) >>> 24);
		int alphaInsideBottom = (image.getRGB(2, dim.height - 2) >>> 24);
		assertTrue("box must be painted under the first frame's rows", alphaTopLeft > 0);
		assertTrue("box must cover the full measured height", alphaInsideBottom > 0);

		int alphaBelow = (image.getRGB(2, dim.height + 4) >>> 24);
		assertEquals("nothing painted below the box", 0, alphaBelow);
	}

	/** An empty panel draws nothing and reports no height, so it consumes no slot in a corner stack. */
	@Test
	public void emptyPanelReportsNoHeight()
	{
		BufferedImage image = canvas();
		Graphics2D g = image.createGraphics();

		Dimension dim = new PanelComponent().render(g);
		g.dispose();

		assertEquals(0, dim.height);
		assertEquals("nothing painted at all", 0, image.getRGB(2, 2) >>> 24);
	}
}

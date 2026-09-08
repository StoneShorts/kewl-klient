// Shim of net.runelite.client.ui.overlay.components.ComponentConstants (BSD-2, RuneLite).
package net.runelite.client.ui.overlay.components;

import java.awt.Dimension;

public final class ComponentConstants
{
	public static final int STANDARD_WIDTH = 129;

	/**
	 * Inset a panel puts between its border and its children, on every side. Upstream's value; it is
	 * why a 129-wide panel lays its rows out 123 wide, and why text does not sit flush on the border.
	 */
	public static final int STANDARD_BORDER = 3;

	private ComponentConstants()
	{
	}
}

// Shim of net.runelite.client.ui.overlay.OverlayPriority (BSD-2, RuneLite).
package net.runelite.client.ui.overlay;

public enum OverlayPriority
{
	LOW(0f),
	MED(0.25f),
	HIGH(0.5f),
	HIGHEST(1f);

	private final float priority;

	OverlayPriority(float priority)
	{
		this.priority = priority;
	}

	public float getPriority()
	{
		return priority;
	}
}

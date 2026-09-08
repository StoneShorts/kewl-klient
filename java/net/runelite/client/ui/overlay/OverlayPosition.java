// Shim of net.runelite.client.ui.overlay.OverlayPosition (BSD-2, RuneLite).
//
// Only OverlayPanel uses position here (to pick a corner for panel-style overlays); the layered
// window draws over the whole game, so the rest of the enum exists to keep sources compiling.
package net.runelite.client.ui.overlay;

public enum OverlayPosition
{
	TOP_LEFT,
	TOP_CENTER,
	TOP_RIGHT,
	BOTTOM_LEFT,
	BOTTOM_CENTER,
	BOTTOM_RIGHT,
	ABOVE_CHATBOX_RIGHT,
	DYNAMIC,
	MOUSE,
	TOOLTIP,
	STATE_OVERLAY,
}

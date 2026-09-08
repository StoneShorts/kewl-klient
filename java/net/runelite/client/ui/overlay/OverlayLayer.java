// Shim of net.runelite.client.ui.overlay.OverlayLayer (BSD-2, RuneLite).
//
// The layered window sits above everything the game draws, so the layering between game scene and
// widgets cannot be reproduced; the value only decides draw order among this plugin's overlays.
package net.runelite.client.ui.overlay;

public enum OverlayLayer
{
	ALWAYS_ON_TOP,
	ABOVE_WIDGETS,
	UNDER_WIDGETS,
	ABOVE_SCENE,
	MANUAL,
}

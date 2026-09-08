// Shim of net.runelite.client.input.KeyListener (BSD-2, RuneLite). Same shape as the real one: the
// plugin implements it with java.awt.event.KeyEvent.
package net.runelite.client.input;

import java.awt.event.KeyEvent;

public interface KeyListener
{
	void keyTyped(KeyEvent e);

	void keyPressed(KeyEvent e);

	void keyReleased(KeyEvent e);
}

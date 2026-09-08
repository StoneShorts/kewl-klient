// Shim of net.runelite.client.input.KeyManager (BSD-2, RuneLite).
//
// KewlKlient polls F1-F8 from the game window; the bridge turns those into KeyEvents and calls the
// registered listeners. registerKeyListener/registerKeyListener only keep lists.
package net.runelite.client.input;

import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class KeyManager
{
	private final List<KeyListener> keyListeners = new CopyOnWriteArrayList<>();

	public void registerKeyListener(KeyListener listener)
	{
		keyListeners.add(listener);
	}

	public void unregisterKeyListener(KeyListener listener)
	{
		keyListeners.remove(listener);
	}

	/** Called by the bridge when a polled key edge happens. */
	public void dispatchPressed(KeyEvent e)
	{
		for (KeyListener l : keyListeners)
		{
			l.keyPressed(e);
		}
	}

	public void dispatchReleased(KeyEvent e)
	{
		for (KeyListener l : keyListeners)
		{
			l.keyReleased(e);
		}
	}
}

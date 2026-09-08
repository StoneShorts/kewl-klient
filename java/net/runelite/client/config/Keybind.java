// Shim of net.runelite.client.config.Keybind (BSD-2, RuneLite), cut to the used surface: NOT_SET,
// matches(KeyEvent) for the "clear path" hotkey listener, and the getters the config panel would read.
//
// KewlKlient only has F1-F8 hotkeys, so the panel edits this as an F-index and the bridge synthesises
// KeyEvents from the game's key polling. Any other key code matches nothing.
package net.runelite.client.config;

import java.awt.event.KeyEvent;

public class Keybind
{
	public static final int NOT_SET_CODE = -1;
	public static final Keybind NOT_SET = new Keybind(NOT_SET_CODE, 0);

	private final int keyCode;
	private final int modifiers;

	public Keybind(int keyCode)
	{
		this(keyCode, 0);
	}

	public Keybind(int keyCode, int modifiers)
	{
		this.keyCode = keyCode;
		this.modifiers = modifiers;
	}

	public int getKeyCode()
	{
		return keyCode;
	}

	public int getModifiers()
	{
		return modifiers;
	}

	/** True when the event is the key this hotkey is bound to. */
	public boolean matches(KeyEvent e)
	{
		return keyCode != NOT_SET_CODE && e.getKeyCode() == keyCode && e.getID() == KeyEvent.KEY_PRESSED;
	}

	@Override
	public String toString()
	{
		return keyCode == NOT_SET_CODE ? "Not set" : "F" + (keyCode - KeyEvent.VK_F1 + 1);
	}

	@Override
	public boolean equals(Object o)
	{
		return o instanceof Keybind k && k.keyCode == keyCode && k.modifiers == modifiers;
	}

	@Override
	public int hashCode()
	{
		return keyCode * 31 + modifiers;
	}
}

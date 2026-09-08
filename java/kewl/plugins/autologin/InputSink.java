package kewl.plugins.autologin;

import kewl.Natives;

/**
 * Where {@link LoginSequence} sends its keystrokes. The seam that makes the state machine testable
 * offline: tests hand it a recording implementation, the plugin hands it {@link #NATIVE}.
 *
 * <p>Same contract as {@code kewl.api.Input}: characters as WM_CHAR, control keys as
 * KEYDOWN/KEYUP, mouse in canvas coordinates. Nothing implementing this may log the character.</p>
 */
public interface InputSink {

    boolean postChar(int c);

    boolean postKey(int vk, boolean down);

    /** action 0 move, 1 left down, 2 left up. */
    boolean postMouse(int x, int y, int action);

    /** The real thing: straight to the DLL. Throws {@code UnsatisfiedLinkError} against an old DLL. */
    InputSink NATIVE = new InputSink() {
        @Override public boolean postChar(int c) { return Natives.postChar(c); }
        @Override public boolean postKey(int vk, boolean down) { return Natives.postKey(vk, down); }
        @Override public boolean postMouse(int x, int y, int action) { return Natives.postMouse(x, y, action); }
    };
}

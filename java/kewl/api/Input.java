package kewl.api;

import kewl.Natives;

/**
 * Injecting input, by posting window messages to NXT's render view.
 *
 * <p>Text goes as WM_CHAR, control keys as WM_KEYDOWN/WM_KEYUP -- see the "Input INTO the game"
 * section of {@code client/jvm.hpp} for why (the game's own TranslateMessage would otherwise decide
 * the case from the physical shift state and double every character). Nothing here logs what it
 * types; the first caller is the autologin plugin, and this is its password path.</p>
 *
 * <p>There is deliberately no {@code typeText(String)}. The frame thread may not block, so a caller
 * must pace characters across frames itself; a whole-string call would invite a burst the login
 * screen may drop. {@code kewl.plugins.autologin.LoginSequence} is the worked example.</p>
 *
 * <p>Everything here is fire-and-forget: a {@code true} means the message was queued for the game
 * window, not that the game acted on it. Posted CLICKS <b>are</b> honoured on client-240-6 -- the
 * autologin plugin's "Login" and "CLICK HERE TO PLAY" clicks both go through {@link #mouseDown} /
 * {@link #mouseUp} and both work (seen live 2026-09-06). That is what makes click-to-walk possible
 * while the client's own action function is still underived; see {@link Actions#walk}.
 * (The client imports GetAsyncKeyState, which was the reason to doubt posted clicks. It was wrong.)</p>
 */
public final class Input {

    private Input() {}

    /** Win32 virtual keys the login sequence needs. Letters never go through {@link #key}. */
    public static final int VK_BACK = 0x08;
    public static final int VK_TAB = 0x09;
    public static final int VK_RETURN = 0x0D;

    /** One character of text, as the game would receive it from real typing. */
    public static boolean typeChar(char c) {
        return Natives.postChar(c);
    }

    /** A control key going down ({@code down=true}) or up. Pair every down with an up. */
    public static boolean key(int vk, boolean down) {
        return Natives.postKey(vk, down);
    }

    /**
     * Move the (virtual) mouse, in canvas coordinates -- the space {@link Game#projectFine} answers in.
     *
     * <p><b>A move posted while a mouse button is down is a DRAG</b>, and the button does not have to
     * be one we posted: the user's hand is on the same physical mouse. Seen live 2026-09-07 -- the
     * walker's move landed between the user's button-down and button-up on the world map's close
     * button, so the release happened somewhere else and the map would not close, while the drag
     * itself panned the map. A caller that posts moves on a timer owes the decision "is any button
     * down right now" before every one of them ({@code kewl.rl.AutoWalk.standDownReason} is the worked
     * example, and it stands down for the whole span the button is held, not just the press).</p>
     */
    public static boolean mouseMove(int x, int y) {
        return Natives.postMouse(x, y, 0);
    }

    /** Left button down at a canvas point. Send {@link #mouseMove} first, as a real mouse would. */
    public static boolean mouseDown(int x, int y) {
        return Natives.postMouse(x, y, 1);
    }

    /** Left button up at a canvas point -- a frame or so after the down, not in the same burst. */
    public static boolean mouseUp(int x, int y) {
        return Natives.postMouse(x, y, 2);
    }

    /**
     * A whole left click at a canvas point: move, down, up, in that order, in one call.
     *
     * <p>Three PostMessageW calls, so the game's own pump sees them in order with distinct message
     * times -- a fast human click. This is the shape the autologin plugin's Login and "CLICK HERE TO
     * PLAY" clicks proved on client-240-6 (seen live 2026-09-06), except that {@code LoginSequence}
     * puts the up on the NEXT emission slot rather than in the same call, because the title screen
     * dropped bursts. In-game clicking has no such evidence either way; if a walk click ever lands as
     * a move that never released, splitting down and up across two frames the way LoginSequence does
     * is the first thing to try -- {@link #mouseDown} and {@link #mouseUp} are here for exactly that.
     *
     * <p>Do not call this per frame. It is input into a live game: a caller must pace itself
     * ({@code kewl.rl.AutoWalk} clicks at most once every few frames, and that is a setting).
     *
     * @return true when all three messages were queued; false the moment one was not
     */
    public static boolean click(int x, int y) {
        return mouseMove(x, y) & mouseDown(x, y) & mouseUp(x, y);
    }

    /**
     * A left click that ARRIVES at the point: a move to an approach point first, then the move that
     * lands, then down and up.
     *
     * <p>A real cursor is somewhere else before it is here. Posting a single move to the exact pixel
     * makes the game's cursor teleport, which is the one thing a watcher can see for free; two moves
     * cost one extra PostMessageW and make the same click look like it came from a hand. The approach
     * point is not clamped to the canvas on purpose -- it is a MOVE, not a click, and a move a few
     * pixels outside the window is what a real cursor coming from outside looks like.</p>
     *
     * @return true when every message was queued; false the moment one was not
     */
    public static boolean click(int x, int y, int approachX, int approachY) {
        return mouseMove(approachX, approachY) & mouseMove(x, y) & mouseDown(x, y) & mouseUp(x, y);
    }

    /**
     * Move to a point and hold the left button down there. <b>Pair every one with a {@link #mouseUp}
     * at the same point</b> -- see {@link Actions#press}, which owns that contract.
     */
    public static boolean press(int x, int y, int approachX, int approachY) {
        boolean approached = approachX == x && approachY == y || mouseMove(approachX, approachY);
        return approached & mouseMove(x, y) & mouseDown(x, y);
    }

    /**
     * Where injected input goes, for the status line and the log. {@code w}/{@code h} are the
     * target window's client size (the canvas) -- the numbers to centre click offsets on.
     */
    public record Target(boolean exists, boolean isRenderView, boolean focused, boolean foreground,
                         int w, int h) {

        /** One line for a log: {@code target=renderview focused=1 foreground=1 canvas=765x503}. */
        public String describe() {
            if (!exists) return "target=none";
            return "target=" + (isRenderView ? "JagRenderView" : "game-root")
                    + " focused=" + (focused ? 1 : 0)
                    + " foreground=" + (foreground ? 1 : 0)
                    + " canvas=" + w + "x" + h;
        }
    }

    /**
     * Describe the input target. {@code grab=true} also asks the DLL to SetFocus the game first
     * (probe-guarded so a non-pumping game cannot hang the frame). The posted-message path needs no
     * focus; this exists so the log can say whether the game had it when something did not type.
     */
    public static Target target(boolean grab) {
        int[] v = Natives.inputTarget(grab);
        if (v == null || v.length < 6) return new Target(false, false, false, false, 0, 0);
        return new Target(v[0] != 0, v[1] != 0, v[2] != 0, v[3] != 0, v[4], v[5]);
    }
}

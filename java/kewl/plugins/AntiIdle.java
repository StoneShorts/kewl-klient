package kewl.plugins;

import kewl.Natives;
import kewl.Plugin;
import kewl.api.Input;

/**
 * Keeps the account from being logged out for inactivity.
 *
 * <p>The server drops a client that sends no input for a few minutes (live 2026-09-06: the account
 * sat on the bank floor and came back to "You were disconnected from the server" while nobody was
 * touching it). Nothing on our side can extend that -- the login plugin's timeouts are about the
 * login form, not about staying in -- so this plugin does what a player does: every few minutes it
 * taps a camera key. A LEFT arrow for a couple of frames and then a RIGHT arrow for the same
 * couple of frames, so the camera ends where it started. Keys go through the same posted-message
 * path the login plugin types with ({@link Input}), straight to the game's render view, so it works
 * with the window in the background and never touches another application.</p>
 *
 * <p>Only while logged in (raw game state 30). Off by default: it is a choice, not a default, and
 * the profile remembers the switch. Pair it with AutoLogin's "Log in again after a disconnect" for a
 * session that also survives the disconnects it cannot prevent.</p>
 */
public final class AntiIdle extends Plugin {

    private static final int VK_LEFT = 0x25, VK_RIGHT = 0x27;
    private static final int STATE_LOGGED_IN = 30;
    private static final int FRAMES_PER_SECOND = 30;     // KewlKlient.tick runs ~30 times a second
    private static final int HOLD_FRAMES = 3;            // how long each arrow stays down

    /** The raw game state, or 0 where the natives are not loaded (unit tests, a jar run standalone). */
    private static int gameState() {
        try { return Natives.gameState(); } catch (UnsatisfiedLinkError e) { return 0; }
    }

    private long framesUntilNudge;
    private int phase;                                   // 0 idle, 1 left down, 2 right down
    private int phaseFrames;
    private long nudges;

    public AntiIdle() {
        config.number("intervalSec", "Nudge every (seconds)",
                "The server logs out after about five idle minutes; stay well under it", 240, 30, 290);
        config.bool("cameraNudge", "Nudge the camera (left then right arrow)",
                "Off = only report; on = the tap that keeps the session alive", true);
    }

    @Override public String name()        { return "Anti-idle"; }
    @Override public String description() { return "Taps a camera key every few minutes so the server does not log you out."; }
    @Override public int    hotkey()      { return -1; }

    @Override
    public void onEnable() {
        framesUntilNudge = (long) config.number("intervalSec") * FRAMES_PER_SECOND;
        phase = 0;
        nudges = 0;
    }

    @Override
    public String status() {
        if (gameState() != STATE_LOGGED_IN) return "waiting for the world";
        if (phase != 0) return "nudging";
        return "next nudge in " + (framesUntilNudge / FRAMES_PER_SECOND) + " s, " + nudges + " so far";
    }

    @Override
    public void tick() {
        if (gameState() != STATE_LOGGED_IN) {
            // Not in the world: nothing to keep alive, and a key on the title screen is noise for
            // the login plugin. Restart the countdown so the first nudge comes a full interval in.
            framesUntilNudge = (long) config.number("intervalSec") * FRAMES_PER_SECOND;
            if (phase != 0) { Input.key(VK_LEFT, false); Input.key(VK_RIGHT, false); phase = 0; }
            return;
        }
        if (phase == 0) {
            if (--framesUntilNudge > 0) return;
            framesUntilNudge = (long) config.number("intervalSec") * FRAMES_PER_SECOND;
            if (!config.bool("cameraNudge")) return;
            Input.key(VK_LEFT, true);
            phase = 1;
            phaseFrames = 0;
            return;
        }
        if (++phaseFrames < HOLD_FRAMES) return;
        phaseFrames = 0;
        if (phase == 1) {
            Input.key(VK_LEFT, false);
            Input.key(VK_RIGHT, true);            // undo the turn: same hold, opposite direction
            phase = 2;
        } else {
            Input.key(VK_RIGHT, false);
            phase = 0;
            nudges++;
        }
    }

    @Override
    public void onDisable() {
        if (phase != 0) { Input.key(VK_LEFT, false); Input.key(VK_RIGHT, false); phase = 0; }
    }
}

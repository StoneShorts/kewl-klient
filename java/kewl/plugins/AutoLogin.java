package kewl.plugins;

import java.awt.Color;
import java.awt.Graphics2D;

import kewl.KewlKlient;
import kewl.Natives;
import kewl.Plugin;
import kewl.api.Input;
import kewl.plugins.autologin.Credentials;
import kewl.plugins.autologin.FieldWriter;
import kewl.plugins.autologin.InputSink;
import kewl.plugins.autologin.LoginSequence;
import kewl.plugins.autologin.PlayButton;
import kewl.ui.Hud;
import kewl.ui.Theme;

/**
 * Types your login into the title screen.
 *
 * <p>The credentials come from one of two places, resolved fresh on every attempt by
 * {@link Credentials#resolve}: the {@code username} / {@code password} settings at the top of this
 * plugin's panel when both are filled in, otherwise the two lines -- {@code username=} and
 * {@code password=} -- of {@code ~/.kewlklient/autologin.properties} in the client's data directory
 * ({@link KewlKlient#dataDir()}). The password setting is a {@code config.secret}: masked wherever
 * either panel draws it, edited only in a password-mode field, and -- like every setting --
 * persisted by the profile store into the profile's config.json under {@code ~/.kewlklient}, which
 * is local and never part of the repository. The file is the alternative for anyone who would
 * rather not have it there. Either way this plugin logs only where the credentials came from
 * ("panel" or "file") and whether each is set, never a character of either and never a length.</p>
 *
 * <p><b>Why a plain {@link Plugin} and not an RlitePlugin:</b> {@link #hotkey()} is the "login now"
 * mechanism, and the shim fires no GameTick at the title screen (it derives ticks from your own
 * cycle counter, which is 0 there). Nothing RuneLite-shaped is being ported.</p>
 *
 * <p><b>Hotkey semantics:</b> F7 toggles the plugin. Enabling it at the title screen means "login
 * now" (a 500 ms settle instead of the full delay); enabling it during start-up simply waits for the
 * login screen; pressing it again while enabled aborts. Off by default and not in
 * {@code KewlKlient.defaultOn()}: switch it on once, the profile remembers.</p>
 *
 * <p>Everything it does is a {@link LoginSequence} fed the raw game state each frame; the state
 * machine is offline-tested, this class only wires it to the natives, the file and the panel. The
 * click targets (x from the canvas centre, y from the canvas top -- see the settings) were measured
 * live on 2026-09-06 and are drawn as crosshairs while the title screen is up, so they can be
 * re-calibrated from the overlay before anything is typed. The flow they drive, as seen live:
 * Existing User -> the form (username pre-filled, caret in the password field when "Remember
 * username" is ticked) -> password -> the Login button (Enter does not submit on this build) -> either
 * the state leaves 10, or the "Incorrect username or password" screen with no state change, which
 * is dismissed with Try again and counted against maxRejects. At state 30 the client shows a welcome
 * screen whose "CLICK HERE TO PLAY" button loads the world; the plugin clicks it once ({@code clickPlay}),
 * only after a login it typed itself. That one click is resolved to a WIDGET -- {@link PlayButton}
 * finds the component whose rectangle contains the measured point and clicks its centre, so it
 * follows the window size; the measurement is the fall back, and the status line says which was
 * used.</p>
 */
public final class AutoLogin extends Plugin {

    private LoginSequence sequence;
    private Credentials creds;              // presence flags only are ever read from here for output
    private boolean nativesMissing;
    private Input.Target lastTarget = new Input.Target(false, false, false, false, 0, 0);
    private int lastRaw;
    private String lastCredsLine = "";
    /** The last direct-write verdict, for the status line. Counts and reasons only -- never a value. */
    private String directWriteLine = "";

    public AutoLogin() {
        // First, so they sit at the top of the panel. Both empty by default: empty means "use the
        // file", and the two are only used together (Credentials.resolve).
        config.text("username", "Username",
                "Your login name or email. Used with the password below when both are filled in; "
                        + "leave both empty to read ~/.kewlklient/autologin.properties instead.", "");
        config.secret("password", "Password",
                "Masked wherever it is shown. Saved in the profile's config.json under ~/.kewlklient; "
                        + "leave it empty to keep the password in autologin.properties instead.", "");
        config.number("hotkey", "Hotkey (F-key, 0 = none)",
                "Toggles the plugin: on at the title screen = login now, on while enabled = abort. F7 by default.",
                7, 0, 8);
        config.number("settleSec", "Settle delay (s)",
                "Seconds to wait after the login screen appears before typing", 3, 0, 30);
        config.number("keyDelayMs", "Key delay (ms)",
                "Milliseconds between keystrokes. Raise it if typed characters go missing.", 60, 20, 300);
        // Seen live 2026-09-06: a rejection does NOT change the game state (it shows a separate
        // "Incorrect username or password" screen at the same raw value), so this timeout is also
        // how a rejection is detected -- it is counted against maxRejects, not maxAttempts.
        config.number("resultTimeoutSec", "Result timeout (s)",
                "Seconds to wait for the game state to leave the login screen after the Login click. "
                        + "No change by then = the rejection screen (the state does not move for one).", 12, 3, 60);
        config.number("maxAttempts", "Max attempts", "Scripts typed before giving up", 3, 1, 10);
        config.number("maxRejects", "Stop after N rejections",
                "A rejection is the 'Incorrect username or password' screen (no state change after the Login "
                        + "click) or a bounce back to the login screen after submitting. Do not hammer a wrong password.",
                2, 1, 5);
        config.number("loginState", "Raw game state of the login screen",
                "10 unless the log says otherwise ('raw state X is not the configured login state')", 10, 0, 50);
        // Seen live 2026-09-06: with "Remember username" ticked the form opens with the Login field
        // pre-filled and the caret already in the Password field, so the username is neither typed
        // nor clicked -- only the password field is cleared and filled. (This replaces the old
        // typeUsername / clickUsername pair: off here = click the username field, type both.)
        config.bool("usernameRemembered", "Username remembered by the client",
                "The client pre-fills the Login field and starts in the password field when \"Remember username\" "
                        + "is ticked (the default here). Off = click the username field and type both.", true);
        config.number("clearBackspaces", "Backspaces before typing",
                "Clears whatever is in the field first (the password field when the username is remembered)",
                20, 0, 40);
        config.bool("tabToPassword", "Tab from username to password",
                "Only when the username is not remembered. Off = click the password field at its offset instead", true);
        // Click targets, measured live 2026-09-06 (client-240-6, launcher path, canvas 1314x900).
        // NXT letterboxes the title screen (~1090x670 of drawn content) centred HORIZONTALLY but
        // TOP-aligned, so x is stable as an offset from the canvas centre and y as an offset from
        // the canvas top. The keys are new (not the old *Dx/*Dy) on purpose: a profile that stored
        // an offset under the old centre-relative y convention must not silently apply here.
        // These five stay COORDINATES on purpose, where the play button no longer is: at the login
        // screen this client reports "[no groups loaded]" live (2026-09-06, the fingerprint line in
        // the log), so the title screen is drawn with no interface components to walk -- there is
        // nothing for kewl.api.Widgets to find. If a live run ever shows groups at state 10, copy
        // what PlayButton does: seed Widgets.smallestContaining with the measurement below.
        config.bool("clickExistingUser", "Click the 'Existing User' button first",
                "The welcome box before the fields (seen live 2026-09-05/06)", true);
        // Existing User button centre = (centreX + 69, top + 288) live.
        config.number("existingX", "'Existing user' x from centre", "", 69, -800, 800);
        config.number("existingY", "'Existing user' y from top", "", 288, 0, 1500);
        // Login button = (centreX - 93, top + 315) live; clicking it is the only thing that submits
        // (Enter does nothing on this build, real or posted).
        config.number("loginX", "'Login' button x from centre", "The click that submits", -93, -800, 800);
        config.number("loginY", "'Login' button y from top", "", 315, 0, 1500);
        // "Try again" on the rejection screen = (centreX - 14, top + 288) live.
        config.number("tryAgainX", "'Try again' x from centre", "On the 'Incorrect username or password' screen", -14, -800, 800);
        config.number("tryAgainY", "'Try again' y from top", "", 288, 0, 1500);
        // Username field ~ (centreX - 82, top + 234), password field ~ (centreX - 82, top + 257) live.
        config.number("usernameX", "Username field x from centre", "Only when the username is not remembered", -82, -800, 800);
        config.number("usernameY", "Username field y from top", "", 234, 0, 1500);
        config.number("passwordX", "Password field x from centre", "Only when not remembered and not tabbing", -82, -800, 800);
        config.number("passwordY", "Password field y from top", "", 257, 0, 1500);
        // Seen live 2026-09-06: state 30 first shows the "Welcome to Old School RuneScape / Welcome
        // back" screen and the world only loads after its "CLICK HERE TO PLAY" button. That screen
        // is NOT letterboxed (it fills the 1314x900 canvas); the button centre measured at canvas
        // (654, 334) = (centreX - 3, top + 334). The state stays 30 across the click.
        config.bool("clickPlay", "Click 'CLICK HERE TO PLAY' after logging in",
                "The welcome screen at state 30; one click, only after a login this plugin typed", true);
        config.number("playX", "'Click here to play' x from centre", "", -3, -800, 800);
        config.number("playY", "'Click here to play' y from top", "", 334, 0, 1500);
        config.number("playDelaySec", "Delay before clicking play (s)",
                "Seconds after state 30 before the click; the welcome screen needs a moment to draw", 2, 0, 30);
        config.bool("probes", "Run the memory probes (diagnostic)",
                "Field and widget scans that walk the game's own structures. OFF: a session with them "
                        + "running ended in the game dying with heap corruption (2026-09-06). Only turn "
                        + "this on when deriving an offset, and expect the client to be less stable.", false);
        config.bool("showTargets", "Draw the click targets at the login screen",
                "Crosshairs at the offsets above, to calibrate them before typing", true);
        config.bool("grabFocus", "SetFocus the game before typing",
                "Not needed for the posted-message path; try it if nothing types and the log says focused=0", false);
        // Off: a logout (deliberate or a disconnect -- the plugin cannot tell them apart) leaves the
        // client at the title screen until the hotkey / panel toggle says "login now" again.
        config.bool("reloginAfterDisconnect", "Log in again after a disconnect",
                "Off = after a logout wait for the hotkey; on = re-type the login whenever the title screen returns", false);
        // OFF, and it stays off until a live run confirms the delta in client/offsets.hpp. It writes
        // into the client's own memory at an address found by searching for the username; every gate
        // is in FieldWriter and in nSetLoginField, and any doubt at all falls back to typing.
        config.bool("setFieldsDirectly", "Set the fields directly (experimental)",
                "Instead of typing, write the username and password into the client's own buffers. "
                        + "Immune to which screen is up and where the caret is -- but the address is derived by "
                        + "searching memory and is NOT VERIFIED, so anything unproven falls back to typing.", false);
    }

    @Override public String name() { return "Autologin"; }

    @Override public String description() { return "Types your login (panel settings, or ~/.kewlklient/autologin.properties) into the title screen."; }

    @Override public String[] tags() { return new String[] {"login", "title", "password"}; }

    /** F7 by default (F1/F2/F5 are taken); 0 disables the key. */
    @Override
    public int hotkey() {
        int f = config.number("hotkey");
        return f >= 1 && f <= 8 ? f - 1 : -1;
    }

    /**
     * The settings the sequence runs on, straight from this panel's declared defaults and edits.
     * Package-private, not private, so {@code AutoLoginDefaultsTest} can hold it against
     * {@link LoginSequence.Settings#defaults()} -- those two lists of numbers are the same live
     * measurements written out twice, and before that test nothing noticed when one moved (review
     * 2026-09-06).
     */
    LoginSequence.Settings settingsFromConfig() {
        return new LoginSequence.Settings(
                config.number("loginState"),
                config.number("settleSec") * 1000L,
                config.number("keyDelayMs"),
                config.number("resultTimeoutSec") * 1000L,
                config.number("maxAttempts"),
                config.number("maxRejects"),
                config.bool("usernameRemembered"),
                config.number("clearBackspaces"),
                config.bool("tabToPassword"),
                config.bool("clickExistingUser"),
                config.number("existingX"), config.number("existingY"),
                config.number("usernameX"), config.number("usernameY"),
                config.number("passwordX"), config.number("passwordY"),
                config.number("loginX"), config.number("loginY"),
                config.number("tryAgainX"), config.number("tryAgainY"),
                400,
                config.bool("reloginAfterDisconnect"),
                config.bool("clickPlay"),
                config.number("playX"), config.number("playY"),
                config.number("playDelaySec") * 1000L);
    }

    @Override
    protected void onEnable() {
        nativesMissing = false;
        directWriteLine = "";
        creds = credentials();
        lastCredsLine = creds.describe();
        sequence = new LoginSequence(InputSink.NATIVE, settingsFromConfig());
        // Click the play button as a WIDGET. The playX/playY measurement below is now the seed the
        // resolver looks the component up with, not the click itself: PlayButton walks the loaded
        // components once (at the moment the click is due -- the only frame the welcome screen is
        // certainly up), takes the smallest visible rectangle containing the measured point, and from
        // then on clicks THAT component's centre, so the click follows the window size. If nothing
        // contains the point -- which is what happens if this build's stored component positions turn
        // out to be parent-relative -- it falls back to the measured coordinate and the log and the
        // status line both say which path was used. Not a per-frame cost: one tree walk per session.
        sequence.playTarget(new PlayButton(PlayButton.Source.NATIVE));
        int raw = rawState();
        lastRaw = raw;
        // An enable AT the title screen (hotkey or panel) means "login now"; an enable during
        // start-up or a profile restore happens at raw state 0 and waits for the transition with the
        // full settle.
        if (raw == config.number("loginState")) sequence.loginNow();
        System.out.println("[autologin] enabled: " + lastCredsLine + "; raw state " + raw
                + (raw == config.number("loginState") ? " (login screen: login now)" : ""));
        creds = null;
    }

    @Override
    protected void onDisable() {
        if (sequence != null) {
            sequence.abort();
            System.out.println("[autologin] disabled -- aborted");
        }
        sequence = null;
        creds = null;
    }

    /** The per-attempt resolution: panel settings when both are set, else the file. See Credentials.resolve. */
    private Credentials credentials() {
        return Credentials.resolve(config.text("username"), config.text("password"), KewlKlient.dataDir());
    }

    private int rawState() {
        try {
            return Natives.gameState();
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public void tick() {
        if (sequence == null || nativesMissing) return;
        // Monotonic, NOT the wall clock. Review 2026-09-06: every deadline in the sequence (the
        // settle, the result timeout, the back-off, the play delay) is a comparison against this
        // number, so an NTP correction of a few seconds while AWAITING_RESULT used to jump straight
        // past the deadline -- counting a rejection and clicking "Try again" at (-14, 288) on
        // whatever screen was up -- and a backward step stalled the pacing until the clock caught up.
        long now = System.nanoTime() / 1_000_000L;
        int raw = rawState();
        // The welcome screen ("CLICK HERE TO PLAY") is an interface, so dump the components that carry
        // text while it is up: that is the evidence for clicking the button by its widget id instead of
        // by a pixel offset measured on one window size. Under KEWL_LOG, twice a session, never per
        // frame -- it walks the whole interface tree.
        // At the MOMENT the play click is due: state 30 is reached before the welcome screen has
        // drawn, and a dump taken then finds nothing (seen live 2026-09-06). playPending() goes false
        // as the click is queued, so this is the last frame the screen is certainly up.
        if (config.bool("probes") && System.getenv("KEWL_LOG") != null && raw == 30 && sequence.playPending()) {
            kewl.plugins.autologin.FieldProbe.dumpWidgets("the welcome screen, at the play click");
        }
        lastRaw = raw;
        sequence.reconfigure(settingsFromConfig());
        // Which of the title screen's faces is up. Only at the login state: in-game the client reports
        // hundreds of loaded groups and none of them says anything about a login form. The env check is
        // the same one FieldProbe uses -- the fingerprints are only LOGGED under KEWL_LOG, but they are
        // always read, because the script branches on them.
        if (raw == config.number("loginState")) {
            try {
                sequence.noteScreen(Natives.loadedGroups(), System.getenv("KEWL_LOG") != null);
            } catch (Throwable t) {
                // An old DLL without loadedGroups, or a client object that is not up: the sequence
                // falls back to how it got here, which is what it does for an unknown fingerprint too.
            }
        }
        sequence.step(now, raw);
        flushLog();

        if (!sequence.wantsScript()) return;

        // Once per attempt, at the moment the settle ends: resolve again (so filling the panel or
        // the file in without restarting the client is enough), then arm. The strings go straight
        // into the sequence's queue; nothing here keeps them.
        Credentials c = credentials();
        // Where does the client keep the form's fields? Once per session, with KEWL_LOG set, so the
        // username/password offsets can be derived live and the plugin can SET them instead of typing
        // (see FieldProbe). Read-only, and it prints addresses, never values.
        if (config.bool("probes") && System.getenv("KEWL_LOG") != null) {
            kewl.plugins.autologin.FieldProbe.run(c);
        }
        String line = c.describe();
        if (!line.equals(lastCredsLine)) {
            System.out.println("[autologin] " + line);
            lastCredsLine = line;
        }
        if (!c.fromPanel() && !c.filePresent()) {
            sequence.noCredentials("no credentials: fill in the panel's username and password, or create "
                    + Credentials.FILE);
        } else if (!c.passwordSet()) {
            sequence.noCredentials("password empty in " + Credentials.FILE);
        } else if (!config.bool("usernameRemembered") && !c.usernameSet()) {
            sequence.noCredentials("username empty in " + Credentials.FILE);
        } else {
            Input.Target t;
            try {
                t = Input.target(config.bool("grabFocus"));
            } catch (Throwable e) {
                // An old kewlklient.dll without the input natives: say so once instead of throwing
                // thirty times a second, and type nothing.
                nativesMissing = true;
                System.out.println("[autologin] input natives missing (" + e.getClass().getSimpleName()
                        + ") -- rebuild the DLL");
                sequence.abort();
                return;
            }
            lastTarget = t;
            // The experimental path: put the two strings in the client's own buffers and let the
            // script do nothing but click Login. Every refusal is a fall back to typing, with the
            // reason in the status line -- FieldWriter builds it from counts and refusal words only.
            boolean direct = false;
            if (config.bool("setFieldsDirectly")) {
                FieldWriter.Result r;
                try {
                    r = c.setDirectly(FieldWriter.NATIVE);
                } catch (Throwable e) {
                    r = new FieldWriter.Result(false, "direct write: this DLL has no setLoginField ("
                            + e.getClass().getSimpleName() + ") -- rebuild it; typing instead");
                }
                direct = r.wrote();
                if (!r.status().equals(directWriteLine)) {
                    directWriteLine = r.status();
                    System.out.println("[autologin] " + r.status());
                }
            } else {
                directWriteLine = "";
            }
            c.armInto(sequence, t.w(), t.h(), direct);
            // Counts of typed characters -- and the step total they add up to -- stay out of the log: a
            // password length is still a fact about the password. Only "set/empty" is ever printed,
            // and since review 2026-09-06 "set/empty" is all the sequence will even tell us.
            System.out.printf("[autologin] attempt %d/%d: %s (screen %s, user %s, pass %s)%n",
                    sequence.attempts() + 1, config.number("maxAttempts"), t.describe(),
                    sequence.armedScreen(),
                    direct ? "written directly"
                            : config.bool("usernameRemembered") ? "remembered by the client"
                            : sequence.usernameTyped() ? "set" : "empty",
                    direct ? "written directly" : sequence.passwordTyped() ? "set" : "empty");
        }
        flushLog();
    }

    private void flushLog() {
        for (String l : sequence.drainLog()) System.out.println("[autologin] " + l);
    }

    @Override
    public String status() {
        if (nativesMissing) return "input natives missing -- rebuild the DLL";
        if (sequence == null) return "";
        // "if anything is unverified, fall back to typing and SAY SO": the fall-back reason leads,
        // because it is the thing the reader has to act on.
        return directWriteLine.isEmpty() ? sequence.status() : directWriteLine + " | " + sequence.status();
    }

    // -----------------------------------------------------------------------------------------------
    // Drawing: only at the login screen, so the offsets can be calibrated before anything is typed.
    // -----------------------------------------------------------------------------------------------

    @Override
    public void render(Graphics2D g) {
        if (sequence == null) return;
        int raw = lastRaw;
        int[] view = Natives.viewport();
        int w = view.length == 4 ? view[2] : 0, h = view.length == 4 ? view[3] : 0;
        g.setFont(Theme.UI);
        // Gate on the PHASE, not on the raw state. Review 2026-09-06: a world hop runs 30 -> 45 -> 25
        // -> 30, and "raw != 30" flashed this title-screen diagnostic panel over the loaded game on
        // every hop and every loading screen. The sequence stays in LOGGED_IN across all of that.
        if (sequence.phase() == LoginSequence.Phase.LOGGED_IN) {
            // In-game the only thing worth drawing is the "CLICK HERE TO PLAY" target, and only while
            // that click is still owed (the welcome screen is up); after it, nothing -- the panel is
            // a title-screen diagnostic, not a permanent HUD.
            // The crosshair stays on the MEASURED point, which is the point the resolver is seeded
            // with -- it is what has to be calibrated. Where the click finally lands (that point, or
            // the centre of the component containing it) is in the status line, because drawing the
            // resolved rectangle would mean walking the interface tree every frame.
            if (sequence.playPending() && config.bool("showTargets") && w > 0 && h > 0) {
                crosshair(g, w / 2 + config.number("playX"), config.number("playY"), "play", config.bool("clickPlay"));
            }
            return;
        }

        Hud.Lines lines = new Hud.Lines()
                .add("phase", status())
                .add("raw state", raw + " (login = " + config.number("loginState") + ")")
                // Which of the title screen's faces this is, and therefore which script it gets. The
                // fingerprint is not drawn: it belongs in KEWL_LOG, where it can be copied out.
                .add("screen", sequence.screen() + (sequence.screenFromFingerprint()
                        ? " (from the loaded interface groups)" : " (from how we got here)"))
                .add("attempt", (sequence.attempts() + 1) + "/" + config.number("maxAttempts")
                        + ", rejections " + sequence.rejects())
                .add("credentials", lastCredsLine)
                .add("input", lastTarget.describe());
        Hud.panel(g, 12, 12, "Autologin", lines);

        if (raw != config.number("loginState") || !config.bool("showTargets") || w <= 0 || h <= 0) return;
        // Same convention as the script: x from the canvas centre, y from the canvas top (NXT
        // letterboxes the title screen top-aligned, so a centre-relative y would drift with height).
        int cx = w / 2;
        boolean remembered = config.bool("usernameRemembered");
        // Bright = the script for THIS screen will click it. On the disconnect screen "existing" goes
        // dim and "pass" lights up, which is the whole fix drawn on the canvas.
        var screen = sequence.screen();
        crosshair(g, cx + config.number("existingX"), config.number("existingY"), "existing",
                config.bool("clickExistingUser") && screen.clicksExistingUser());
        crosshair(g, cx + config.number("usernameX"), config.number("usernameY"), "user", !remembered);
        crosshair(g, cx + config.number("passwordX"), config.number("passwordY"), "pass",
                remembered ? screen.focusesPasswordField() : !config.bool("tabToPassword"));
        crosshair(g, cx + config.number("loginX"), config.number("loginY"), "login", true);
        crosshair(g, cx + config.number("tryAgainX"), config.number("tryAgainY"), "try again", true);
    }

    /** A crosshair with a label; bright when the script will click it, dim when it is preview only. */
    private static void crosshair(Graphics2D g, int x, int y, String label, boolean active) {
        Color c = active ? Theme.WARN : Theme.TEXT_DIM;
        g.setColor(c);
        g.drawLine(x - 10, y, x + 10, y);
        g.drawLine(x, y - 10, x, y + 10);
        g.drawOval(x - 5, y - 5, 10, 10);
        Hud.text(g, label + (active ? "" : " (unused)"), x + 12, y + 4, c);
    }
}

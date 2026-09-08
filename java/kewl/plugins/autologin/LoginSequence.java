package kewl.plugins.autologin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The login state machine: pure, paced, password-blind.
 *
 * <p>Pure: it touches no native and no clock of its own -- the plugin feeds it {@link #step(long,
 * int)} with the frame's time and the raw game state, and it emits through an {@link InputSink}.
 * Paced: at most one keystroke per {@code keyDelayMs}, a key or click taking two slots (down, then
 * up), so nothing is ever sent in a burst the login screen might drop. Password-blind: the script is
 * a queue of characters that is drained as it types and cleared on every exit; no field, no log line
 * and no {@link #status()} ever carries a character of it -- {@link #drainLog()} reports states and
 * the count of keys/clicks, never a character count and never how long typing took (review
 * 2026-09-06: both give the length away, the second by arithmetic -- elapsed / keyDelayMs minus twice
 * the control steps IS the number of characters).</p>
 *
 * <h2>The title screen as seen live (2026-09-06, client-240-6)</h2>
 * <p>A welcome box (New User / Existing User) precedes the form. With "Remember username" ticked the
 * form opens with the Login field pre-filled and the caret in the Password field. Enter does NOT
 * submit the form -- neither a real keyboard Enter nor a posted one -- only a click on the Login
 * button does. A rejected login shows a separate "Incorrect username or password" screen with a
 * "Try again" button, and the raw game state stays at the login value the whole time, so a rejection
 * is indistinguishable from "nothing happened" by state alone: a result timeout IS the rejection
 * screen here. Click targets are canvas pixels: x from the canvas centre, y from the canvas TOP,
 * because NXT letterboxes the title screen centred horizontally but top-aligned.</p>
 *
 * <h2>Which screen, not just which state</h2>
 * <p>Raw state 10 is several different screens (see {@link LoginScreen}), and the script differs
 * between them in exactly two places: whether Existing User is clicked, and whether the password
 * field must be clicked before typing. {@link #noteScreen} feeds this machine the client's loaded
 * interface groups every frame at the title screen; {@link #screen()} answers with the fingerprint's
 * verdict when it is a known one, and otherwise with {@link #provenance()} -- how we arrived here,
 * which is all we have until a live run fills {@link LoginScreen#KNOWN} in. Nothing about this is
 * inferred from a widget POSITION: those are not trustworthy on this build.</p>
 *
 * <h2>Phases</h2>
 * <pre>
 *   WAITING_FOR_LOGIN_SCREEN --(raw == loginState)--> SETTLING --(deadline)--> [plugin arms a script]
 *       --> RUNNING --(script drained, Login clicked)--> AWAITING_RESULT --(raw 20/25)--> SUBMITTED --(30)--> LOGGED_IN
 *   AWAITING_RESULT --(timeout, raw unchanged)--> the rejection screen: DISMISSING_REJECTION (clicks
 *                   Try again) --> SETTLING --> the attempt script again (without the welcome click);
 *                   or GAVE_UP at maxRejects
 *   SUBMITTED --(raw back to loginState)--> a server bounce; BACKOFF, or GAVE_UP at maxRejects
 *   anywhere --(raw 11)--> STOPPED_AUTHENTICATOR: a human is needed, no retry
 *   LOGGED_IN (after a login this machine typed) --(playDelayMs)--> ONE click on "CLICK HERE TO PLAY":
 *                   seen live 2026-09-06, state 30 first shows the "Welcome to Old School RuneScape /
 *                   Welcome back" screen and the world only loads after that button (centre - 3,
 *                   top + 334 on the un-letterboxed 1314x900 canvas); the state stays 30 throughout
 *   LOGGED_IN --(raw == loginState again)--> IDLE: waits for loginNow() (hotkey / panel toggle); or,
 *                   with reloginAfterDisconnect, WAITING with the counters reset (an automatic relogin)
 *   NO_CREDENTIALS: the plugin found no password; re-checked every few seconds so filling the file
 *                   in while the client sits at the title screen is enough
 * </pre>
 */
public final class LoginSequence {

    public enum Phase {
        WAITING_FOR_LOGIN_SCREEN, SETTLING, RUNNING, AWAITING_RESULT, SUBMITTED, LOGGED_IN, BACKOFF,
        GAVE_UP, STOPPED_AUTHENTICATOR, NO_CREDENTIALS,
        /** Logged out after a login; nothing is typed until {@link #loginNow()}. */
        IDLE,
        /** Clicking "Try again" on the rejection screen; the attempt script is re-armed after it. */
        DISMISSING_REJECTION
    }

    /**
     * Everything the plugin's settings decide, as one immutable value the plugin rebuilds per tick.
     * Click targets: {@code *X} is the offset from the canvas centre, {@code *Y} the offset from the
     * canvas top (see the class comment for why).
     */
    public record Settings(int loginState, long settleMs, long keyDelayMs, long resultTimeoutMs,
                           int maxAttempts, int maxRejects, boolean usernameRemembered, int clearBackspaces,
                           boolean tabToPassword, boolean clickExistingUser, int existingX, int existingY,
                           int usernameX, int usernameY, int passwordX, int passwordY,
                           int loginX, int loginY, int tryAgainX, int tryAgainY,
                           long afterClickMs, boolean reloginAfterDisconnect,
                           boolean clickPlay, int playX, int playY, long playDelayMs) {

        /** The defaults the plugin declares (the 2026-09-06 live measurements), for tests. */
        public static Settings defaults() {
            return new Settings(10, 3000, 60, 12000, 3, 2, true, 20, true, true, 69, 288, -82, 234,
                    -82, 257, -93, 315, -14, 288, 400, false, true, -3, 334, 2000);
        }
    }

    /**
     * Where the one "CLICK HERE TO PLAY" click goes. The seam that keeps this class pure: the
     * measured coordinate is passed IN, and an implementation may answer with something better --
     * {@link PlayButton} walks the loaded components and returns the centre of the smallest one that
     * contains that coordinate, so the click follows the window instead of a measurement.
     *
     * <p>{@link #OFFSETS} is the identity: it hands the measured point straight back. It is the
     * default, and it is what a throwing or empty-handed resolver falls back to, so there is always
     * a point to click and the feature can never be worse than the coordinate it started from.</p>
     */
    public interface PlayTarget {

        /**
         * Where to click, and how that point was chosen. {@code how} is a mechanism, printed to the
         * log and the status line -- it must stay free of anything typed at the login screen.
         */
        record Resolution(int x, int y, String how) {}

        /**
         * @param canvasW canvas width, or 0 when it is not known
         * @param canvasH canvas height, or 0 when it is not known
         * @param offsetX the measured x -- the canvas centre plus the configured offset
         * @param offsetY the measured y -- from the canvas top
         */
        Resolution resolve(int canvasW, int canvasH, int offsetX, int offsetY);

        /** The measured coordinate, unchanged. The default, and every fall back. */
        PlayTarget OFFSETS = (w, h, x, y) -> new Resolution(x, y, "the configured offset");
    }

    // -- the script. A Chr prints as "*" so that no toString of a step or of the queue can leak it.

    sealed interface Step permits Chr, Key, Mouse, Pause {}

    record Chr(int c) implements Step {
        @Override public String toString() { return "*"; }
    }

    /** Down now, up on the next emission slot. */
    record Key(int vk) implements Step {}

    /** Move + down now, up on the next emission slot. Canvas coordinates. */
    record Mouse(int x, int y) implements Step {}

    /** Stalls the queue for this long. */
    record Pause(long ms) implements Step {}

    /** Win32 virtual keys, duplicated from kewl.api.Input so this class stays free of the api package. */
    static final int VK_BACK = 0x08, VK_TAB = 0x09, VK_RETURN = 0x0D;

    /** Raw states that mean "past the title screen" on the Java client's numbering. */
    static final int STATE_LOGGING_IN = 20, STATE_LOADING = 25, STATE_LOGGED_IN = 30, STATE_AUTHENTICATOR = 11;

    /**
     * Values the client passes through that are neither the title screen nor a settled in-game state:
     * a world hop runs 30 -> 45 -> 25 -> 30, and the loading/starting screens use these too. They are
     * expected -- review 2026-09-06: without them {@link #noteState} advised the user to set
     * loginState to 45 after their first hop, and following that advice would have the sequence treat
     * every later hop as the title screen and type the password into the game.
     */
    static final Set<Integer> HOP_OR_LOADING_STATES = Set.of(40, 45, 1000);

    /** How long the settle is when the user pressed "login now" at the title screen. */
    static final long QUICK_SETTLE_MS = 500;

    /** How often NO_CREDENTIALS re-reads the file. */
    static final long NO_CREDENTIALS_RETRY_MS = 5000;

    static final long MAX_BACKOFF_MS = 60_000;

    /** The log line for the timeout-is-a-rejection case; the plugin prefixes it with "[autologin] ". */
    static final String REJECTION_ASSUMED =
            "no state change after Login click: assuming the rejection screen, clicking Try again";

    private final InputSink sink;
    private Settings s;

    private Phase phase = Phase.WAITING_FOR_LOGIN_SCREEN;
    private long deadline;              // meaning depends on the phase: settle end, result end, backoff end
    private long pauseUntil;
    private long lastEmitMs = Long.MIN_VALUE / 2;
    // When the last script finished. Only ever used as a delta against a LATER event (the server's
    // answer), never as the script's own duration -- see the RUNNING case for why that would leak.
    // The flag says whether it means anything yet: the frame clock is monotonic, not the wall clock,
    // so "scriptEndMs > 0" is not a safe stand-in for "a script has run" (review 2026-09-06).
    private long scriptEndMs;
    private boolean scriptEnded;
    private boolean quickSettle;
    private boolean scriptWanted;
    private String noCredentialsReason = "";

    private final Deque<Step> script = new ArrayDeque<>();
    private Step pendingUp;             // a Key/Mouse whose up half is still owed
    private int controlSteps;           // keys and clicks in the script: safe to log, unlike a character count
    private int usernameChars, passwordChars;
    private int canvasW, canvasH;       // from the last arm(); the Try-again click needs them later
    // After "Try again" the client is back on the form, not the welcome box: the next arm() must not
    // click "Existing User" (that spot is next to Cancel on the form).
    private boolean skipWelcomeClick;
    // The "CLICK HERE TO PLAY" click: scheduled only by the paths that reach LOGGED_IN from a login
    // this machine typed (SUBMITTED / leftTitle / BACKOFF -> 30). A manual login noticed from IDLE,
    // GAVE_UP, NO_CREDENTIALS or the authenticator stop does NOT schedule it: a human is at the
    // keyboard then and a posted click at a fixed spot would land on whatever they are doing.
    private boolean playPending;
    private long playAt;
    private boolean playClicked;
    // Where that click actually went, in words, for the log and the status line. Empty until a click
    // has been posted; cleared again on every entry into LOGGED_IN, so it always describes the click
    // of THIS login and never the previous one.
    private String playHow = "";
    // How the point is chosen. OFFSETS is the measured coordinate and is the DEFAULT on purpose: this
    // class stays pure, and a resolver that needs the game (PlayButton, which walks the loaded
    // components) is injected by the plugin through playTarget().
    private PlayTarget playTarget = PlayTarget.OFFSETS;

    // -- which screen is up. See LoginScreen for the two sources and why the book is empty.
    private Map<String, LoginScreen> book = LoginScreen.KNOWN;
    private LoginScreen fromFingerprint = LoginScreen.UNKNOWN;
    private String lastFingerprint = "\0none";      // not "" -- that IS a fingerprint (no groups loaded)
    private final Set<String> fingerprintsLogged = new HashSet<>();
    // Did we arrive at the title screen from the loaded world? That is a disconnect, and a disconnect
    // shows the form directly (live 2026-09-06), not the welcome box. This is the signal that fixes
    // the reported bug; the fingerprint is what will one day replace it.
    private boolean arrivedFromWorld;
    private LoginScreen armedScreen = LoginScreen.WELCOME;
    private boolean fieldsSetDirectly;

    private int attempts, rejects;
    private int lastRawState = Integer.MIN_VALUE;
    // Has this client ever reached the in-game state? Only until then can an unknown raw state
    // plausibly BE the login screen -- see noteState.
    private boolean everLoggedIn;
    private final Set<Integer> oddStatesLogged = new HashSet<>();
    private final List<String> log = new ArrayList<>();

    public LoginSequence(InputSink sink, Settings settings) {
        this.sink = sink;
        this.s = settings;
    }

    /** Apply new settings. Cheap; the plugin calls it every tick so panel edits apply live. */
    public void reconfigure(Settings settings) { this.s = settings; }

    public Phase phase() { return phase; }

    /** Attempts finished (a script run to the Login click and not accepted) so far; the current one is this plus one. */
    public int attempts() { return attempts; }

    public int rejects() { return rejects; }

    /**
     * Whether the armed script types a username at all -- never how many characters it is. Review
     * 2026-09-06: the counts were public getters, which puts a password length one {@code println}
     * away from anybody's log, and the plugin only ever printed "set"/"empty" from them. The counts
     * themselves are now package-private, for the tests in this package that check what was armed.
     */
    public boolean usernameTyped() { return usernameChars > 0; }

    /** Whether the armed script types a password at all. See {@link #usernameTyped()} for why this is not a count. */
    public boolean passwordTyped() { return passwordChars > 0; }

    int usernameChars() { return usernameChars; }

    int passwordChars() { return passwordChars; }

    /** Steps still queued (tests check this reaches 0 -- nothing of the password lingers). */
    public int queued() { return script.size() + (pendingUp != null ? 1 : 0); }

    /**
     * True when the machine has settled and is waiting for the plugin to {@link #arm} a script (or
     * to say {@link #noCredentials}). The plugin re-reads the credentials file at exactly this point,
     * once per attempt.
     */
    public boolean wantsScript() { return scriptWanted; }

    /** True while the "CLICK HERE TO PLAY" click is scheduled and not yet posted (the overlay draws its target then). */
    public boolean playPending() { return playPending; }

    /**
     * Wire in how the play click is placed. Null restores {@link PlayTarget#OFFSETS}, so a caller can
     * never leave the machine without a target: the measured coordinate is always the floor.
     */
    public void playTarget(PlayTarget t) { this.playTarget = t == null ? PlayTarget.OFFSETS : t; }

    /**
     * How the last play click was placed, in the resolver's own words ("widget 549:12 ...", or "the
     * configured offset ..."). It is a PATH, never a credential -- the same rule as the rest of this
     * class: states and mechanisms may be printed, nothing typed may.
     */
    public String playHow() { return playHow; }

    // -----------------------------------------------------------------------------------------------
    // Which screen is up
    // -----------------------------------------------------------------------------------------------

    /**
     * This frame's loaded interface groups, straight from {@code Natives.loadedGroups()}. The plugin
     * calls it every tick while the raw state is the login state and never in-game; a null or empty
     * array is fine and is itself a fingerprint.
     *
     * <p>{@code verbose} (the plugin passes "is KEWL_LOG set") logs each DISTINCT fingerprint once,
     * with the screen this machine believed was up at the time. That line is the entire point of the
     * mechanism today: it is what tells the next reader which ids mean which screen, so
     * {@link LoginScreen#KNOWN} can stop being empty. The ids are the client's own small integers --
     * there is nothing of ours in them.</p>
     */
    public void noteScreen(int[] loadedGroups, boolean verbose) {
        String fp = LoginScreen.fingerprint(loadedGroups);
        fromFingerprint = LoginScreen.ofFingerprint(fp, book);
        if (fp.equals(lastFingerprint)) return;
        lastFingerprint = fp;
        if (!verbose || !fingerprintsLogged.add(fp)) return;
        log.add("login screen fingerprint [" + (fp.isEmpty() ? "no groups loaded" : fp) + "] -- "
                + (fromFingerprint == LoginScreen.UNKNOWN
                        ? "not in LoginScreen.KNOWN; going by how we got here, which says " + provenance()
                          + ". Put this fingerprint in that map once you have seen which screen it was."
                        : "known: " + fromFingerprint));
    }

    /**
     * The screen the next script will be built for: the fingerprint's verdict when the fingerprint is
     * a known one, otherwise {@link #provenance()}.
     */
    public LoginScreen screen() {
        return fromFingerprint == LoginScreen.UNKNOWN ? provenance() : fromFingerprint;
    }

    /**
     * Which screen "how we got here" says is up, for every fingerprint that is not in the book -- that
     * is, all of them today.
     *
     * <ul>
     *   <li>The title screen reached from the loaded world is a DISCONNECT: live 2026-09-06 that is
     *       the form itself, username pre-filled, "Please enter your password", with no welcome box.
     *       Clicking Existing User there is the reported bug.</li>
     *   <li>The screen after a "Try again" click is the FORM: the client is already past the welcome
     *       box by then (this is what the old {@code skipWelcomeClick} flag said, in one boolean).</li>
     *   <li>Everything else is the WELCOME box, which is exactly today's behaviour -- and a cold start
     *       is "everything else". A server bounce back to the login screen (state 20 -> 10) also lands
     *       here: nobody has yet seen which screen the client puts up after one, so it keeps the
     *       behaviour that has been running rather than a guess.</li>
     * </ul>
     */
    public LoginScreen provenance() {
        if (skipWelcomeClick) return LoginScreen.FORM;
        if (arrivedFromWorld) return LoginScreen.DISCONNECT;
        return LoginScreen.WELCOME;
    }

    /** What the last {@link #arm} built its script for. For the panel's diagnostic line. */
    public LoginScreen armedScreen() { return armedScreen; }

    /**
     * True when {@link #screen()} came from the client's loaded interface groups rather than from
     * {@link #provenance()}. False for every fingerprint today, since {@link LoginScreen#KNOWN} is
     * empty -- which is exactly what the panel should be saying out loud.
     */
    public boolean screenFromFingerprint() { return fromFingerprint != LoginScreen.UNKNOWN; }

    /** True when the last {@link #arm} was told the fields were already written, so it types nothing. */
    public boolean fieldsSetDirectly() { return fieldsSetDirectly; }

    /**
     * Replace the fingerprint table. For tests, and for anyone who wants to try candidate ids without
     * a rebuild; the shipped table is {@link LoginScreen#KNOWN}.
     */
    void setScreenBook(Map<String, LoginScreen> b) {
        book = b == null ? LoginScreen.KNOWN : b;
        lastFingerprint = " none";
    }

    /**
     * "Login now": the next settle is {@value #QUICK_SETTLE_MS} ms, and a terminal phase (gave up,
     * authenticator, no credentials, idle after a logout) is reset so a fresh enable at the title
     * screen starts over.
     */
    public void loginNow() {
        quickSettle = true;
        if (phase == Phase.GAVE_UP || phase == Phase.STOPPED_AUTHENTICATOR || phase == Phase.NO_CREDENTIALS
                || phase == Phase.BACKOFF || phase == Phase.IDLE) {
            attempts = 0;
            rejects = 0;
            enter(Phase.WAITING_FOR_LOGIN_SCREEN, 0);
        }
    }

    /** Stop everything: clear the script, forget the counters, wait for the login screen again. */
    public void abort() {
        clearScript();
        attempts = 0;
        rejects = 0;
        scriptWanted = false;
        enter(Phase.WAITING_FOR_LOGIN_SCREEN, 0);
    }

    /** The plugin found no usable credentials. Re-checked after {@value #NO_CREDENTIALS_RETRY_MS} ms. */
    public void noCredentials(String reason) {
        scriptWanted = false;
        clearScript();
        if (phase != Phase.NO_CREDENTIALS || !reason.equals(noCredentialsReason)) {
            log.add(reason + " -- nothing typed; re-checking the file every "
                    + NO_CREDENTIALS_RETRY_MS / 1000 + " s");
        }
        noCredentialsReason = reason;
        deadline = 0;   // set on the first step in that phase, which knows the time
        phase = Phase.NO_CREDENTIALS;
    }

    /**
     * Build this attempt's script. Only counts are kept about the arguments; the strings themselves
     * become a queue of characters that {@link #step} drains.
     *
     * <p>The script, as the live flow needs it, on the WELCOME screen: Existing User click -> settle
     * -> with {@code usernameRemembered}: backspaces (the caret already sits in the pre-filled form's
     * password field), password; otherwise: click the username field, backspaces, username, Tab or a
     * click into the password field, password. Then Enter (two slots; does nothing on this build,
     * kept in case another accepts it) and the Login CLICK, which is what actually submits.</p>
     *
     * <p>On every OTHER screen ({@link #screen()}) two things change and nothing else: the Existing
     * User click is not made -- on the form that spot sits next to Cancel and on the disconnect
     * screen it is not a button at all -- and, on the remembered path, the password field is clicked
     * before the backspaces, because nothing has told us where the caret is on a screen that was
     * already up when we arrived. The not-remembered path is unchanged: it already clicks its way
     * into both fields.</p>
     *
     * <p>Only honoured when {@link #wantsScript()} says a script was asked for -- that is, from the
     * end of a settle or from the no-credentials re-check. Review 2026-09-06: an {@code arm()} from
     * anywhere else (a give-up, an idle, a future "arm eagerly on enable") built the queue in a phase
     * whose {@code step} never drains it, so the password sat in memory for the rest of the session
     * and the class comment's "cleared on every exit" was not true of that path.</p>
     *
     * @param canvasW canvas size, so click offsets can be centred; 0 disables the clicks (and with
     *                them the submit -- logged, since the attempt cannot then succeed)
     */
    public void arm(String username, String password, int canvasW, int canvasH) {
        arm(username, password, canvasW, canvasH, false);
    }

    /**
     * As {@link #arm(String, String, int, int)}, but {@code fieldsAlreadySet} says the two values are
     * already in the client's own buffers ({@link FieldWriter}) and must NOT be typed. The script is
     * then the screen's navigation and the Login click, nothing else -- no backspaces, no characters,
     * no focus click, since there is no field to put a caret in.
     *
     * <p>The Existing User click is still made on the welcome box: the form has to be on screen for
     * the Login button to be there to click. Whether the client re-reads or re-blanks its buffers when
     * it draws the form is NOT VERIFIED -- if the next live run shows an empty form after a direct
     * write, that is the reason.</p>
     */
    public void arm(String username, String password, int canvasW, int canvasH, boolean fieldsAlreadySet) {
        clearScript();
        if (!scriptWanted) {
            usernameChars = 0;
            passwordChars = 0;
            controlSteps = 0;
            fieldsSetDirectly = false;
            log.add("arm() ignored in phase " + phase + ": no script was asked for");
            return;
        }
        scriptWanted = false;
        fieldsSetDirectly = fieldsAlreadySet;
        this.canvasW = canvasW;
        this.canvasH = canvasH;
        int cx = canvasW / 2;
        boolean canClick = canvasW > 0 && canvasH > 0;
        if (!canClick) log.add("canvas size unknown: clicks skipped, so nothing will submit -- check the input target");
        LoginScreen screen = screen();
        armedScreen = screen;
        boolean welcome = s.clickExistingUser() && screen.clicksExistingUser();
        skipWelcomeClick = false;
        log.add("screen: " + screen + (fromFingerprint == LoginScreen.UNKNOWN ? " (from how we got here)"
                : " (from the loaded interface groups)")
                + " -- " + (welcome ? "clicking Existing User first" : "no Existing User click")
                + (fieldsAlreadySet ? ", fields already set, typing nothing" : ""));
        if (welcome && canClick) {
            script.add(new Mouse(cx + s.existingX(), s.existingY()));
            script.add(new Pause(s.afterClickMs()));
        }
        usernameChars = 0;
        passwordChars = 0;
        if (fieldsAlreadySet) {
            // Nothing is typed at all: straight to the submit. Enter is skipped too -- it does nothing
            // on this build and an unfocused form is the last place to send a stray key.
            if (canClick) script.add(new Mouse(cx + s.loginX(), s.loginY()));
            finishArm();
            return;
        }
        if (s.usernameRemembered()) {
            // On a screen that was already up when we arrived, the caret is wherever the client left
            // it -- the welcome box is the only one that hands it to us in the password field.
            if (screen.focusesPasswordField() && canClick) {
                script.add(new Mouse(cx + s.passwordX(), s.passwordY()));
                script.add(new Pause(200));
            }
            for (int i = 0; i < s.clearBackspaces(); i++) script.add(new Key(VK_BACK));
        } else {
            if (canClick) {
                script.add(new Mouse(cx + s.usernameX(), s.usernameY()));
                script.add(new Pause(200));
            }
            for (int i = 0; i < s.clearBackspaces(); i++) script.add(new Key(VK_BACK));
            if (username != null) {
                for (int i = 0; i < username.length(); i++) {
                    script.add(new Chr(username.charAt(i)));
                    usernameChars++;
                }
            }
            if (s.tabToPassword() || !canClick) {
                script.add(new Key(VK_TAB));
            } else {
                script.add(new Mouse(cx + s.passwordX(), s.passwordY()));
                script.add(new Pause(200));
            }
        }
        if (password != null) {
            for (int i = 0; i < password.length(); i++) {
                script.add(new Chr(password.charAt(i)));
                passwordChars++;
            }
        }
        script.add(new Key(VK_RETURN));
        if (canClick) script.add(new Mouse(cx + s.loginX(), s.loginY()));
        finishArm();
    }

    /** The tail both arm paths share: count the safe-to-log steps and start running. */
    private void finishArm() {
        controlSteps = 0;
        for (Step st : script) if (st instanceof Key || st instanceof Mouse) controlSteps++;
        pauseUntil = 0;
        if (phase == Phase.SETTLING || phase == Phase.WAITING_FOR_LOGIN_SCREEN || phase == Phase.BACKOFF
                || phase == Phase.NO_CREDENTIALS) {
            phase = Phase.RUNNING;
        }
    }

    /**
     * One frame. {@code rawState} is the client's own game-state field ({@code Natives.gameState()});
     * {@code nowMs} any monotonic-enough clock in milliseconds.
     */
    public Phase step(long nowMs, int rawState) {
        lastStepMs = nowMs;
        noteState(rawState);
        switch (phase) {
            case WAITING_FOR_LOGIN_SCREEN -> {
                if (rawState == s.loginState()) {
                    long settle = quickSettle ? QUICK_SETTLE_MS : s.settleMs();
                    quickSettle = false;
                    enter(Phase.SETTLING, nowMs + settle);
                } else if (rawState == STATE_LOGGED_IN) {
                    enter(Phase.LOGGED_IN, 0);
                }
            }
            case SETTLING -> {
                if (leftTitle(nowMs, rawState)) return phase;
                if (nowMs >= deadline) scriptWanted = true;
            }
            case RUNNING -> {
                if (leftTitle(nowMs, rawState)) return phase;
                emit(nowMs);
                if (script.isEmpty() && pendingUp == null) {
                    scriptEndMs = nowMs;
                    scriptEnded = true;
                    // Keys/clicks only, and NO elapsed time. Review 2026-09-06: the script is one
                    // emission slot per character and two per key/click, all paced by keyDelayMs, so
                    // "done in T ms" was the password length written out longhand --
                    // T / keyDelayMs - 2 * controlSteps, minus the fixed pauses, is the count. The
                    // control-step total on its own is a constant of the settings, not of the secret.
                    log.add("script done (" + controlSteps
                            + " keys/clicks, Login clicked); waiting up to " + s.resultTimeoutMs()
                            + " ms for the state to leave " + s.loginState());
                    enter(Phase.AWAITING_RESULT, nowMs + s.resultTimeoutMs());
                }
            }
            case AWAITING_RESULT -> {
                if (leftTitle(nowMs, rawState)) return phase;
                if (nowMs >= deadline) rejectionAssumed();
            }
            case DISMISSING_REJECTION -> {
                if (leftTitle(nowMs, rawState)) return phase;
                emit(nowMs);
                if (script.isEmpty() && pendingUp == null) {
                    log.add("Try again clicked; settling " + s.settleMs() + " ms, then attempt "
                            + (attempts + 1) + "/" + s.maxAttempts()
                            + " (no welcome click: the form is already up)");
                    skipWelcomeClick = true;
                    enter(Phase.SETTLING, nowMs + s.settleMs());
                }
            }
            case SUBMITTED -> {
                if (rawState == STATE_LOGGED_IN) {
                    log.add("logged in (state " + rawState + ")");
                    enter(Phase.LOGGED_IN, 0);
                    schedulePlay(nowMs);
                } else if (rawState == STATE_AUTHENTICATOR) {
                    log.add("authenticator screen (state 11) -- stopped, a human is needed");
                    enter(Phase.STOPPED_AUTHENTICATOR, 0);
                } else if (rawState == s.loginState()) {
                    rejects++;
                    attempts++;
                    log.add("server bounced us back to the login screen (rejection " + rejects + "/"
                            + s.maxRejects() + ")");
                    if (rejects >= s.maxRejects()) {
                        giveUpRejected();
                    } else {
                        failAttempt(nowMs, "gave up after " + attempts + " attempts");
                    }
                }
            }
            case LOGGED_IN -> {
                if (rawState == STATE_LOGGED_IN) {
                    if (playPending && nowMs >= playAt) {
                        playPending = false;
                        if (s.clickPlay()) {
                            // The measured coordinate is what the resolver is SEEDED with, not a
                            // fallback bolted on after it: PlayButton takes the smallest loaded
                            // component containing this point, so a wrong seed and a missing seed
                            // both end at the same place -- this point.
                            int ox = canvasW / 2 + s.playX();
                            int oy = s.playY();
                            PlayTarget.Resolution r = null;
                            try {
                                r = playTarget.resolve(canvasW, canvasH, ox, oy);
                            } catch (Throwable t) {
                                // An old DLL with no widget natives throws UnsatisfiedLinkError from
                                // inside the resolver. That must cost the click nothing: this class
                                // is the one that still has to press the button.
                                r = new PlayTarget.Resolution(ox, oy,
                                        "the configured offset (the resolver threw "
                                                + t.getClass().getSimpleName() + ")");
                            }
                            if (r == null) {
                                r = new PlayTarget.Resolution(ox, oy,
                                        "the configured offset (the resolver found nothing)");
                            }
                            playHow = r.how();
                            script.add(new Mouse(r.x(), r.y()));
                            pauseUntil = 0;
                            playClicked = true;
                            // Where it went AND how that point was chosen. "at 500,600 -- widget
                            // 549:12 ..." is the difference between a click that follows the window
                            // and a number somebody measured once.
                            log.add("clicked play (CLICK HERE TO PLAY) at " + r.x() + "," + r.y()
                                    + " -- " + r.how());
                        }
                    }
                    emit(nowMs);    // the click's down, then its paced up; a no-op once drained
                }
                if (rawState == s.loginState()) {
                    playPending = false;
                    if (!s.reloginAfterDisconnect()) {
                        // A deliberate logout looks exactly like a disconnect from here; without the
                        // opt-in, do not type the password back in on our own.
                        log.add("back at the login screen -- idle until 'login now' (reloginAfterDisconnect is off)");
                        clearScript();
                        enter(Phase.IDLE, 0);
                        return phase;
                    }
                    log.add("back at the login screen -- re-armed (attempts and rejections reset)");
                    attempts = 0;
                    rejects = 0;
                    enter(Phase.WAITING_FOR_LOGIN_SCREEN, 0);
                    return step(nowMs, rawState);
                }
            }
            case IDLE -> {
                // The user logged in by hand (or a hotkey loginNow() already moved us on).
                if (rawState == STATE_LOGGED_IN) enter(Phase.LOGGED_IN, 0);
            }
            case BACKOFF -> {
                if (rawState == STATE_LOGGED_IN) {
                    // A slow server accepting the login we typed before the bounce was final -- but
                    // only while that submission could still be in flight. Review 2026-09-06: a
                    // back-off can last a minute, and a human who logs in by hand during one also
                    // arrives at 30; clicking "play" then posts a click into a world that is already
                    // loaded, at whatever happens to be under (centre - 3, top + 334).
                    boolean ours = scriptEnded && nowMs - scriptEndMs <= s.resultTimeoutMs();
                    enter(Phase.LOGGED_IN, 0);
                    if (ours) schedulePlay(nowMs);
                } else if (rawState == STATE_AUTHENTICATOR) {
                    enter(Phase.STOPPED_AUTHENTICATOR, 0);
                } else if (nowMs >= deadline && rawState == s.loginState()) {
                    log.add("retrying: attempt " + (attempts + 1) + "/" + s.maxAttempts());
                    enter(Phase.SETTLING, nowMs);
                }
            }
            case NO_CREDENTIALS -> {
                if (rawState == STATE_LOGGED_IN) {
                    enter(Phase.LOGGED_IN, 0);
                } else {
                    if (deadline == 0) deadline = nowMs + NO_CREDENTIALS_RETRY_MS;
                    if (nowMs >= deadline && rawState == s.loginState()) scriptWanted = true;
                }
            }
            case GAVE_UP, STOPPED_AUTHENTICATOR -> {
                if (rawState == STATE_LOGGED_IN) enter(Phase.LOGGED_IN, 0);
            }
        }
        return phase;
    }

    private String gaveUpReason = "";

    /**
     * The result timeout with the state still at the login value. Seen live 2026-09-06: that IS the
     * "Incorrect username or password" screen (the state never moves for a rejection on this build),
     * so it is counted as one and dismissed with its "Try again" button rather than typed over -- the
     * old script kept appending to the password field because nothing had cleared it.
     */
    private void rejectionAssumed() {
        attempts++;
        rejects++;
        clearScript();
        if (rejects >= s.maxRejects()) {
            log.add("no state change after Login click: assuming the rejection screen (rejection " + rejects
                    + "/" + s.maxRejects() + ") -- stopping");
            giveUpRejected();
            return;
        }
        if (attempts >= s.maxAttempts()) {
            gaveUpReason = "gave up after " + attempts + " attempts -- see KEWL_LOG";
            log.add(gaveUpReason);
            enter(Phase.GAVE_UP, 0);
            return;
        }
        if (canvasW <= 0 || canvasH <= 0) {
            gaveUpReason = "canvas size unknown -- cannot click Try again; check the input target";
            log.add(gaveUpReason);
            enter(Phase.GAVE_UP, 0);
            return;
        }
        log.add(REJECTION_ASSUMED + " (rejection " + rejects + "/" + s.maxRejects() + ")");
        script.add(new Mouse(canvasW / 2 + s.tryAgainX(), s.tryAgainY()));
        pauseUntil = 0;
        enter(Phase.DISMISSING_REJECTION, 0);
    }

    /** maxRejects reached, by either route: never hammer a wrong password, and name the Jagex Account trap. */
    private void giveUpRejected() {
        clearScript();
        gaveUpReason = "rejected " + rejects + " times -- check the credentials, and whether this is a Jagex "
                + "Account (the standalone client cannot log those in; the client's own message says to use "
                + "the Jagex Launcher)";
        log.add(gaveUpReason);
        enter(Phase.GAVE_UP, 0);
    }

    /** A non-final server bounce: back off with a doubling delay, or give up at maxAttempts. */
    private void failAttempt(long nowMs, String gaveUp) {
        clearScript();
        if (attempts >= s.maxAttempts()) {
            gaveUpReason = gaveUp + " -- see KEWL_LOG";
            log.add(gaveUpReason);
            enter(Phase.GAVE_UP, 0);
            return;
        }
        long delay = Math.max(1000, s.settleMs()) << Math.min(attempts - 1, 10);
        delay = Math.min(delay, MAX_BACKOFF_MS);
        log.add("backing off " + delay + " ms before attempt " + (attempts + 1) + "/" + s.maxAttempts());
        enter(Phase.BACKOFF, nowMs + delay);
    }

    /**
     * While settling/typing/awaiting: has the state moved off the title screen? Handles the
     * post-submit values; anything else stays put (it is logged once by {@link #noteState}).
     */
    private boolean leftTitle(long nowMs, int rawState) {
        if (rawState == s.loginState()) return false;
        if (rawState == STATE_LOGGING_IN || rawState == STATE_LOADING) {
            long after = scriptEnded ? nowMs - scriptEndMs : 0;
            log.add("submitted; state " + s.loginState() + " -> " + rawState + " after " + after
                    + " ms -- the typed login was accepted by the client, waiting for the server");
            clearScript();
            enter(Phase.SUBMITTED, 0);
            return true;
        }
        if (rawState == STATE_LOGGED_IN) {
            log.add("logged in (state " + rawState + ")");
            clearScript();
            enter(Phase.LOGGED_IN, 0);
            schedulePlay(nowMs);
            return true;
        }
        if (rawState == STATE_AUTHENTICATOR) {
            log.add("authenticator screen (state 11) -- stopped, a human is needed");
            clearScript();
            enter(Phase.STOPPED_AUTHENTICATOR, 0);
            return true;
        }
        return false;
    }

    /**
     * Just reached LOGGED_IN from a login this machine typed: arm the one "CLICK HERE TO PLAY"
     * click for {@code playDelayMs} later (seen live 2026-09-06: the welcome screen needs a moment
     * to draw after state 30 arrives). Without a canvas size there is no centre to offset from.
     */
    private void schedulePlay(long nowMs) {
        if (!s.clickPlay()) return;
        if (canvasW <= 0 || canvasH <= 0) {
            log.add("canvas size unknown: not clicking play");
            return;
        }
        playPending = true;
        playAt = nowMs + s.playDelayMs();
    }

    /** One emission slot, paced by keyDelayMs. A pending key-up always goes first. */
    private void emit(long nowMs) {
        if (nowMs < pauseUntil) return;
        if (nowMs - lastEmitMs < s.keyDelayMs()) return;
        if (pendingUp != null) {
            if (pendingUp instanceof Key k) sink.postKey(k.vk(), false);
            else if (pendingUp instanceof Mouse m) sink.postMouse(m.x(), m.y(), 2);
            pendingUp = null;
            lastEmitMs = nowMs;
            return;
        }
        Step st = script.poll();
        if (st == null) return;
        if (st instanceof Chr c) {
            sink.postChar(c.c());
            lastEmitMs = nowMs;
        } else if (st instanceof Key k) {
            sink.postKey(k.vk(), true);
            pendingUp = k;
            lastEmitMs = nowMs;
        } else if (st instanceof Mouse m) {
            sink.postMouse(m.x(), m.y(), 0);
            sink.postMouse(m.x(), m.y(), 1);
            pendingUp = m;
            lastEmitMs = nowMs;
        } else if (st instanceof Pause p) {
            pauseUntil = nowMs + p.ms();
        }
    }

    private void noteState(int rawState) {
        if (rawState == STATE_LOGGED_IN) everLoggedIn = true;
        if (rawState != lastRawState) {
            if (lastRawState != Integer.MIN_VALUE) log.add("state " + lastRawState + " -> " + rawState + " (raw)");
            // Did we fall out of the world onto the title screen? That is a disconnect (or a logout),
            // and the client draws the FORM for one, not the welcome box -- live 2026-09-06. State 20
            // is deliberately NOT in this list: 20 -> 10 is a server bounce on a login WE submitted,
            // and nobody has yet seen which screen follows one, so that keeps today's behaviour.
            if (rawState == s.loginState()) {
                if (lastRawState == STATE_LOADING || lastRawState == STATE_LOGGED_IN
                        || HOP_OR_LOADING_STATES.contains(lastRawState)) {
                    arrivedFromWorld = true;
                }
            } else if (rawState != 0 && lastRawState == s.loginState()) {
                // Leaving the title screen for anywhere: the next arrival is judged on its own.
                arrivedFromWorld = false;
            }
            lastRawState = rawState;
        }
        boolean expected = rawState == 0 || rawState == s.loginState() || rawState == STATE_LOGGING_IN
                || rawState == STATE_LOADING || rawState == STATE_LOGGED_IN || rawState == STATE_AUTHENTICATOR
                || HOP_OR_LOADING_STATES.contains(rawState);
        // The advice is only ever right for a state seen BEFORE this client has been in-game: once 30
        // has happened the login state is settled, and everything else is a hop or a loading screen.
        // Review 2026-09-06: telling a hopping user to set loginState to 45 would make the sequence
        // treat every later hop as the title screen and type the password into the game.
        if (!expected && !everLoggedIn && oddStatesLogged.add(rawState)) {
            log.add("raw state " + rawState + " is not the configured login state " + s.loginState()
                    + " -- if this is the login screen set the loginState setting to " + rawState);
        }
    }

    private void enter(Phase p, long deadlineMs) {
        phase = p;
        deadline = deadlineMs;
        scriptWanted = false;
        if (p == Phase.GAVE_UP || p == Phase.STOPPED_AUTHENTICATOR || p == Phase.LOGGED_IN
                || p == Phase.WAITING_FOR_LOGIN_SCREEN) {
            clearScript();
            skipWelcomeClick = false;   // a fresh start begins on the welcome box again
        }
        if (p == Phase.LOGGED_IN) {
            // Not ours unless schedulePlay() follows: every other route to 30 is a manual login.
            playPending = false;
            playClicked = false;
            playHow = "";
        }
    }

    private void clearScript() {
        script.clear();
        pendingUp = null;
    }

    /** Log lines since the last call, oldest first. Counts and states only -- never a character. */
    public List<String> drainLog() {
        List<String> out = new ArrayList<>(log);
        log.clear();
        return out;
    }

    /** One line for the panel's status column. */
    public String status() {
        switch (phase) {
            case WAITING_FOR_LOGIN_SCREEN:
                return "waiting for login screen (raw state " + lastRawState + ", want " + s.loginState() + ")";
            case SETTLING:
                return scriptWanted ? "reading credentials"
                        : String.format("settling %.1fs", Math.max(0, deadline - lastNow()) / 1000.0);
            case RUNNING:
                // No k/N: the slot total is the password length plus a constant.
                return (fieldsSetDirectly ? "fields set directly; clicking Login" : "typing...")
                        + " (attempt " + (attempts + 1) + "/" + s.maxAttempts() + ", " + armedScreen + ")";
            case AWAITING_RESULT:
                return "Login clicked, waiting for the state to leave " + s.loginState() + " (attempt "
                        + (attempts + 1) + "/" + s.maxAttempts() + ")";
            case DISMISSING_REJECTION:
                return "rejected (" + rejects + "/" + s.maxRejects() + ") -- clicking Try again";
            case SUBMITTED:
                return "submitted, waiting (state " + lastRawState + ")";
            case LOGGED_IN:
                // Which path the click took belongs in front of the user: a status that says
                // "widget 549:12 ..." is the difference between "this follows the window" and "this
                // is a number somebody measured once".
                return playClicked ? "logged in -- clicked play (" + playHow + ")" : "logged in";
            case BACKOFF:
                return "attempt " + (attempts + 1) + "/" + s.maxAttempts() + " in "
                        + Math.max(0, (deadline - lastNow() + 999) / 1000) + "s";
            case GAVE_UP:
                return gaveUpReason.isEmpty() ? "gave up -- see KEWL_LOG" : gaveUpReason;
            case STOPPED_AUTHENTICATOR:
                return "authenticator screen -- stopped";
            case NO_CREDENTIALS:
                return noCredentialsReason;
            case IDLE:
                return "logged out -- idle; hotkey or panel toggle to log in again";
        }
        return phase.name();
    }

    // The status line wants "seconds left" without a clock of its own; the last step() time is
    // close enough, since step() runs every frame.
    private long lastStepMs;

    private long lastNow() { return lastStepMs; }

    /** Same as {@link #status()} -- a sequence never prints anything else. */
    @Override
    public String toString() { return status(); }
}

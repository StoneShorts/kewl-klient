package kewl.plugins.autologin;

import java.util.Arrays;
import java.util.Map;

/**
 * Which of the title screen's several faces is up right now -- and therefore which script fits it.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Seen live 2026-09-06: the client does NOT always show the same thing at raw game state 10. From
 * a cold start it draws a welcome box (New User / Existing User) and the form only appears after the
 * Existing User click. After a DISCONNECT it draws the form straight away, username pre-filled, with
 * the message "Please enter your password" -- no welcome box at all. The old script assumed the cold
 * start unconditionally: it clicked where Existing User would have been (on that screen it is not a
 * button), typed into whatever had focus, and clicked Login on an empty password field, twice, then
 * stopped with "rejected 2 times". The screen, not the state, is what the script has to branch on.</p>
 *
 * <h2>How the screen is decided</h2>
 *
 * <p>Two sources, in this order:</p>
 * <ol>
 *   <li><b>The fingerprint</b> -- {@code Natives.loadedGroups()}, the interface group ids whose
 *       component data is loaded right now. Positions from a single packed widget id are NOT
 *       trustworthy on this build (client/offsets.hpp records that a widget's stored x/y are taken as
 *       canvas coordinates with no parent-offset accumulation), but "which groups are loaded" is a
 *       plain list the client hands over, and it changes with the screen. {@link #KNOWN} maps a
 *       fingerprint to a screen. It ships <b>EMPTY</b>: no id has been confirmed against a real
 *       screen yet, and inventing one would be worse than admitting ignorance. The sequence logs each
 *       distinct fingerprint once under {@code KEWL_LOG} together with what it believed the screen
 *       was, so the next live run is exactly the evidence needed to fill this map in.</li>
 *   <li><b>How we got here</b> -- the fallback whenever the fingerprint is unknown, which is every
 *       fingerprint today. See {@code LoginSequence.provenance()}: the login screen reached from the
 *       in-world state is a {@link #DISCONNECT}; the one reached after a "Try again" click is a
 *       {@link #FORM}; anything else -- a cold start above all -- is {@link #WELCOME}, which is
 *       precisely today's behaviour.</li>
 * </ol>
 *
 * <p>The screens differ in exactly two things, which is why this is an enum and not a class: whether
 * the Existing User click happens ({@link #clicksExistingUser()}) and whether the password field has
 * to be clicked before typing ({@link #focusesPasswordField()}).</p>
 */
public enum LoginScreen {

    /**
     * The welcome box with New User / Existing User, before the form. What a cold start shows
     * (live 2026-09-05 and 2026-09-06). The Existing User click opens the form and the caret lands in
     * the password field by itself when the client remembers the username, so nothing else is needed.
     */
    WELCOME,

    /**
     * The login form itself, with no welcome box in front of it: fields, Login and Cancel. Where the
     * client sits after a "Try again" on the rejection screen. Nothing here says the caret is in the
     * password field, so the script puts it there.
     */
    FORM,

    /**
     * The form as it comes up after a disconnect: username pre-filled, "Please enter your password"
     * (live 2026-09-06). Same script as {@link #FORM}; kept apart because that message means the
     * password field is empty, which is what makes the direct field write worth trying at all, and
     * because the two want separate fingerprints in {@link #KNOWN}.
     */
    DISCONNECT,

    /**
     * "Incorrect username or password", with its Try again button. Reached by the result timeout, not
     * by a state change -- the raw state never moves for a rejection on this build. The sequence
     * dismisses this one rather than typing over it; if it is somehow still up at arming time the
     * script at least does not click Existing User into it.
     */
    REJECTION,

    /** No fingerprint matched. The caller falls back to how it got here; never used to build a script. */
    UNKNOWN;

    /**
     * Only the welcome box has an Existing User button. This is the whole of the reported bug: on
     * every other screen that spot is either nothing or, on the form, next to Cancel.
     */
    public boolean clicksExistingUser() { return this == WELCOME; }

    /**
     * Whether the script must click the password field before typing into it. The welcome box hands
     * the caret over itself when it opens the form; on a screen that was already showing when we
     * arrived, nothing has told us where the focus is, so it is put where we want it.
     *
     * <p>Only consulted on the "username remembered" path -- the other one already clicks the
     * username field and then tabs or clicks its way to the password.</p>
     */
    public boolean focusesPasswordField() { return this == FORM || this == DISCONNECT || this == REJECTION; }

    /**
     * The loaded interface groups as one short, order-independent, comparable string. Safe to log: it
     * is a list of small integers out of the client, with nothing of ours in it.
     *
     * <p>An empty array (nothing loaded, or the client object is not up) is {@code ""}, which is
     * itself a fingerprint -- the title screen may well load no interface groups at all, and "none"
     * would then be as good a discriminator as any. Anything longer than {@link #MAX_GROUPS} entries
     * is summarised as a count instead: in-game the client reports around 970 groups and neither the
     * log nor the map wants that, and a screen with that many groups loaded is not the title screen.</p>
     */
    public static String fingerprint(int[] groups) {
        if (groups == null || groups.length == 0) return "";
        int[] sorted = groups.clone();
        Arrays.sort(sorted);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i > 0 && sorted[i] == sorted[i - 1]) continue;      // dedupe
            if (++n > MAX_GROUPS) return "many:" + count(sorted);
            if (sb.length() > 0) sb.append(',');
            sb.append(sorted[i]);
        }
        return sb.toString();
    }

    /** Past this many distinct groups the fingerprint is a count: that is the loaded game, not the title screen. */
    public static final int MAX_GROUPS = 40;

    private static int count(int[] sorted) {
        int n = 0;
        for (int i = 0; i < sorted.length; i++) if (i == 0 || sorted[i] != sorted[i - 1]) n++;
        return n;
    }

    /**
     * The fingerprint-to-screen table, <b>deliberately empty</b>.
     *
     * <p>Nothing here has been confirmed against a real screen, and a wrong entry is worse than none:
     * it would make the script skip the Existing User click on the cold start that works today. Run
     * the client once with {@code KEWL_LOG} set, cold-start it, log out, disconnect, and fail a login
     * on purpose; the log then carries one
     * "{@code login screen fingerprint <ids> -- ...}" line per distinct screen, and the ids in those
     * lines are what belongs here:</p>
     *
     * <pre>
     *   Map.of("378,invalid-example", WELCOME,
     *          "...",                 DISCONNECT);
     * </pre>
     */
    public static final Map<String, LoginScreen> KNOWN = Map.of();

    /** The screen this fingerprint is known to be, or {@link #UNKNOWN}. A null book is an empty one. */
    public static LoginScreen ofFingerprint(String fingerprint, Map<String, LoginScreen> book) {
        if (fingerprint == null || book == null) return UNKNOWN;
        LoginScreen s = book.get(fingerprint);
        return s == null ? UNKNOWN : s;
    }
}

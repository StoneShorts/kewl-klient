package kewl.plugins.autologin;

import kewl.Natives;

/**
 * Finds where the client keeps the login form's username and password, so the plugin can SET the
 * fields instead of typing into them.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Typing works, but it depends on the form being in the state the script assumes: the caret in
 * the right field, the right screen showing, the click offsets matching the window size. A
 * disconnect puts up a different screen than a cold start, and the script then typed a password into
 * nothing and clicked Login on an empty field (seen live 2026-09-06: "Please enter your password").
 * Writing the two strings the client itself renders is immune to all of that.</p>
 *
 * <h2>How it finds them, with no decompiler</h2>
 *
 * <p>The username is a value we already know, and the client is holding it in memory right now --
 * it is drawn in the Login field. {@link Natives#findString} returns the addresses whose bytes equal
 * it; the one that is the form's field is an {@code NxtString} (the client's small-string-optimised
 * string: up to 23 bytes inline, with {@code 0x17 - length} in the flag byte at {@code +0x17}), and
 * the password is the neighbouring one on the same object. The probe prints ADDRESSES and VERDICTS
 * only -- never a byte, never a length, not even the NxtString flag byte, which is a length written
 * in one byte. Review 2026-09-06: it used to print the 16 bytes before each hit and the decoded
 * length of every neighbouring inline string, and the neighbour it exists to find IS the password
 * field, so that line published the password's length by design. Classify, never dump.</p>
 *
 * <p>Read-only. Nothing here writes to the game.</p>
 */
public final class FieldProbe {

    private FieldProbe() {}

    /**
     * Entry point for {@code kewl.plugins.AutoLogin}, which lives in the parent package and must not
     * see the credential strings: it hands over the whole {@link Credentials} and the username never
     * crosses a package boundary as a value.
     */
    public static void run(Credentials c) {
        if (c != null && c.usernameSet()) run(c.username, c.password);
    }

    private static int widgetDumps;

    /**
     * Log every interface component that carries text, at most twice a session: once is the welcome
     * screen (where "CLICK HERE TO PLAY" lives) and once is whatever is up the next time. This is the
     * evidence for clicking that button by its widget id instead of by a measured pixel offset.
     */
    public static void dumpWidgets(String why) {
        if (widgetDumps >= 2) return;
        widgetDumps++;
        String dump;
        try {
            dump = Natives.dumpWidgetText(400);
        } catch (UnsatisfiedLinkError e) {
            System.out.println("[widgetprobe] dumpWidgetText is not in this DLL -- rebuild it");
            return;
        }
        if (dump == null || dump.isEmpty()) {
            System.out.println("[widgetprobe] " + why + ": no interface component carries text");
            return;
        }
        // Where do components really keep their rectangle? The canvas size is a value we know, and a
        // top-level interface matches it, so the offsets that come back are where the rectangle lives.
        //
        // viewport() is {x, y, width, height} and this used to pass vp[0], vp[1] -- the canvas's SCREEN
        // TOP-LEFT -- as if it were the canvas SIZE. Every "rectangle offsets for canvas NxM" line this
        // probe has ever printed therefore searched for the wrong two numbers and could only ever
        // report noise (Perspective.localToMinimap gets this right: it reads the width from vp[2]).
        try {
            int[] vp = Natives.viewport();
            if (vp != null && vp.length >= 4 && vp[2] > 0 && vp[3] > 0) {
                String rect = Natives.findWidgetRect(vp[2], vp[3]);
                System.out.println("[widgetprobe] rectangle offsets for canvas " + vp[2] + "x" + vp[3] + ":");
                for (String l : rect.split("\n")) if (!l.isEmpty()) System.out.println("[widgetprobe]   " + l);
            }
        } catch (UnsatisfiedLinkError e) {
            System.out.println("[widgetprobe] findWidgetRect is not in this DLL -- rebuild it");
        }
        System.out.println("[widgetprobe] " + why + ":");
        for (String line : dump.split("\n")) {
            if (!line.isEmpty()) System.out.println("[widgetprobe]   " + line);
        }
    }

    /** An NxtString's flag byte sits here; inline strings store {@code 0x17 - length} in it. */
    private static final int NXT_FLAG = 0x17;

    /**
     * What a stretch of memory looks LIKE -- never what it holds. The bytes next to a credential are
     * as good as the credential (the pair found live on 2026-09-06 had {@code ","password":"} and the
     * value itself sitting immediately after the username), so nothing in this file hands a hex line
     * to a print statement; the reader gets one of these four words instead, which is all that is
     * needed to tell a fixed-buffer struct from a JSON document.
     */
    /**
     * The verdict on one neighbouring slot, from its NxtString flag byte and the length we are
     * hunting for. Pure and package-private so {@code FieldProbeTest} can hold every possible flag
     * byte against every plausible password length and assert the answer never varies with, or
     * discloses, either number: an inline string's flag byte IS {@code 0x17 - length}, so printing
     * it -- or the length back out of it -- publishes the length of the very field this method exists
     * to locate.
     *
     * @param flagByte the byte at {@code +0x17} of the candidate slot, 0..255
     * @param wantedLength the length being looked for, never printed and never reflected in the text
     *                     beyond a yes/no
     */
    static String neighbour(int flagByte, int wantedLength) {
        if (flagByte > 0x17) return "(heap or not a string)";
        boolean matches = wantedLength > 0 && 0x17 - flagByte == wantedLength;
        return matches
                ? "inline, AND ITS LENGTH IS THE ONE WE WANT -- this is the password field"
                : "inline, but a different length";
    }

    private static String shape(long at, int len) {
        String hex = Natives.peek(at, len);
        if (hex.isEmpty()) return "unreadable";
        if (FieldWriter.allZero(hex)) return "zeroes";
        return FieldWriter.gapIsText(FieldWriter.NATIVE, at, len)
                ? "printable text (a document)" : "binary (a struct)";
    }

    private static boolean ran;


    /**
     * Log where {@code username} lives, once per session. Prints addresses and the shape of the
     * object around each, never the value.
     */
    private static void run(String username, String password) {
        if (ran || username == null || username.length() < 3) return;
        ran = true;
        final long[] hits;
        final long[] pwHits;
        try {
            hits = Natives.findString(username);
            // The password's addresses, never its bytes. Two fields of one object sit a few dozen
            // bytes apart, so a username hit and a password hit with a small delta IS the login form
            // -- that pairing is what identifies the struct, and no single-field scan could.
            pwHits = (password != null && password.length() >= 3) ? Natives.findString(password) : new long[0];
        } catch (UnsatisfiedLinkError e) {
            System.out.println("[fieldprobe] findString is not in this DLL -- rebuild it");
            return;
        }
        System.out.println("[fieldprobe] " + (pwHits == null ? 0 : pwHits.length)
                + " address(es) hold the password");
        // Used only as an ARITHMETIC offset when walking past a password hit to the padding behind
        // it. It is never printed, never compared into a message, and never reaches a format string:
        // a password's length is a fact about the password (see the class comment).
        final int passLen = password == null ? 0 : password.length();
        if (hits != null && pwHits != null) {
            for (long u : hits) {
                for (long p : pwHits) {
                    long d = p - u;
                    if (d > -4096 && d < 4096) {
                        // Is the gap printable text or binary padding? Text means the pair is a
                        // document -- our own profile JSON in the JVM heap -- and binary padding means
                        // a struct with fixed buffers, which is what the client's form is.
                        //
                        // CLASSIFY, never print. Dumping those bytes leaked the first characters of
                        // the password on 2026-09-06: the JSON pair's gap is literally
                        // `","password":"` followed by the value. The verdict is all a reader needs.
                        // One implementation of that rule, shared with FieldWriter, which discards a
                        // text-gapped pair rather than merely reporting it.
                        // The gap starts at the END of whichever hit comes first -- with
                        // Math.min(u, p) + username.length() a password longer than the username put
                        // `from` back inside the password's own bytes, so the classification was of
                        // the secret rather than of the padding after it.
                        long from = d > 0 ? u + username.length() : p + passLen;
                        int len = (int) Math.min(24, Math.max(0,
                                Math.abs(d) - (d > 0 ? username.length() : passLen)));
                        if (len <= 0) continue;
                        boolean readable = !Natives.peek(from, len).isEmpty();
                        boolean text = readable && FieldWriter.gapIsText(FieldWriter.NATIVE, from, len);
                        System.out.println(String.format(
                                "[fieldprobe] PAIR user@%016x pass@%016x delta=%+d gap=%s",
                                u, p, d, !readable ? "unreadable"
                                        : (text ? "TEXT (a document, not the client's form)"
                                                : "binary padding (a struct -- candidate)")));
                    }
                }
            }
        }
        if (hits == null || hits.length == 0) {
            System.out.println("[fieldprobe] the username is not in memory as plain bytes right now "
                    + "(wrong screen, or the client stores it wide/encoded)");
            return;
        }
        System.out.println("[fieldprobe] " + hits.length + " address(es) hold the username");
        for (int i = 0; i < hits.length && i < 12; i++) {
            long a = hits[i];
            // CLASSIFY, NEVER DUMP -- and a length is a dump in one byte. An inline NxtString keeps
            // 0x17 - length in the flag byte at +0x17, so printing that byte, or the length derived
            // from it, publishes the length of whatever string sits there. For the hit itself that is
            // the username; for the NEIGHBOURS below it is, by construction, the field we are hunting
            // for -- the password. Both are reported as verdicts instead: "is it shaped like an inline
            // string", and "does its length match the one we are looking for", computed in here
            // against a value we already hold and never written out as a number. The 16 bytes before
            // the hit went out as raw hex until review 2026-09-06; a hit inside a document has the
            // neighbouring field's bytes right there, which is the exact shape of the leak this file
            // already carries a warning about.
            String flag = Natives.peek(a + NXT_FLAG, 1);
            boolean inlineShape = flag.equals(String.format("%02x", 0x17 - username.length()));
            System.out.println(String.format(
                    "[fieldprobe]   %016x %s before=%s",
                    a, inlineShape ? "INLINE-NXTSTRING" : "not an inline NxtString of this length",
                    shape(a - 16, 16)));
            if (inlineShape) {
                // The password field is the next NxtString on the same object. NxtString is 24 bytes,
                // so look at the two slots either side and report which of them look like strings --
                // and which one is the length we are after, without ever saying what that length is.
                for (int d : new int[] { -48, -24, 24, 48 }) {
                    String f2 = Natives.peek(a + d + NXT_FLAG, 1);
                    if (f2.isEmpty()) continue;
                    System.out.println(String.format("[fieldprobe]     neighbour %+d: %s", d,
                            neighbour(Integer.parseInt(f2, 16), passLen)));
                }
            }
        }
        System.out.println("[fieldprobe] the neighbour marked 'the one we want' above is the password "
                + "field; its offset from the username field is what goes in offsets.hpp");
    }
}

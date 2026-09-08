package kewl.plugins.autologin;

import java.util.ArrayList;
import java.util.List;

import kewl.Natives;

/**
 * The opt-in direct field write: put the username and the password into the client's own buffers
 * instead of typing them at the form.
 *
 * <h2>What it does, and why it is off by default</h2>
 *
 * <p>Typing works only when the form is in the state the script assumes -- the right screen up, the
 * caret in the right field, the click offsets matching the window. Writing the two strings the client
 * itself renders is immune to all of that. It is also a write into another process's memory at an
 * address we derived by searching for a string, and a wrong address is a corrupted client or, worse,
 * a corrupted JVM heap. So the setting ships OFF, every gate below has to pass, and a single doubt
 * anywhere falls back to typing and says so.</p>
 *
 * <h2>The gates</h2>
 * <ol>
 *   <li><b>The pair, not the hit.</b> {@link Memory#find} returns every address holding the username.
 *       Live 2026-09-06 there were four, and one pair of them was our OWN profile config.json sitting
 *       in the JVM heap -- username and password a short distance apart with {@code ","password":"}
 *       between them. A username hit that pairs with a password hit across PRINTABLE TEXT is a
 *       document and is discarded. Binary padding between a pair is a struct with fixed buffers,
 *       which is what the client's form is.</li>
 *   <li><b>The password buffer must be empty.</b> The candidate password address is
 *       {@code username + }{@link #PASSWORD_DELTA}, and the 24 bytes there must be all zero -- the
 *       "Please enter your password" state this whole feature exists for. The bytes are CLASSIFIED,
 *       never printed: dumping them is what leaked the first characters of a password on
 *       2026-09-06.</li>
 *   <li><b>Exactly one candidate.</b> Two addresses that both look right means the delta is matching
 *       something by chance; nothing is written.</li>
 *   <li><b>The native's own gates.</b> {@link Natives#setLoginField} refuses unless the target is
 *       readable AND writable and already holds either all zeroes or exactly the value being written,
 *       and it never writes past the existing content plus its zero run, nor past the cap. See the
 *       {@code nSetLoginField} comment in {@code client/jvm.hpp}.</li>
 * </ol>
 *
 * <p>Nothing in this class puts a credential in a string. Every {@link Result#status()} is built from
 * counts and refusal words; {@code FieldWriterTest} asserts that across every path.</p>
 */
public final class FieldWriter {

    private FieldWriter() {}

    /**
     * The three natives this needs, behind an interface so the decision logic above can be tested
     * against a fabricated memory layout with no game running. Same seam as {@link InputSink}.
     */
    public interface Memory {
        /** Addresses whose bytes equal {@code needle}; never the bytes. */
        long[] find(String needle);

        /** {@code len} bytes at {@code at} as hex, "" when unreadable. Callers CLASSIFY this, never print it. */
        String peek(long at, int len);

        /** {@link Natives#setLoginField}: 0 on success, one of the {@code REFUSED_*} codes otherwise. */
        int write(long at, String value, int cap);
    }

    /** The real thing. Throws {@code UnsatisfiedLinkError} against a DLL built before setLoginField. */
    public static final Memory NATIVE = new Memory() {
        @Override public long[] find(String needle) { return Natives.findString(needle); }
        @Override public String peek(long at, int len) { return Natives.peek(at, len); }
        @Override public int write(long at, String value, int cap) { return Natives.setLoginField(at, value, cap); }
    };

    /**
     * Distance from the username buffer to the password buffer, mirrored from
     * {@code LOGIN_PASSWORD_DELTA} in {@code client/offsets.hpp} -- move both together.
     *
     * <p><b>NOT VERIFIED.</b> Derived from one live FieldProbe run on 2026-09-06: of the four
     * addresses holding the username, one paired with a password hit at {@code +508} with binary
     * padding between them (a struct with fixed buffers), while another pair was our own profile JSON
     * in the JVM heap with printable text between. Nothing has yet confirmed that writing there
     * changes what the form displays.</p>
     */
    public static final int PASSWORD_DELTA = 508;

    /** The most bytes either buffer is allowed to hold, terminator included. */
    public static final int CAP = 64;

    /** How many bytes of the candidate password buffer must be zero before anything is written. */
    static final int EMPTY_PROBE_BYTES = 24;

    /** How far apart two hits may be and still be considered a pair (the FieldProbe window). */
    static final int PAIR_WINDOW = 4096;

    // -- the native's refusal codes; mirrored from nSetLoginField in client/jvm.hpp.
    public static final int OK = 0;
    public static final int REFUSED_ARGUMENT = -1;
    public static final int REFUSED_UNREADABLE = -2;
    public static final int REFUSED_UNWRITABLE = -3;
    public static final int REFUSED_CONTENT = -4;
    public static final int REFUSED_NO_ROOM = -5;
    public static final int REFUSED_NXTSTRING = -6;

    /**
     * What happened. {@code wrote} true means BOTH fields are in the client and the script should not
     * type; {@code status} is one short line for the log and the panel, containing counts and reasons
     * and never a value.
     */
    public record Result(boolean wrote, String status) {}

    /**
     * Try to set both fields. The whole {@link Credentials} is taken rather than two strings for the
     * same reason {@code FieldProbe.run} takes it: the values never cross a package boundary.
     */
    public static Result write(Memory m, Credentials c) {
        if (c == null || !c.usernameSet() || !c.passwordSet()) {
            return new Result(false, "direct write: no credentials to place -- typing instead");
        }
        return write(m, c.username, c.password);
    }

    static Result write(Memory m, String user, String pass) {
        if (user.length() < 3) {
            return new Result(false, "direct write: the username is too short to search for -- typing instead");
        }
        if (user.length() >= CAP || pass.length() >= CAP) {
            return new Result(false, "direct write: a field is longer than the buffer -- typing instead");
        }
        final long[] hits;
        final long[] pwHits;
        try {
            hits = m.find(user);
            // The password's ADDRESSES, never its bytes: a username hit and a password hit a short
            // distance apart is what identifies a pair at all, and the bytes between them are what
            // tells a struct from a document.
            pwHits = pass.length() >= 3 ? m.find(pass) : new long[0];
        } catch (UnsatisfiedLinkError e) {
            return new Result(false, "direct write: this DLL has no findString -- rebuild it; typing instead");
        }
        if (hits == null || hits.length == 0) {
            return new Result(false, "direct write: the client is not holding the username as plain bytes "
                    + "right now -- typing instead");
        }

        List<Long> candidates = new ArrayList<>();
        int documents = 0, occupied = 0, unreadable = 0;
        for (long u : hits) {
            if (pairsAcrossText(m, u, pwHits, user.length(), pass.length())) { documents++; continue; }
            String hex = m.peek(u + PASSWORD_DELTA, EMPTY_PROBE_BYTES);
            if (hex == null || hex.isEmpty()) { unreadable++; continue; }
            if (!allZero(hex)) { occupied++; continue; }
            candidates.add(u);
        }
        if (candidates.size() != 1) {
            return new Result(false, "direct write: " + candidates.size() + " candidate field pair(s) among "
                    + hits.length + " username hits (" + documents + " ruled out as documents, " + occupied
                    + " with a non-empty buffer, " + unreadable + " unreadable) -- typing instead");
        }

        long u = candidates.get(0);
        // The username first, on purpose. It is a no-op -- the buffer already holds exactly this, which
        // is how it was found -- so it costs nothing and it proves the address is writable and shaped
        // the way the native demands BEFORE the password goes anywhere.
        int a;
        try {
            a = m.write(u, user, CAP);
        } catch (UnsatisfiedLinkError e) {
            return new Result(false, "direct write: this DLL has no setLoginField -- rebuild it; typing instead");
        }
        if (a != OK) {
            return new Result(false, "direct write: the username buffer refused the write ("
                    + refusal(a) + ") -- typing instead");
        }
        int b = m.write(u + PASSWORD_DELTA, pass, CAP);
        if (b != OK) {
            return new Result(false, "direct write: the password buffer refused the write ("
                    + refusal(b) + ") -- typing instead");
        }
        return new Result(true, "fields set directly (NOT VERIFIED -- watch that the form actually shows them)");
    }

    /**
     * Does this username hit pair with a password hit across PRINTABLE TEXT? That pair is a document
     * -- our own profile config.json in the JVM heap, where the gap is literally
     * {@code ","password":"} -- and never the client's form, whose fixed buffers are separated by
     * binary padding.
     */
    private static boolean pairsAcrossText(Memory m, long u, long[] pwHits, int userLen, int passLen) {
        if (pwHits == null) return false;
        for (long p : pwHits) {
            long d = p - u;
            if (d <= -PAIR_WINDOW || d >= PAIR_WINDOW) continue;
            long from = d > 0 ? u + userLen : p + passLen;
            int len = (int) Math.min(EMPTY_PROBE_BYTES, Math.max(0, Math.abs(d) - (d > 0 ? userLen : passLen)));
            if (len <= 0) continue;
            if (gapIsText(m, from, len)) return true;
        }
        return false;
    }

    /**
     * Are the {@code len} bytes at {@code from} all printable (or zero)? The verdict is the only thing
     * a caller may report -- CLASSIFY, never dump. Printing these bytes is what leaked the leading
     * characters of a password on 2026-09-06, the gap in that pair being {@code ","password":"}
     * followed by the value.
     */
    static boolean gapIsText(Memory m, long from, int len) {
        String hex = m.peek(from, len);
        if (hex == null || hex.isEmpty()) return false;
        for (int k = 0; k + 1 < hex.length(); k += 2) {
            int b;
            try {
                b = Integer.parseInt(hex.substring(k, k + 2), 16);
            } catch (NumberFormatException e) {
                return false;
            }
            if (b != 0 && (b < 0x20 || b > 0x7E)) return false;
        }
        return true;
    }

    /** Every byte of this hex line zero. The line itself never leaves this class. */
    static boolean allZero(String hex) {
        for (int i = 0; i < hex.length(); i++) if (hex.charAt(i) != '0') return false;
        return !hex.isEmpty();
    }

    /** The native's refusal as a word. Never a value, never a length. */
    static String refusal(int code) {
        switch (code) {
            case OK: return "written";
            case REFUSED_ARGUMENT: return "bad argument";
            case REFUSED_UNREADABLE: return "not readable";
            case REFUSED_UNWRITABLE: return "not writable";
            case REFUSED_CONTENT: return "holds something we did not expect";
            case REFUSED_NO_ROOM: return "no room in the buffer";
            case REFUSED_NXTSTRING: return "an inline NxtString, whose length byte this will not guess";
            default: return "code " + code;
        }
    }
}

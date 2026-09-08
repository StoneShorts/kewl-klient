package kewl.api;

import java.util.ArrayList;
import java.util.List;

import kewl.Natives;

/**
 * Interface components by what they COVER, so a button can be clicked at its own centre instead of
 * at a pixel offset somebody measured once on one window size.
 *
 * <p><b>Why this exists.</b> Autologin's "CLICK HERE TO PLAY" click was a measured offset
 * ({@code centre - 3, top + 334} on a 1314x900 canvas, live 2026-09-06). That works until the window
 * changes size. The button is a SPRITE, so it carries no text and cannot be found by label -- but its
 * component still has a rectangle, and a rectangle that contains the point we already know works
 * identifies it. Resolve once from the known-good coordinate, keep the id, and from then on click the
 * middle of whatever that component currently measures.</p>
 *
 * <h2>What is trustworthy here, and what is not</h2>
 * <p>Verified live 2026-09-06: the component pointer is the SECOND half of each 16-byte shared_ptr
 * entry, and with that fixed the dump returns real data (e.g. {@code "90:37 3,17 134x30 shown When
 * the timer hits 0:00 YOU'LL DIE"}). A component's SIZE is trustworthy. Its POSITION is NOT settled:
 * whether the stored x/y are canvas-absolute or relative to the parent is unverified on this build,
 * and there is no parent link to accumulate through from here. So every position-based answer in this
 * class is a CANDIDATE that a caller must cross-check against something it already knows -- which is
 * exactly what {@link #smallestContaining} is shaped for: you hand it a point that is known to work,
 * and it hands back the component that covers it. If the stored positions turn out to be
 * parent-relative, no component contains the point, the search comes back empty, and the caller keeps
 * its coordinate. It never silently clicks somewhere else.</p>
 *
 * <p><b>Not for the per-frame path.</b> {@link #loaded} walks the whole interface tree in the DLL.
 * Call it from a resolve-once, from a probe, or from an explicit action -- never every frame. (It is
 * not a memory probe in the {@code FieldProbe} sense: it goes through the same guarded reader every
 * native uses, and it is read-only.)</p>
 */
public final class Widgets {

    private Widgets() {}

    /** dumpWidgetText's own walk is bounded; this is what we ask for, and it fits a busy screen. */
    public static final int DEFAULT_MAX = 600;

    /**
     * One interface component and its rectangle, as the client stores them.
     *
     * @param id     the client's own {@code (group << 16) | component}
     * @param x      as stored -- see the class comment; may be parent-relative
     * @param text   the component's text, or "" for a sprite (the dump writes "-" for those)
     */
    public record Component(int id, int x, int y, int width, int height, boolean hidden, String text) {

        public int group() { return (id >>> 16) & 0xFFFF; }

        public int component() { return id & 0xFFFF; }

        public int centreX() { return x + width / 2; }

        public int centreY() { return y + height / 2; }

        public long area() { return (long) width * (long) height; }

        /** Half-open, like every other rectangle test: the right and bottom edges are outside. */
        public boolean contains(int px, int py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }

        /** {@code "549:12 3,17 134x30 shown"} -- the id first, because the id is the thing to keep. */
        public String describe() {
            return group() + ":" + component() + " " + x + "," + y + " " + width + "x" + height
                    + (hidden ? " hidden" : " shown") + (text.isEmpty() ? "" : " \"" + text + "\"");
        }
    }

    /**
     * Every loaded component that has a real rectangle, from {@code Natives.dumpWidgetText}. Empty
     * when nothing is loaded, when the client object is not up, or when the DLL predates the native.
     */
    public static List<Component> loaded(int max) {
        String dump;
        try {
            dump = Natives.dumpWidgetText(max);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return List.of();
        }
        return parse(dump);
    }

    /** One component by its packed id, or null when its group is not loaded right now. */
    public static Component byId(int id) {
        final int[] v;
        final String text;
        try {
            v = Natives.widget(id);
            if (v == null || v.length < 6 || v[0] == 0) return null;
            text = Natives.widgetText(id);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return null;
        }
        return new Component(id, v[1], v[2], v[3], v[4], v[5] != 0, text == null ? "" : text);
    }

    // ---------------------------------------------------------------------------------------------
    // Pure: parsing and picking. No natives below this line, so the tests can drive all of it.
    // ---------------------------------------------------------------------------------------------

    /** Every parseable line of a {@code dumpWidgetText} block, garbage lines dropped. */
    public static List<Component> parse(String dump) {
        List<Component> out = new ArrayList<>();
        if (dump == null || dump.isEmpty()) return out;
        for (String line : dump.split("\n")) {
            Component c = parseLine(line);
            if (c != null) out.add(c);
        }
        return out;
    }

    /**
     * One line of {@code dumpWidgetText}: {@code "group:component x,y wxh shown|hidden text"}, where
     * the text is "-" for a component that carries none (a sprite -- the play button is one). Returns
     * null for anything that does not parse, because this reads a DLL's output and a mismatched DLL
     * must degrade to "found nothing", never to an exception on the frame thread.
     */
    public static Component parseLine(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty()) return null;
        String[] p = s.split(" ", 5);
        if (p.length < 4) return null;
        try {
            int colon = p[0].indexOf(':');
            int comma = p[1].indexOf(',');
            int ex = p[2].indexOf('x');
            if (colon < 0 || comma < 0 || ex < 0) return null;
            int group = Integer.parseInt(p[0].substring(0, colon));
            int comp = Integer.parseInt(p[0].substring(colon + 1));
            if (group < 0 || group > 0xFFFF || comp < 0 || comp > 0xFFFF) return null;
            int x = Integer.parseInt(p[1].substring(0, comma));
            int y = Integer.parseInt(p[1].substring(comma + 1));
            int w = Integer.parseInt(p[2].substring(0, ex));
            int h = Integer.parseInt(p[2].substring(ex + 1));
            boolean hidden = p[3].equals("hidden");
            if (!hidden && !p[3].equals("shown")) return null;
            String text = p.length >= 5 ? p[4].trim() : "";
            if (text.equals("-")) text = "";                 // the dump's placeholder for "no text"
            return new Component((group << 16) | comp, x, y, w, h, hidden, text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The smallest visible component whose rectangle contains {@code (px, py)}, or null.
     *
     * <p>Smallest wins because the point is inside the button AND inside every container above it;
     * the button is the innermost of those, so the least area is the most specific answer.
     * {@code maxW}/{@code maxH} throw out the containers that survive that anyway (a full-canvas
     * parent whose only child is the button would otherwise win on a screen with one component);
     * 0 means no bound. Hidden components are never returned -- a hidden rectangle under the cursor
     * is not what a click would hit.</p>
     */
    public static Component smallestContaining(List<Component> cs, int px, int py, int maxW, int maxH) {
        Component best = null;
        if (cs == null) return null;
        for (Component c : cs) {
            if (c == null || c.hidden() || c.width() <= 0 || c.height() <= 0) continue;
            if (maxW > 0 && c.width() > maxW) continue;
            if (maxH > 0 && c.height() > maxH) continue;
            if (!c.contains(px, py)) continue;
            if (best == null || c.area() < best.area()) best = c;
        }
        return best;
    }
}

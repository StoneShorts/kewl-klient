package kewl.plugins.autologin;

import java.util.List;

import kewl.api.Widgets;

/**
 * Resolves "CLICK HERE TO PLAY" to a component, so the click follows the window instead of a
 * measurement.
 *
 * <p>The button is a sprite: live on 2026-09-06 the welcome screen had interface groups loaded and
 * not one component carried text, so it cannot be found by its label. What it does have is a
 * rectangle -- and a coordinate that is known to work, {@code (centre - 3, top + 334)} on the
 * 1314x900 canvas this was measured on. So: walk the loaded components ONCE, at the moment the click
 * is due, take the smallest visible rectangle that contains that coordinate, remember its packed id,
 * and click the centre of that component from then on ({@link Widgets} explains why "smallest" is the
 * right pick and why a position-based answer has to be cross-checked against a known point).</p>
 *
 * <p><b>The fall back is the measured coordinate, and it is not a failure case.</b> Component
 * positions on this build may be parent-relative (unverified -- see {@link Widgets}); if they are,
 * nothing contains the point, the walk comes back empty, and the click goes exactly where it went
 * before. Either way {@link Resolution#how()} says which of the two paths was used, and that string
 * reaches both the log and the plugin's status line.</p>
 *
 * <p><b>The login form's buttons stay on coordinates.</b> Not an oversight: at the login screen this
 * client reports {@code [no groups loaded]} live (2026-09-06, the fingerprint line in
 * {@link LoginSequence#noteScreen}), so there is nothing to walk there -- the title screen is drawn
 * without the interface manager. If a future run shows groups at state 10, this class is the shape to
 * copy: hand {@link Widgets#smallestContaining} the Login button's measured point the same way.</p>
 *
 * <p>Not on the per-frame path: {@link #resolve} walks the tree at most once and then costs one
 * component read per call.</p>
 */
public final class PlayButton implements LoginSequence.PlayTarget {

    /**
     * Where the components come from. The seam that keeps this class testable with no game attached:
     * the plugin passes {@link #NATIVE}, the tests pass a list.
     */
    public interface Source {

        /** Every loaded component with a real rectangle. */
        List<Widgets.Component> loaded(int max);

        /** One component by packed id, or null when its group is not loaded. */
        Widgets.Component byId(int id);

        Source NATIVE = new Source() {
            @Override public List<Widgets.Component> loaded(int max) { return Widgets.loaded(max); }
            @Override public Widgets.Component byId(int id) { return Widgets.byId(id); }
        };
    }

    private final Source source;
    /** The component the first walk found, or -1 while nothing has been found yet. */
    private int foundId = -1;

    public PlayButton(Source source) {
        this.source = source;
    }

    /** The id the walk settled on, or -1 -- for the log and for tests. */
    public int foundId() { return foundId; }

    @Override
    public Resolution resolve(int canvasW, int canvasH, int offsetX, int offsetY) {
        // Already resolved: one read, and the centre follows the component if the window resized.
        if (foundId >= 0) {
            Widgets.Component c = source.byId(foundId);
            if (c != null && !c.hidden() && c.width() > 0 && c.height() > 0) {
                return new Resolution(c.centreX(), c.centreY(), "widget " + c.describe());
            }
            // The group unloaded, or the component went hidden between logins. Walk again rather
            // than clicking a stale rectangle.
            foundId = -1;
        }
        List<Widgets.Component> all = source.loaded(Widgets.DEFAULT_MAX);
        // A container is not a button. Half the canvas in each direction is generous for a button and
        // rules out the top-level interface, which is canvas-sized by construction.
        Widgets.Component hit = Widgets.smallestContaining(all, offsetX, offsetY,
                canvasW > 0 ? canvasW / 2 : 0, canvasH > 0 ? canvasH / 2 : 0);
        if (hit == null) {
            return new Resolution(offsetX, offsetY, "the measured offset -- no component of the "
                    + all.size() + " loaded contains " + offsetX + "," + offsetY);
        }
        foundId = hit.id();
        return new Resolution(hit.centreX(), hit.centreY(), "widget " + hit.describe()
                + " (found from the measured " + offsetX + "," + offsetY + ")");
    }
}

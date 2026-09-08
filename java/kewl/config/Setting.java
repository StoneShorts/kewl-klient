package kewl.config;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * One knob on a plugin's config panel.
 *
 * <p>A setting knows its own type, so the control panel can build the right widget for it without every
 * plugin having to write any Swing. Declare them in your plugin's constructor and read them in
 * {@code tick()}; the panel wires itself up.</p>
 *
 * <p>Not generic on purpose. A {@code Setting<T>} reads better in isolation and turns the panel into a
 * pile of unchecked casts, because the panel handles a heterogeneous list and has to switch on the type
 * anyway.</p>
 */
public final class Setting {

    /** What kind of value this holds, and therefore which control the panel draws. */
    public enum Kind { BOOL, INT, TEXT, COLOR, ENUM }

    /**
     * Bumped by every {@link #set}. The panel-bridge's model revision is built on top of it: it is how
     * the C++ launcher learns that a value changed in Java and the shared-memory model needs
     * republishing. Cheap on purpose -- it is written on every slider drag frame.
     */
    public static volatile long REVISION;

    /**
     * The one observer of every {@link #set}, reserved for the persistence layer
     * ({@code kewl.profile.ProfileManager}). A single slot rather than a listener list on purpose: a
     * Setting already carries per-setting change listeners for the config shim, and profile
     * persistence is a client-wide concern -- there is exactly one store, so there is exactly one
     * hook. The sink must be cheap (mark dirty, schedule a write); it runs on whatever thread called
     * {@link #set}, which is normally the frame thread, and must never do file I/O there.
     */
    public interface Sink { void settingChanged(Setting s); }

    private static volatile Sink sink;

    /** Installed once, by the profile manager. {@code null} (the default) means nothing persists. */
    public static void setSink(Sink s) { sink = s; }

    private final String key, label, description;
    private final Kind kind;
    private final int min, max;
    private final Object[] options;
    private final Object defaultValue;
    private final boolean secret;
    private Object value;
    private final List<Runnable> listeners = new ArrayList<>();

    Setting(String key, String label, String description, Kind kind, Object value, int min, int max) {
        this(key, label, description, kind, value, min, max, null, false);
    }

    /** For {@link Kind#ENUM}: {@code options} are the constants the panel offers, in order. */
    Setting(String key, String label, String description, Kind kind, Object value, int min, int max, Object[] options) {
        this(key, label, description, kind, value, min, max, options, false);
    }

    /** The full form; {@code secret} is only meaningful for {@link Kind#TEXT} (see {@link #secret()}). */
    Setting(String key, String label, String description, Kind kind, Object value, int min, int max,
            Object[] options, boolean secret) {
        this.key = key;
        this.label = label;
        this.description = description;
        this.kind = kind;
        this.value = value;
        this.defaultValue = value;
        this.min = min;
        this.max = max;
        this.options = options;
        this.secret = secret;
    }

    /** The name you look it up by. */
    public String key() { return key; }

    /** The name shown on the panel. */
    public String label() { return label; }

    /** The tooltip. Empty for none. */
    public String description() { return description; }

    public Kind kind() { return kind; }

    /** The current value, as stored. The RuneLite config shim reads this for enum settings. */
    public Object value() { return value; }

    /** The constants an ENUM offers, in panel order. Null for every other kind. */
    public Object[] options() { return options; }

    /**
     * A TEXT setting whose value must never be shown or logged in clear -- a password. It is a
     * display concern only: the value is stored, persisted, edited and reset exactly like any other
     * TEXT setting (the launcher has to be able to type into it), but every place that RENDERS or
     * PRINTS a setting's text must go through {@link #displayText()} and mask it. Nothing in this
     * class derives anything from the value for a secret -- not even its length.
     */
    public boolean secret() { return secret; }

    /**
     * The text to draw or log for this setting: the value itself for an ordinary TEXT setting, and
     * for a secret one only whether it is set -- a fixed mask, never a length. Not a hint that the
     * mask is the value's width: a masked password that gave away its length would still be a fact
     * about the password.
     */
    public String displayText() {
        if (!secret) return asText();
        return asText().isEmpty() ? "(empty)" : SECRET_MASK;
    }

    /** What every masked secret shows, whatever its length. */
    public static final String SECRET_MASK = "\u2022\u2022\u2022\u2022";

    /**
     * What it was declared with: the value a fresh plugin starts on, and the value {@link #reset}
     * restores. Profiles persist only the settings that differ from this, so a setting a plugin gains
     * in an update starts on its new default instead of on a stale copy.
     */
    public Object defaultValue() { return defaultValue; }

    /** Lower bound, for a number. */
    public int min() { return min; }

    /** Upper bound, for a number. */
    public int max() { return max; }

    /** Run {@code r} whenever the value actually changes. Used by the RuneLite config shim. */
    public void onChange(Runnable r) { listeners.add(r); }

    private void fireChanged() { for (Runnable r : listeners) { try { r.run(); } catch (Throwable t) { t.printStackTrace(); } } }

    public boolean asBool() { return value instanceof Boolean b && b; }

    public int asInt() { return value instanceof Integer i ? i : 0; }

    public String asText() { return value == null ? "" : String.valueOf(value); }

    public Color asColor() { return value instanceof Color c ? c : Color.WHITE; }

    /** Set it. Out-of-range numbers are clamped rather than rejected, so a slider cannot wedge. */
    public void set(Object v) {
        REVISION++;
        if (kind == Kind.INT && v instanceof Integer i) {
            value = Math.max(min, Math.min(max, i));
        } else {
            value = v;
        }
        fireChanged();
        Sink s = sink;
        if (s != null) {
            try {
                s.settingChanged(this);
            } catch (Throwable t) {
                // Persistence is a passenger here: a slider drag must not be able to fail because the
                // store hiccuped, and the value itself is already committed above.
                System.out.println("[config] persistence sink threw on " + key + ": " + t);
            }
        }
    }

    /**
     * Put the declared value back, the way the panel's per-setting and whole-plugin reset buttons do.
     * Deliberately routed through {@link #set} rather than writing the field: the change listeners
     * fire, {@link #REVISION} moves, and the persistence sink hears about it -- a reset that skipped
     * those would look right on screen and do nothing to the plugin.
     */
    public void reset() { set(defaultValue); }
}

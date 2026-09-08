package kewl.profile;

import java.awt.Color;
import java.util.Objects;

import kewl.config.Setting;

/**
 * A {@link Setting}'s live value as a JSON-shaped value, and back.
 *
 * <p>The config store writes JSON, and a Setting's value can be a Boolean, an Integer, a String, a
 * {@link Color} or one of an enum's constants -- none of which JSON knows. Rather than widen the
 * persistence format per kind, every value is folded into one of the four things JSON does have:</p>
 *
 * <pre>
 *   BOOL   -> true / false            -> Boolean
 *   INT    -> 12                      -> Long
 *   TEXT   -> "abc"                   -> String
 *   ENUM   -> the option's toString   -> String, matched back by same rule
 *   COLOR  -> "#5adc78" / "#805adc78" -> String, alpha FIRST when the colour has any
 *                                        (the order {@link Color#getRGB} and {@link
 *                                        Color#decode} both speak -- see {@link #color(Color)})
 * </pre>
 *
 * <p>The enum choice (store the option's {@code toString}, not its index) is deliberate: a plugin
 * that reorders or extends its options between sessions keeps every stored value that still names an
 * option, instead of silently meaning something else. An option whose text no longer matches is not
 * an error -- it decodes to null and the setting keeps its default, which is what the user would
 * want from an option that went away.</p>
 *
 * <p>Decoding returns null for "nothing to apply", which the caller skips. It never throws: a
 * corrupt or stale value in a config.json must cost one setting's default, not the profile.</p>
 */
final class SettingCodec {

    private SettingCodec() {}

    /** The value as JSON, or null when it should not be stored at all. */
    static Object encode(Setting s) {
        return switch (s.kind()) {
            case BOOL -> s.asBool();
            case INT -> (long) s.asInt();
            case TEXT -> s.asText();
            case ENUM -> s.value() == null ? null : String.valueOf(s.value());
            case COLOR -> color(s.asColor());
        };
    }

    /** The live value to hand to {@link Setting#set}, or null to leave the setting alone. */
    static Object decode(Setting s, Object stored) {
        if (stored == null) return null;
        try {
            return switch (s.kind()) {
                case BOOL -> stored instanceof Boolean b ? b : null;
                case INT -> stored instanceof Number n ? (int) n.longValue() : null;
                case TEXT -> String.valueOf(stored);
                case ENUM -> enumOption(s, String.valueOf(stored));
                case COLOR -> color(String.valueOf(stored));
            };
        } catch (RuntimeException bad) {
            System.out.println("[profile] dropping unreadable value for " + s.key() + ": " + stored);
            return null;
        }
    }

    /** Whether a stored (JSON-shaped) value says the same thing as the setting's current value. */
    static boolean matches(Setting s, Object stored) {
        Object live = encode(s);
        if (live == null || stored == null) return live == null && stored == null;
        return live instanceof Number a && stored instanceof Number b
                ? a.doubleValue() == b.doubleValue()
                : live.equals(stored);
    }

    /**
     * Alpha goes FIRST, because {@code getRGB()} is ARGB and that is the order {@link
     * Color#decode} parses -- the same convention every other "#rrggbb(aa)" string in this codebase
     * meets, so a stored value can be handed to either without translation.
     */
    private static String color(Color c) {
        boolean hasAlpha = c.getAlpha() != 255;
        return hasAlpha
                ? String.format("#%08x", c.getRGB())
                : String.format("#%06x", c.getRGB() & 0xFFFFFF);
    }

    /**
     * The inverse of {@link #color(Color)}: 8 hex digits are ARGB (alpha in the top byte), 6 are
     * opaque RGB. The lengths are of the whole string, "#" included -- getting them wrong here used
     * to make every alpha colour fail to decode at all (the length checks were off by one, so an
     * 8-digit value fell through to the "not a colour" return), which silently reverted the setting
     * to its default on every profile apply.
     */
    private static Color color(String text) {
        String t = text.trim();
        if (!t.startsWith("#")) return null;
        long rgb = Long.parseLong(t.substring(1), 16);      // throws -> caller's catch turns it null
        if (t.length() == 9) {                              // "#aarrggbb"
            int argb = (int) rgb;
            return new Color(argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF, argb >>> 24);
        }
        if (t.length() != 7) return null;                   // "#rrggbb"
        return new Color((int) (rgb & 0xFFFFFF));
    }

    /** The declared option whose {@code toString} is {@code name}, or null when none is. */
    private static Object enumOption(Setting s, String name) {
        Object[] options = s.options();
        if (options == null) return null;
        for (Object o : options) {
            if (Objects.equals(String.valueOf(o), name)) return o;
        }
        return null;
    }
}

package kewl.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The smallest JSON reader and writer this project can get away with.
 *
 * <p>Two things need JSON here: the profile store (profiles/index.json and one config.json per
 * profile) and the plugin hub's manifest. Neither justifies a dependency, and the project's whole
 * build story is "the jar ships alone next to a DLL" -- so this is a hand-rolled parser instead.
 * It is deliberately minimal: RFC 8259's object/array/string/number/true/false/null, with backslash-u
 * escapes, and nothing else.</p>
 *
 * <p>What comes back: {@link Map} (insertion-ordered, so re-writing a file does not shuffle it),
 * {@link List}, {@link String}, {@link Long} or {@link Double}, {@link Boolean}, and {@code null}.
 * Objects and arrays are newly built every parse -- nothing is shared or cached, so a caller can
 * mutate what it parsed without worrying about anyone else.</p>
 *
 * <p>Not a validating parser. A trailing comma, a stray comment or duplicate keys are not errors;
 * duplicate keys keep the last value, and anything truly malformed throws {@link ParseException},
 * which the callers (the profile store, the hub) catch and turn into "corrupt file, fall back" or
 * "bad manifest" state rather than a crash. Escaped surrogates are reassembled, because plugin
 * descriptions from a real hub will carry them.</p>
 */
public final class Json {

    private Json() {}

    /** Thrown for input that is not JSON at all. The message says where, because it is the only clue. */
    public static final class ParseException extends RuntimeException {
        ParseException(String msg, int pos) { super(msg + " at offset " + pos); }
    }

    // --------------------------------------------------------------------------- reading

    /** Parses {@code json} into the object tree described on the class. Throws on malformed input. */
    public static Object parse(String json) {
        P p = new P(json);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) throw new ParseException("trailing characters after the value", p.i);
        return v;
    }

    private static final class P {
        final String s;
        int i;

        P(String s) { this.s = s == null ? "" : s; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        char peek() {
            if (i >= s.length()) throw new ParseException("input ended early", i);
            return s.charAt(i);
        }

        Object value() {
            return switch (peek()) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                ws();
                m.put(key, value());
                ws();
                char c = peek();
                i++;
                if (c == '}') return m;
                if (c != ',') throw new ParseException("expected ',' or '}' in an object", i - 1);
            }
        }

        List<Object> array() {
            expect('[');
            List<Object> a = new ArrayList<>();
            ws();
            if (peek() == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(value());
                ws();
                char c = peek();
                i++;
                if (c == ']') return a;
                if (c != ',') throw new ParseException("expected ',' or ']' in an array", i - 1);
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new ParseException("unterminated string", i);
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw new ParseException("unterminated escape", i);
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw new ParseException("short \\u escape", i);
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw new ParseException("unknown escape \\" + e, i - 2);
                }
            }
        }

        Object number() {
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dbl = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') { i++; continue; }
                if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') { dbl = dbl || c != '-'; i++; continue; }
                break;
            }
            if (i == start) throw new ParseException("expected a value", i);
            String t = s.substring(start, i);
            try {
                return dbl ? (Object) Double.parseDouble(t) : (Object) Long.parseLong(t);
            } catch (NumberFormatException e) {
                throw new ParseException("not a number: " + t, start);
            }
        }

        Object literal(String text, Object v) {
            if (!s.startsWith(text, i)) throw new ParseException("expected " + text, i);
            i += text.length();
            return v;
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw new ParseException("expected '" + c + "'", i);
            i++;
        }
    }

    // --------------------------------------------------------------------------- writing

    /**
     * Renders the tree back to JSON. Maps are written in their iteration order (which is why the
     * parser hands back insertion-ordered maps and the stores build {@code LinkedHashMap}s), numbers
     * as their own text, and a null slot as {@code null}. Not pretty-printed: these files are read by
     * this program and occasionally by a human in an editor, and two-space indent doubles the size of
     * every profile for nothing.
     */
    public static String write(Object v) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { writeString(sb, s); return; }
        if (v instanceof Boolean || v instanceof Number) { sb.append(v); return; }
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                write(sb, l.get(i));
            }
            sb.append(']');
            return;
        }
        if (v instanceof Object[] a) {
            List<Object> l = new ArrayList<>(a.length);
            for (Object o : a) l.add(o);
            write(sb, l);
            return;
        }
        // Anything else (a Color, an enum constant, a plugin) has no JSON meaning; write it as its
        // toString rather than throwing, so a store can round-trip exotic values as strings.
        writeString(sb, v.toString());
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}

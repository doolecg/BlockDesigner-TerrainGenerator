package io.blockdesigner.terragen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer, so the runtime needs no library. Objects read as {@link LinkedHashMap} (key order
 * kept), arrays as {@link List}, numbers as {@link Double}, plus {@link String}, {@link Boolean} and {@code null}.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Parses one JSON value. */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.error("Unexpected text after the value");
        return v;
    }

    private Object value() {
        if (i >= s.length()) throw error("Unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return word("true", Boolean.TRUE);
            case 'f':
                return word("false", Boolean.FALSE);
            case 'n':
                return word("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("Unexpected '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek('}')) return m;
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw error("Expected a key");
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            if (peek('}')) return m;
            expect(',');
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek(']')) return l;
        while (true) {
            ws();
            l.add(value());
            ws();
            if (peek(']')) return l;
            expect(',');
        }
    }

    private String string() {
        i++;
        StringBuilder b = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    if (i + 4 > s.length()) throw error("Bad \\u escape");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> b.append(e);
            }
        }
        throw error("Unterminated string");
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        try {
            return Double.valueOf(s.substring(start, i));
        } catch (NumberFormatException e) {
            throw error("Bad number '" + s.substring(start, i) + "'");
        }
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) throw error("Unexpected text");
        i += w.length();
        return v;
    }

    private boolean peek(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("Expected '" + c + "'");
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private IllegalArgumentException error(String msg) {
        int line = 1, col = 1;
        for (int k = 0; k < Math.min(i, s.length()); k++) {
            if (s.charAt(k) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new IllegalArgumentException(msg + " at line " + line + ", column " + col);
    }

    // ---- writing ----------------------------------------------------------------------------------------------

    /** Writes a value as indented JSON. Whole numbers are written without ".0". */
    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, 0);
        return b.append('\n').toString();
    }

    private static void write(StringBuilder b, Object v, int indent) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            quote(b, (String) v);
        } else if (v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
            else b.append(d);
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            // Small objects of plain values stay on one line, which keeps node lists readable.
            boolean inline = m.size() <= 6 && m.values().stream().allMatch(Json::simple);
            b.append('{');
            int n = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (n++ > 0) b.append(inline ? ", " : ",");
                if (!inline) newline(b, indent + 1);
                quote(b, String.valueOf(e.getKey()));
                b.append(": ");
                write(b, e.getValue(), indent + 1);
            }
            if (!inline) newline(b, indent);
            b.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                b.append("[]");
                return;
            }
            boolean inline = l.stream().allMatch(x -> simple(x) || x instanceof List<?> ll && ll.stream().allMatch(Json::simple));
            b.append('[');
            int n = 0;
            for (Object x : l) {
                if (n++ > 0) b.append(inline ? ", " : ",");
                if (!inline) newline(b, indent + 1);
                write(b, x, indent + 1);
            }
            if (!inline) newline(b, indent);
            b.append(']');
        } else {
            throw new IllegalArgumentException("Can't write " + v.getClass().getSimpleName() + " as JSON");
        }
    }

    private static boolean simple(Object v) {
        return v == null || v instanceof String || v instanceof Number || v instanceof Boolean;
    }

    private static void newline(StringBuilder b, int indent) {
        b.append('\n');
        for (int k = 0; k < indent; k++) b.append("  ");
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                case '\r' -> b.append("\\r");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }
}

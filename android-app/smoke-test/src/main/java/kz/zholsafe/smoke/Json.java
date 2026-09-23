package kz.zholsafe.smoke;

import java.util.List;
import java.util.Map;

/** Minimal JSON writer (no third-party dependency in the harness). Maps must be insertion-ordered. */
final class Json {

    private Json() { }

    static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(v, b, 0);
        b.append('\n');
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder b, int indent) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String s) {
            quote(s, b);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            b.append(v);
        } else if (v instanceof Float f) {
            number(f.doubleValue(), b);
        } else if (v instanceof Double d) {
            number(d, b);
        } else if (v instanceof Enum<?> e) {
            quote(e.name(), b);
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : ((Map<Object, Object>) m).entrySet()) {
                pad(b, indent + 1);
                quote(String.valueOf(e.getKey()), b);
                b.append(": ");
                write(e.getValue(), b, indent + 1);
                b.append(++i < m.size() ? ",\n" : "\n");
            }
            pad(b, indent);
            b.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                b.append("[]");
                return;
            }
            boolean scalars = l.stream().allMatch(o -> o == null || o instanceof Number || o instanceof String || o instanceof Boolean || o instanceof Enum);
            if (scalars) {
                b.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) b.append(", ");
                    write(l.get(i), b, indent);
                }
                b.append(']');
                return;
            }
            b.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                pad(b, indent + 1);
                write(l.get(i), b, indent + 1);
                b.append(i + 1 < l.size() ? ",\n" : "\n");
            }
            pad(b, indent);
            b.append(']');
        } else if (v instanceof long[] a) {
            b.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) b.append(", ");
                b.append(a[i]);
            }
            b.append(']');
        } else if (v instanceof double[] a) {
            b.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) b.append(", ");
                number(a[i], b);
            }
            b.append(']');
        } else if (v instanceof float[] a) {
            b.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) b.append(", ");
                number(a[i], b);
            }
            b.append(']');
        } else {
            quote(String.valueOf(v), b);
        }
    }

    private static void number(double d, StringBuilder b) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            b.append("null"); // JSON has no NaN; absence of a value is reported explicitly as null
        } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            b.append((long) d).append(".0");
        } else {
            b.append(String.format(java.util.Locale.ROOT, "%.4f", d));
        }
    }

    private static void pad(StringBuilder b, int n) {
        for (int i = 0; i < n; i++) b.append("  ");
    }

    static void quote(String s, StringBuilder b) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }
}

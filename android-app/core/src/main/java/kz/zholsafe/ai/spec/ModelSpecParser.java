package kz.zholsafe.ai.spec;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses {@code model-spec.json} without any JSON library (core has no dependencies).
 * Supports the flat object shape written by {@code ai-training/export/write_model_spec.py}:
 * strings, numbers, booleans, null and one nested string→string object ({@code labelAliases}).
 * Unknown keys are ignored; missing required keys fail with a clear message.
 */
public final class ModelSpecParser {

    private ModelSpecParser() { }

    public static ModelSpec parse(String json) {
        Map<String, Object> m = new MiniJson(json).parseObject();
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> aliases = m.get("labelAliases") instanceof Map<?, ?> am
                    ? (Map<String, String>) (Map<?, ?>) am : Map.of();
            return new ModelSpec(
                    str(m, "modelId", true),
                    str(m, "family", true),
                    str(m, "version", true),
                    str(m, "modelFile", true),
                    str(m, "labelsFile", true),
                    str(m, "inputName", false),
                    str(m, "outputName", false),
                    integer(m, "inputWidth"),
                    integer(m, "inputHeight"),
                    m.containsKey("inputChannels") ? integer(m, "inputChannels") : 3,
                    ModelSpec.TensorLayout.valueOf(str(m, "layout", true).toUpperCase(Locale.ROOT)),
                    ModelSpec.InputType.valueOf(str(m, "inputType", true).toUpperCase(Locale.ROOT)),
                    ModelSpec.Normalization.valueOf(str(m, "normalization", true).toUpperCase(Locale.ROOT)),
                    bool(m, "letterbox"),
                    m.containsKey("padValue") ? integer(m, "padValue") : 114,
                    number(m, "confidenceThreshold"),
                    number(m, "iouThreshold"),
                    ModelSpec.DecoderType.valueOf(str(m, "decoder", true).toUpperCase(Locale.ROOT)),
                    bool(m, "nmsInModel"),
                    integer(m, "numClasses"),
                    m.containsKey("maxDetections") ? integer(m, "maxDetections") : 100,
                    aliases,
                    str(m, "sha256", false));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("model-spec.json invalid: " + e.getMessage(), e);
        }
    }

    private static String str(Map<String, Object> m, String k, boolean required) {
        Object v = m.get(k);
        if (v == null) {
            if (required) throw new IllegalArgumentException("missing '" + k + "'");
            return null;
        }
        return v.toString();
    }

    private static int integer(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Number n)) throw new IllegalArgumentException("missing/invalid integer '" + k + "'");
        return n.intValue();
    }

    private static float number(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Number n)) throw new IllegalArgumentException("missing/invalid number '" + k + "'");
        return n.floatValue();
    }

    private static boolean bool(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Boolean b)) throw new IllegalArgumentException("missing/invalid boolean '" + k + "'");
        return b;
    }

    /** Minimal recursive-descent JSON reader sufficient for model-spec files. */
    static final class MiniJson {
        private final String s;
        private int i;

        MiniJson(String s) {
            this.s = s;
        }

        Map<String, Object> parseObject() {
            ws();
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            ws();
            if (peek() == '}') { i++; return out; }
            while (true) {
                ws();
                String key = parseString();
                ws();
                expect(':');
                out.put(key, parseValue());
                ws();
                char c = next();
                if (c == '}') return out;
                if (c != ',') throw err("expected ',' or '}'");
            }
        }

        private Object parseValue() {
            ws();
            char c = peek();
            if (c == '"') return parseString();
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw err("unexpected character '" + c + "'");
            String num = s.substring(start, i);
            return num.contains(".") || num.contains("e") || num.contains("E") ? Double.parseDouble(num) : Long.parseLong(num);
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> out = new java.util.ArrayList<>();
            ws();
            if (peek() == ']') { i++; return out; }
            while (true) {
                out.add(parseValue());
                ws();
                char c = next();
                if (c == ']') return out;
                if (c != ',') throw err("expected ',' or ']'");
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private char peek() {
            if (i >= s.length()) throw err("unexpected end");
            return s.charAt(i);
        }

        private char next() {
            char c = peek();
            i++;
            return c;
        }

        private void expect(char c) {
            if (next() != c) throw err("expected '" + c + "'");
        }

        private IllegalArgumentException err(String m) {
            return new IllegalArgumentException(m + " at offset " + i);
        }

        static Map<String, Object> emptyMap() {
            return new HashMap<>();
        }
    }
}

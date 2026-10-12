package org.polyzon.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict RFC 8259 JSON parser. Not part of the public API.
 *
 * <p>Objects become unmodifiable {@link LinkedHashMap}s (a repeated key keeps the last value),
 * arrays unmodifiable lists, strings {@link String}, {@code true}/{@code false} {@link Boolean},
 * {@code null} Java {@code null}. Integers that fit become {@link Long}, every other number a
 * {@link BigDecimal}. Malformed input, nesting deeper than {@value #MAX_DEPTH}, number literals
 * longer than {@value #MAX_NUMBER_LENGTH} characters and data after the value are rejected with
 * {@link IllegalArgumentException}.
 */
public final class Json {
    /** Deepest allowed nesting of arrays and objects. */
    public static final int MAX_DEPTH = 64;
    /** Longest accepted number literal; bounds the cost of converting it. */
    public static final int MAX_NUMBER_LENGTH = 1000;

    private final String text;
    private int pos;
    private int depth;

    private Json(String text) {
        this.text = text;
    }

    /**
     * Parses a JSON text whose top-level value must be an object.
     *
     * @param text the JSON text
     * @return the parsed object
     * @throws IllegalArgumentException if the text is not valid JSON, not an object or exceeds a limit
     */
    public static Map<String, Object> parseObject(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        if (parser.pos >= text.length() || text.charAt(parser.pos) != '{') {
            throw parser.error("expected a JSON object");
        }
        Map<String, Object> value = parser.object();
        parser.skipWhitespace();
        if (parser.pos != text.length()) {
            throw parser.error("unexpected data after the value");
        }
        return value;
    }

    private Object value() {
        if (pos >= text.length()) {
            throw error("unexpected end of input");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || isDigit(c)) {
                    return number();
                }
                throw error("unexpected character");
        }
    }

    private Map<String, Object> object() {
        enter();
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (consume('}')) {
            depth--;
            return Collections.unmodifiableMap(map);
        }
        do {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != '"') {
                throw error("expected a string key");
            }
            String key = string();
            skipWhitespace();
            if (!consume(':')) {
                throw error("expected ':'");
            }
            skipWhitespace();
            map.put(key, value());
            skipWhitespace();
        } while (consume(','));
        if (!consume('}')) {
            throw error("expected ',' or '}'");
        }
        depth--;
        return Collections.unmodifiableMap(map);
    }

    private List<Object> array() {
        enter();
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (consume(']')) {
            depth--;
            return Collections.unmodifiableList(list);
        }
        do {
            skipWhitespace();
            list.add(value());
            skipWhitespace();
        } while (consume(','));
        if (!consume(']')) {
            throw error("expected ',' or ']'");
        }
        depth--;
        return Collections.unmodifiableList(list);
    }

    private void enter() {
        pos++;
        if (++depth > MAX_DEPTH) {
            throw error("nesting deeper than " + MAX_DEPTH);
        }
    }

    private String string() {
        pos++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw error("unescaped control character in string");
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            char escape = text.charAt(pos++);
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                // UTF-16 code units: an escaped surrogate pair lands as a pair in the String.
                case 'u' -> out.append(hex4());
                default -> throw error("invalid escape");
            }
        }
    }

    private char hex4() {
        if (pos + 4 > text.length()) {
            throw error("truncated \\u escape");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            char c = text.charAt(pos++);
            int digit;
            if (c >= '0' && c <= '9') {
                digit = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                digit = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                digit = c - 'A' + 10;
            } else {
                throw error("invalid \\u escape");
            }
            value = value << 4 | digit;
        }
        return (char) value;
    }

    private Object number() {
        int start = pos;
        consume('-');
        if (!consume('0')) {
            if (pos >= text.length() || !isDigit(text.charAt(pos))) {
                throw error("invalid number");
            }
            skipDigits();
        }
        boolean integral = true;
        if (consume('.')) {
            requireDigits();
            integral = false;
        }
        if (consume('e') || consume('E')) {
            if (!consume('+')) {
                consume('-');
            }
            requireDigits();
            integral = false;
        }
        if (pos - start > MAX_NUMBER_LENGTH) {
            throw error("number literal longer than " + MAX_NUMBER_LENGTH + " characters");
        }
        String literal = text.substring(start, pos);
        if (integral) {
            try {
                return Long.parseLong(literal);
            } catch (NumberFormatException tooBig) {
                // falls through to BigDecimal
            }
        }
        try {
            return new BigDecimal(literal);
        } catch (NumberFormatException outOfRange) {
            throw error("number out of range");
        }
    }

    private void requireDigits() {
        if (pos >= text.length() || !isDigit(text.charAt(pos))) {
            throw error("invalid number");
        }
        skipDigits();
    }

    private void skipDigits() {
        while (pos < text.length() && isDigit(text.charAt(pos))) {
            pos++;
        }
    }

    private Object literal(String word, Object result) {
        if (!text.startsWith(word, pos)) {
            throw error("invalid literal");
        }
        pos += word.length();
        return result;
    }

    private boolean consume(char expected) {
        if (pos < text.length() && text.charAt(pos) == expected) {
            pos++;
            return true;
        }
        return false;
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("invalid JSON at offset " + pos + ": " + message);
    }
}

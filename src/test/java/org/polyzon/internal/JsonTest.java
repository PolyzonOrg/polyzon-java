package org.polyzon.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {
    private static Object value(String json) {
        return Json.parseObject("{\"v\":" + json + "}").get("v");
    }

    @Test
    void parsesEveryValueType() {
        Map<String, Object> map = Json.parseObject(
                " {\"s\":\"x\",\"i\":-12,\"d\":1.5e2,\"t\":true,\"f\":false,\"n\":null,\"a\":[1,[]],\"o\":{}} ");
        assertEquals("x", map.get("s"));
        assertEquals(-12L, map.get("i"));
        assertEquals(new BigDecimal("1.5e2"), map.get("d"));
        assertEquals(Boolean.TRUE, map.get("t"));
        assertEquals(Boolean.FALSE, map.get("f"));
        assertTrue(map.containsKey("n"));
        assertNull(map.get("n"));
        assertEquals(List.of(1L, List.of()), map.get("a"));
        assertEquals(Map.of(), map.get("o"));
        assertEquals(List.of("s", "i", "d", "t", "f", "n", "a", "o"), List.copyOf(map.keySet()));
    }

    @Test
    void resultIsUnmodifiable() {
        Map<String, Object> map = Json.parseObject("{\"a\":[1],\"o\":{}}");
        assertThrows(UnsupportedOperationException.class, () -> map.put("x", 1));
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) map.get("a");
        assertThrows(UnsupportedOperationException.class, () -> list.add(2));
    }

    @Test
    void decodesEscapesAndSurrogatePairs() {
        assertEquals("\"\\/\b\f\n\r\tA", value("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0041\""));
        String emoji = (String) value("\"\\ud83d\\ude00\"");
        assertEquals(0x1F600, emoji.codePointAt(0));
        assertEquals(2, emoji.length());
        assertEquals("дракон 😀", value("\"дракон 😀\""));
        assertEquals("\uD800", value("\"\\uD800\""), "a lone surrogate escape is kept as is");
        assertEquals("\u00e9", value("\"\\u00E9\""));
    }

    @Test
    void numbers() {
        assertEquals(0L, value("0"));
        assertEquals(0L, value("-0"));
        assertEquals(Long.MAX_VALUE, value("9223372036854775807"));
        assertEquals(Long.MIN_VALUE, value("-9223372036854775808"));
        assertEquals(new BigDecimal("9223372036854775808"), value("9223372036854775808"));
        assertEquals(new BigDecimal("12.0"), value("12.0"));
        assertEquals(new BigDecimal("1E+999999999"), value("1e999999999"));
        assertEquals(new BigDecimal("-2.5E-3"), value("-2.5E-3"));
        assertEquals(new BigDecimal("1e+5"), value("1e+5"));
    }

    @Test
    void lastDuplicateKeyWins() {
        assertEquals(2L, Json.parseObject("{\"a\":1,\"a\":2}").get("a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", " ", "[]", "\"x\"", "1", "null", "{", "}", "{\"a\"}", "{\"a\":}", "{\"a\":1,}", "{,}", "{a:1}",
        "{'a':1}", "{\"a\":1}x", "{\"a\":1}{}", "{\"a\":[1,]}", "{\"a\":[1 2]}", "{\"a\":[}", "{\"a\":01}",
        "{\"a\":1.}", "{\"a\":.5}", "{\"a\":-}", "{\"a\":1e}", "{\"a\":1e+}", "{\"a\":+1}", "{\"a\":NaN}",
        "{\"a\":Infinity}", "{\"a\":tru}", "{\"a\":nul}", "{\"a\":True}", "{\"a\":\"x}", "{\"a\":\"\\x\"}",
        "{\"a\":\"\\u12\"}", "{\"a\":\"\\u12G4\"}", "{\"a\":\"\\u١٢٣٤\"}", "{\"a\":\"\\", "{\"a\":\"tab\there\"}",
        "{\"a\":\"line\nbreak\"}", "\uFEFF{}", "{\"a\":1}\u00a0", "{\"a\" 1}", "{\"a\":1 \"b\":2}",
        "{\"a\":1e9999999999}"
    })
    void rejectsMalformedInput(String json) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Json.parseObject(json));
        assertTrue(error.getMessage().startsWith("invalid JSON at offset "), error.getMessage());
    }

    @Test
    void acceptsAllJsonWhitespaceOnly() {
        assertEquals(Map.of("a", 1L), Json.parseObject("\t\r\n {\t\"a\"\r:\n1 }\n"));
    }

    @Test
    void nestingLimitIs64() {
        String ok = "[".repeat(63) + "]".repeat(63);
        Json.parseObject("{\"v\":" + ok + "}");
        String tooDeep = "[".repeat(64) + "]".repeat(64);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Json.parseObject("{\"v\":" + tooDeep + "}"));
        assertTrue(error.getMessage().contains("nesting deeper than 64"));
        String objects = "{\"a\":".repeat(64) + "1" + "}".repeat(64);
        Json.parseObject(objects);
        assertThrows(IllegalArgumentException.class,
                () -> Json.parseObject("{\"a\":".repeat(65) + "1" + "}".repeat(65)));
    }

    @Test
    void deepInputFailsFastWithoutStackOverflow() {
        char[] deep = new char[1_000_000];
        Arrays.fill(deep, '[');
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"v\":" + new String(deep)));
    }

    @Test
    void numberLengthIsLimited() {
        String ok = "1".repeat(Json.MAX_NUMBER_LENGTH);
        assertEquals(new BigDecimal(ok), value(ok));
        assertThrows(IllegalArgumentException.class, () -> value("1".repeat(Json.MAX_NUMBER_LENGTH + 1)));
    }
}

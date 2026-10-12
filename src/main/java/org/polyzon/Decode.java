package org.polyzon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Maps parsed JSON to result records. The server is untrusted input: unknown fields are ignored,
 * wrong-typed or missing optional fields become {@code null} (lists: empty), missing required
 * strings {@code ""}, missing integers {@code 0}; fractional counts are truncated.
 */
final class Decode {
    private Decode() {
    }

    static SearchPage searchPage(Map<?, ?> json, ResponseMeta meta) {
        return new SearchPage(
                text(json, "query"),
                optionalText(json, "correctedQuery"),
                optionalText(json, "didYouMean"),
                integer(json, "total"),
                text(json, "totalRelation"),
                integer(json, "page"),
                integer(json, "size"),
                Boolean.TRUE.equals(json.get("hasMore")),
                Boolean.TRUE.equals(json.get("partial")),
                objects(json.get("items")).stream().map(Decode::item).toList(),
                meta);
    }

    static SearchItem item(Map<?, ?> json) {
        Map<?, ?> stats = object(json.get("stats"));
        return new SearchItem(
                text(json, "source"),
                text(json, "id"),
                text(json, "title"),
                text(json, "url"),
                optionalText(json, "thumbnail"),
                optionalText(json, "creator"),
                optionalText(json, "license"),
                price(json.get("price")),
                strings(json.get("formats")),
                strings(json.get("tags")),
                new SearchItem.Stats(
                        count(stats.get("likes")),
                        count(stats.get("comments")),
                        count(stats.get("makes")),
                        count(stats.get("collects"))),
                optionalText(json, "publishedAt"));
    }

    static Model model(Map<?, ?> json, ResponseMeta meta) {
        Map<?, ?> stats = object(json.get("stats"));
        Model.License license = json.get("license") instanceof Map<?, ?> value
                ? new Model.License(text(value, "id"), optionalText(value, "raw"))
                : null;
        List<ModelRef> copies = objects(json.get("copies")).stream()
                .filter(copy -> !text(copy, "source").isEmpty() && !text(copy, "id").isEmpty())
                .map(copy -> new ModelRef(text(copy, "source"), text(copy, "id")))
                .toList();
        return new Model(
                text(json, "source"),
                text(json, "id"),
                text(json, "title"),
                text(json, "url"),
                optionalText(json, "description"),
                optionalText(json, "thumbnail"),
                strings(json.get("images")),
                optionalText(json, "creator"),
                optionalText(json, "creatorUrl"),
                optionalText(json, "category"),
                license,
                price(json.get("price")),
                strings(json.get("formats")),
                strings(json.get("tags")),
                new Model.Stats(
                        count(stats.get("likes")),
                        count(stats.get("comments")),
                        count(stats.get("makes")),
                        count(stats.get("collects")),
                        count(stats.get("downloads")),
                        count(stats.get("views"))),
                optionalText(json, "publishedAt"),
                optionalText(json, "updatedAt"),
                copies,
                meta);
    }

    static Me me(Map<?, ?> json, ResponseMeta meta) {
        Map<?, ?> account = object(json.get("account"));
        Map<?, ?> token = object(json.get("token"));
        return new Me(
                new Me.Account(text(account, "id"), optionalText(account, "displayName"), text(account, "createdAt")),
                new Me.Token(text(token, "id"), text(token, "name"), text(token, "prefix"), text(token, "createdAt")),
                meta);
    }

    static Limits limits(Map<?, ?> json, ResponseMeta meta) {
        return new Limits(window(object(json.get("perMinute"))), window(object(json.get("daily"))), meta);
    }

    private static Limits.Window window(Map<?, ?> json) {
        return new Limits.Window(
                integer(json, "limit"), integer(json, "used"), integer(json, "remaining"), integer(json, "resetAt"));
    }

    private static Price price(Object value) {
        if (!(value instanceof Map<?, ?> json)) {
            return null;
        }
        Object amount = json.get("value");
        BigDecimal decimal = amount instanceof Long whole
                ? BigDecimal.valueOf(whole)
                : amount instanceof BigDecimal fraction ? fraction : null;
        return new Price(text(json, "type"), decimal, optionalText(json, "currency"), text(json, "display"));
    }

    private static String text(Map<?, ?> json, String key) {
        return json.get(key) instanceof String value ? value : "";
    }

    private static String optionalText(Map<?, ?> json, String key) {
        return json.get(key) instanceof String value ? value : null;
    }

    private static long integer(Map<?, ?> json, String key) {
        Long value = count(json.get(key));
        return value == null ? 0 : value;
    }

    /** A JSON number as a 64-bit integer, truncated toward zero; {@code null} if not a number or out of range. */
    static Long count(Object value) {
        if (value instanceof Long whole) {
            return whole;
        }
        if (!(value instanceof BigDecimal number)) {
            return null;
        }
        // precision - scale = digits before the point; checked first so 1e999999999 is never expanded.
        int integerDigits = number.precision() - number.scale();
        if (number.signum() == 0 || integerDigits <= 0) {
            return 0L;
        }
        if (integerDigits > 19) {
            return null;
        }
        try {
            return number.setScale(0, RoundingMode.DOWN).longValueExact();
        } catch (ArithmeticException outOfRange) {
            return null;
        }
    }

    private static Map<?, ?> object(Object value) {
        return value instanceof Map<?, ?> json ? json : Map.of();
    }

    private static List<Map<?, ?>> objects(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().<Map<?, ?>>mapMulti((item, out) -> {
            if (item instanceof Map<?, ?> json) {
                out.accept(json);
            }
        }).toList();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().<String>mapMulti((item, out) -> {
            if (item instanceof String text) {
                out.accept(text);
            }
        }).toList();
    }
}

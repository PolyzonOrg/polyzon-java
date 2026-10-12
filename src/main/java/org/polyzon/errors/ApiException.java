package org.polyzon.errors;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The API answered with an HTTP error. Subclasses name the common statuses; this base class is
 * thrown for the rest (other 4xx, and 3xx because redirects are never followed).
 *
 * <p>The server answers errors as {@code application/problem+json}:
 * {@code {"title":"Too Many Requests","status":429,"code":"rate_limited","detail":"…"}}. When
 * the body is not such a document (for example an HTML page from the edge), {@link #code()} is
 * {@code null} and {@link #detail()} is {@code "HTTP <status>"}.
 *
 * <p>The message reads {@code "<status> <code>: <detail> (request id <id>)"}.
 */
public class ApiException extends PolyzonException {
    private static final long serialVersionUID = 1L;
    private static final HttpHeaders NO_HEADERS = HttpHeaders.of(Map.of(), (name, value) -> true);

    /** The HTTP status code. */
    private final int status;
    /** The problem code, or {@code null}. */
    private final String code;
    /** The problem title, or {@code null}. */
    private final String title;
    /** The problem detail, or {@code "HTTP <status>"}. */
    private final String detail;
    /** The rejected parameter, or {@code null}. */
    private final String param;
    /** The {@code X-Request-ID} header, or {@code null}. */
    private final String requestId;
    /** The parsed {@code Retry-After} header, or {@code null}. */
    private final Duration retryAfter;
    /** Not preserved by Java serialization. */
    private final transient HttpHeaders headers;
    /** Not preserved by Java serialization. */
    private final transient Map<String, Object> body;

    /**
     * Creates an exception from an HTTP error answer. Everything else is derived from the
     * status, the headers ({@code X-Request-ID}, {@code Retry-After}) and the problem document.
     *
     * @param status the HTTP status code
     * @param headers the response headers
     * @param body the parsed problem document, or {@code null} when the body was not a JSON object
     */
    public ApiException(int status, HttpHeaders headers, Map<String, ?> body) {
        super(message(status, body, headers), null);
        this.status = status;
        this.code = text(body, "code");
        this.title = text(body, "title");
        this.detail = detail(status, body);
        this.param = text(body, "param");
        this.requestId = headers.firstValue("x-request-id").orElse(null);
        this.retryAfter = headers.firstValue("retry-after").map(ApiException::parseRetryAfter).orElse(null);
        this.headers = headers;
        this.body = body == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(body));
    }

    /**
     * Creates the exception type that matches the status and problem code: 400
     * {@link BadRequestException}, 401 {@link AuthenticationException}, 403
     * {@link PermissionDeniedException}, 404 {@link NotFoundException}, 410
     * {@link GoneException}, 429 {@link RateLimitException} (or {@link QuotaExceededException}
     * for code {@code quota_exceeded}), 5xx {@link InternalServerException}, anything else
     * {@code ApiException}.
     *
     * @param status the HTTP status code
     * @param headers the response headers
     * @param body the parsed problem document, or {@code null}
     * @return the exception, not thrown
     */
    public static ApiException of(int status, HttpHeaders headers, Map<String, ?> body) {
        switch (status) {
            case 400:
                return new BadRequestException(headers, body);
            case 401:
                return new AuthenticationException(headers, body);
            case 403:
                return new PermissionDeniedException(headers, body);
            case 404:
                return new NotFoundException(headers, body);
            case 410:
                return new GoneException(headers, body);
            case 429:
                return "quota_exceeded".equals(text(body, "code"))
                        ? new QuotaExceededException(headers, body)
                        : new RateLimitException(headers, body);
            default:
                return status >= 500
                        ? new InternalServerException(status, headers, body)
                        : new ApiException(status, headers, body);
        }
    }

    /**
     * Returns the HTTP status code.
     *
     * @return the status
     */
    public int status() {
        return status;
    }

    /**
     * Returns the stable machine-readable error code, for example {@code invalid_token},
     * {@code rate_limited}, {@code quota_exceeded}, {@code bad_request}, {@code page_too_deep},
     * {@code not_found}, {@code gone}, {@code account_action_required}, {@code unavailable}.
     *
     * @return the code, or {@code null} when the body was not a problem document
     */
    public String code() {
        return code;
    }

    /**
     * Returns the short summary of the problem type, for example {@code "Too Many Requests"}.
     *
     * @return the title, or {@code null}
     */
    public String title() {
        return title;
    }

    /**
     * Returns the human-readable explanation (in Russian for most server errors).
     *
     * @return the detail; {@code "HTTP <status>"} when the server sent none
     */
    public String detail() {
        return detail;
    }

    /**
     * Returns the name of the rejected query parameter for {@code bad_request}.
     *
     * @return the parameter name, or {@code null}
     */
    public String param() {
        return param;
    }

    /**
     * Returns the server's request id ({@code X-Request-ID}); quote it when contacting support.
     *
     * @return the request id, or {@code null}
     */
    public String requestId() {
        return requestId;
    }

    /**
     * Returns how long the server asked to wait before retrying ({@code Retry-After}, in seconds
     * or as an HTTP date).
     *
     * @return the delay, or empty when the header is absent or malformed
     */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /**
     * Returns the response headers (case-insensitive lookup).
     *
     * @return the headers; empty after Java deserialization
     */
    public HttpHeaders headers() {
        return headers == null ? NO_HEADERS : headers;
    }

    /**
     * Returns the parsed problem document. Values are {@code String}, {@code Long},
     * {@code java.math.BigDecimal}, {@code Boolean}, {@code null}, {@code List} or {@code Map}.
     *
     * @return the unmodifiable document, or {@code null} when the body was not a JSON object
     *     (and after Java deserialization)
     */
    public Map<String, Object> body() {
        return body;
    }

    private static String text(Map<String, ?> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value instanceof String s && !s.isEmpty() ? s : null;
    }

    private static String detail(int status, Map<String, ?> body) {
        String detail = text(body, "detail");
        return detail != null ? detail : "HTTP " + status;
    }

    private static String message(int status, Map<String, ?> body, HttpHeaders headers) {
        String code = text(body, "code");
        StringBuilder message = new StringBuilder().append(status);
        if (code != null) {
            message.append(' ').append(code);
        }
        message.append(": ").append(detail(status, body));
        headers.firstValue("x-request-id").ifPresent(id -> message.append(" (request id ").append(id).append(')'));
        return message.toString();
    }

    private static Duration parseRetryAfter(String value) {
        String trimmed = value.trim();
        if (trimmed.matches("[0-9]+")) {
            try {
                return Duration.ofSeconds(Long.parseLong(trimmed));
            } catch (NumberFormatException huge) {
                return Duration.ofSeconds(Long.MAX_VALUE);
            }
        }
        try {
            Instant at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration wait = Duration.between(Instant.now(), at);
            return wait.isNegative() ? Duration.ZERO : wait;
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }
}

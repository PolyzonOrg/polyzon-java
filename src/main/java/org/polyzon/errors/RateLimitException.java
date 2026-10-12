package org.polyzon.errors;

import java.net.http.HttpHeaders;
import java.util.Map;

/**
 * HTTP 429: too many requests. Code {@code rate_limited} (more than 20 requests a minute by
 * default) is retried automatically after {@link #retryAfter()} when that is at most 60 seconds;
 * the subclass {@link QuotaExceededException} is never retried.
 */
public class RateLimitException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception for an HTTP 429 answer.
     *
     * @param headers the response headers
     * @param body the parsed problem document, or {@code null}
     */
    public RateLimitException(HttpHeaders headers, Map<String, ?> body) {
        super(429, headers, body);
    }
}

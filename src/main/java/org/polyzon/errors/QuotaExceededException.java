package org.polyzon.errors;

import java.net.http.HttpHeaders;
import java.util.Map;

/** HTTP 429 with code {@code quota_exceeded}: the daily quota (200 search and model requests per UTC day by default) is used up. Never retried automatically; {@link #retryAfter()} points at the next UTC midnight. */
public class QuotaExceededException extends RateLimitException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception for an HTTP 429 answer.
     *
     * @param headers the response headers
     * @param body the parsed problem document, or {@code null}
     */
    public QuotaExceededException(HttpHeaders headers, Map<String, ?> body) {
        super(headers, body);
    }
}

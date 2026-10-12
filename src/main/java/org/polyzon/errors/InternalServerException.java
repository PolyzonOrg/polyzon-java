package org.polyzon.errors;

import java.net.http.HttpHeaders;
import java.util.Map;

/**
 * HTTP 5xx: the service failed or is temporarily unavailable ({@code internal},
 * {@code unavailable}, or an edge error page). 500, 502, 503 and 504 are retried automatically.
 */
public class InternalServerException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception for an HTTP 5xx answer.
     *
     * @param status the HTTP status code, 500 or above
     * @param headers the response headers
     * @param body the parsed problem document, or {@code null}
     */
    public InternalServerException(int status, HttpHeaders headers, Map<String, ?> body) {
        super(status, headers, body);
    }
}

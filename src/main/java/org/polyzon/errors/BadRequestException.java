package org.polyzon.errors;

import java.net.http.HttpHeaders;
import java.util.Map;

/** HTTP 400: a parameter was rejected ({@code bad_request}, see {@link #param()}) or the page is too deep ({@code page_too_deep}: page &times; size &gt; 2400). The request still counted against the quota. */
public class BadRequestException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception for an HTTP 400 answer.
     *
     * @param headers the response headers
     * @param body the parsed problem document, or {@code null}
     */
    public BadRequestException(HttpHeaders headers, Map<String, ?> body) {
        super(400, headers, body);
    }
}

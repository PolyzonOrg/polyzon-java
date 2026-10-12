package org.polyzon.errors;

import java.net.http.HttpHeaders;
import java.util.Map;

/** HTTP 404: no such model ({@code not_found}). */
public class NotFoundException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception for an HTTP 404 answer.
     *
     * @param headers the response headers
     * @param body the parsed problem document, or {@code null}
     */
    public NotFoundException(HttpHeaders headers, Map<String, ?> body) {
        super(404, headers, body);
    }
}

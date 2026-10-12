package org.polyzon.errors;

import java.net.http.HttpHeaders;
import java.util.Map;

/** HTTP 410: the model was removed at the right holder's request ({@code gone}). Delete your copy of it as well. */
public class GoneException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception for an HTTP 410 answer.
     *
     * @param headers the response headers
     * @param body the parsed problem document, or {@code null}
     */
    public GoneException(HttpHeaders headers, Map<String, ?> body) {
        super(410, headers, body);
    }
}

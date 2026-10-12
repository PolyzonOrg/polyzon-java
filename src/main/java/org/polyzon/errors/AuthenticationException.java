package org.polyzon.errors;

import java.net.http.HttpHeaders;
import java.util.Map;

/** HTTP 401: no token ({@code unauthorized}) or the token is unknown or revoked ({@code invalid_token}). */
public class AuthenticationException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception for an HTTP 401 answer.
     *
     * @param headers the response headers
     * @param body the parsed problem document, or {@code null}
     */
    public AuthenticationException(HttpHeaders headers, Map<String, ?> body) {
        super(401, headers, body);
    }
}

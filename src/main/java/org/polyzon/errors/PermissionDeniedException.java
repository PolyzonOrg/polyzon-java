package org.polyzon.errors;

import java.net.http.HttpHeaders;
import java.util.Map;

/** HTTP 403: the token owner must accept the current Polyzon terms at https://polyzon.org/account/ ({@code account_action_required}). */
public class PermissionDeniedException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception for an HTTP 403 answer.
     *
     * @param headers the response headers
     * @param body the parsed problem document, or {@code null}
     */
    public PermissionDeniedException(HttpHeaders headers, Map<String, ?> body) {
        super(403, headers, body);
    }
}

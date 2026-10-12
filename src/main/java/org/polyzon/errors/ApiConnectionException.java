package org.polyzon.errors;

/**
 * The request did not produce an HTTP answer: DNS, connect, TLS, proxy or reset failure, or the
 * calling thread was interrupted. Retried automatically (see the client's {@code maxRetries}).
 */
public class ApiConnectionException extends PolyzonException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception.
     *
     * @param message the detail message
     * @param cause the underlying failure, or {@code null}
     */
    public ApiConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}

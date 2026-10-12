package org.polyzon.errors;

/** An attempt took longer than the client's {@code timeout}. Retried automatically. */
public class ApiTimeoutException extends ApiConnectionException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception.
     *
     * @param message the detail message
     * @param cause the underlying failure, or {@code null}
     */
    public ApiTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}

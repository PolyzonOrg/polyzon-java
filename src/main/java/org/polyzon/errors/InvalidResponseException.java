package org.polyzon.errors;

/**
 * A successful (2xx) answer whose body is not a JSON object, is not valid UTF-8 or is larger
 * than 8 MiB. Not retried.
 */
public class InvalidResponseException extends PolyzonException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception.
     *
     * @param message the detail message
     * @param cause the underlying failure, or {@code null}
     */
    public InvalidResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}

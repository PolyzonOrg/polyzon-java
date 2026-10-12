package org.polyzon.errors;

/**
 * Base class of every exception the SDK throws for a failed call.
 *
 * <p>Invalid arguments are reported with the standard {@link IllegalArgumentException} and
 * {@link NullPointerException} instead. Messages never contain the API key.
 */
public abstract class PolyzonException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception.
     *
     * @param message the detail message
     * @param cause the cause, or {@code null}
     */
    protected PolyzonException(String message, Throwable cause) {
        super(message, cause);
    }
}

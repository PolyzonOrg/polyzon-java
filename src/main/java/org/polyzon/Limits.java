package org.polyzon;

/**
 * The account's quota windows ({@code GET /v1/limits}). Quotas are per account, not per token.
 *
 * @param perMinute requests of any method per minute (20 by default)
 * @param daily search and model requests per UTC day (200 by default)
 * @param meta request id; excluded from {@code equals}, {@code hashCode} and {@code toString}
 */
public record Limits(Window perMinute, Window daily, ResponseMeta meta) {
    @Override
    public boolean equals(Object other) {
        return Records.equalsIgnoringMeta(this, other);
    }

    @Override
    public int hashCode() {
        return Records.hashIgnoringMeta(this);
    }

    @Override
    public String toString() {
        return Records.toStringIgnoringMeta(this);
    }

    /**
     * One quota window.
     *
     * @param limit requests allowed in the window
     * @param used requests made in the window
     * @param remaining requests left
     * @param resetAt when the next window starts, Unix seconds
     */
    public record Window(long limit, long used, long remaining, long resetAt) {
    }
}

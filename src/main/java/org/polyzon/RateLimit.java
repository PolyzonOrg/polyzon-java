package org.polyzon;

/**
 * The account's daily quota window as reported by the {@code x-ratelimit-limit},
 * {@code x-ratelimit-remaining} and {@code x-ratelimit-reset} headers.
 *
 * @param limit search and model requests allowed per UTC day
 * @param remaining requests left until the reset
 * @param resetAt when the window resets (next UTC midnight), Unix seconds
 */
public record RateLimit(long limit, long remaining, long resetAt) {
}

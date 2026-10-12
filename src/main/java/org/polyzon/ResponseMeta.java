package org.polyzon;

/**
 * Metadata of a successful answer. It is not part of {@code equals}, {@code hashCode} or
 * {@code toString} of the result that carries it.
 *
 * @param requestId the server's request id ({@code X-Request-ID}); quote it when contacting
 *     support. {@code null} when absent
 * @param rateLimit the daily quota window from the {@code x-ratelimit-*} headers, or {@code null}
 *     when they are absent
 */
public record ResponseMeta(String requestId, RateLimit rateLimit) {
}

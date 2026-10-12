/**
 * Exceptions thrown by {@link org.polyzon.PolyzonClient}.
 *
 * <pre>
 * PolyzonException
 * ├─ ApiConnectionException        network, DNS, TLS, proxy failure or interrupt
 * │  └─ ApiTimeoutException
 * ├─ InvalidResponseException      2xx answer that is not a JSON object or exceeds 8 MiB
 * └─ ApiException                  HTTP error answer (status, code, detail, request id, …)
 *    ├─ BadRequestException        400
 *    ├─ AuthenticationException    401
 *    ├─ PermissionDeniedException  403
 *    ├─ NotFoundException          404
 *    ├─ GoneException              410
 *    ├─ RateLimitException         429
 *    │  └─ QuotaExceededException  429, code quota_exceeded
 *    └─ InternalServerException    5xx
 * </pre>
 *
 * <p>All of them are unchecked. Invalid arguments are reported with
 * {@link java.lang.IllegalArgumentException} and {@link java.lang.NullPointerException} instead.
 */
package org.polyzon.errors;

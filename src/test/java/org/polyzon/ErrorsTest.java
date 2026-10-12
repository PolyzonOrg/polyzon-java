package org.polyzon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.polyzon.errors.ApiException;
import org.polyzon.errors.AuthenticationException;
import org.polyzon.errors.BadRequestException;
import org.polyzon.errors.GoneException;
import org.polyzon.errors.InternalServerException;
import org.polyzon.errors.NotFoundException;
import org.polyzon.errors.PermissionDeniedException;
import org.polyzon.errors.PolyzonException;
import org.polyzon.errors.QuotaExceededException;
import org.polyzon.errors.RateLimitException;

class ErrorsTest {
    private MockApi api;
    private PolyzonClient client;

    @BeforeEach
    void start() throws Exception {
        api = new MockApi();
        client = api.builder().maxRetries(0).build();
    }

    @AfterEach
    void stop() {
        client.close();
        api.close();
    }

    private static HttpHeaders headers(String... pairs) {
        Map<String, List<String>> map = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], List.of(pairs[i + 1]));
        }
        return HttpHeaders.of(map, (name, value) -> true);
    }

    @ParameterizedTest
    @CsvSource({
        "400, bad_request, org.polyzon.errors.BadRequestException",
        "400, page_too_deep, org.polyzon.errors.BadRequestException",
        "401, unauthorized, org.polyzon.errors.AuthenticationException",
        "401, invalid_token, org.polyzon.errors.AuthenticationException",
        "403, account_action_required, org.polyzon.errors.PermissionDeniedException",
        "404, not_found, org.polyzon.errors.NotFoundException",
        "410, gone, org.polyzon.errors.GoneException",
        "429, rate_limited, org.polyzon.errors.RateLimitException",
        "429, quota_exceeded, org.polyzon.errors.QuotaExceededException",
        "500, internal, org.polyzon.errors.InternalServerException",
        "502, bad_gateway, org.polyzon.errors.InternalServerException",
        "503, unavailable, org.polyzon.errors.InternalServerException",
        "418, teapot, org.polyzon.errors.ApiException",
        "409, conflict, org.polyzon.errors.ApiException"
    })
    void statusesMapToExceptionTypes(int status, String code, String type) {
        api.enqueue(status, MockApi.problem(status, code, "Деталь ошибки."), "X-Request-ID", "rid-" + status);

        ApiException error = assertThrows(ApiException.class, client::me);

        assertEquals(type, error.getClass().getName());
        assertEquals(status, error.status());
        assertEquals(code, error.code());
        assertEquals("T" + status, error.title());
        assertEquals("Деталь ошибки.", error.detail());
        assertEquals("rid-" + status, error.requestId());
        assertEquals(status + " " + code + ": Деталь ошибки. (request id rid-" + status + ")", error.getMessage());
        assertEquals((long) status, error.body().get("status"));
        assertFalse(error.getMessage().contains(MockApi.KEY));
        assertEquals(1, api.requests.size(), "no retry");
    }

    @Test
    void badRequestCarriesParam() {
        api.enqueue(400, "{\"title\":\"Bad Request\",\"status\":400,\"code\":\"bad_request\","
                + "\"detail\":\"size must be ≤ 50\",\"param\":\"size\"}");
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> client.search(SearchParams.builder("x").size(500).build()));
        assertEquals("size", error.param());
        assertEquals("400 bad_request: size must be ≤ 50", error.getMessage());
        assertNull(error.requestId());
        assertEquals(Optional.empty(), error.retryAfter());
    }

    @Test
    void authenticationErrorExposesHeaders() {
        api.enqueue(401, MockApi.problem(401, "invalid_token", "Токен не найден или отозван."),
                "WWW-Authenticate", "Bearer");
        AuthenticationException error = assertThrows(AuthenticationException.class, client::me);
        assertEquals(Optional.of("Bearer"), error.headers().firstValue("www-authenticate"));
        assertEquals("invalid_token", error.code());
    }

    @Test
    void rateLimitCarriesRetryAfter() {
        api.enqueue(429, MockApi.problem(429, "quota_exceeded", "Суточная квота 200 запросов исчерпана."),
                "Retry-After", "3600", "x-ratelimit-limit", "200", "x-ratelimit-remaining", "0");
        QuotaExceededException error = assertThrows(QuotaExceededException.class, client::me);
        assertTrue(error instanceof RateLimitException);
        assertEquals(Optional.of(Duration.ofHours(1)), error.retryAfter());
        assertEquals(Optional.of("0"), error.headers().firstValue("X-RateLimit-Remaining"));
    }

    @Test
    void nonProblemBodiesMapByStatusWithoutLeakingTheBody() {
        api.enqueue(502, "<html><body>Bad gateway from the edge</body></html>", "Content-Type", "text/html");
        InternalServerException error = assertThrows(InternalServerException.class, client::me);
        assertEquals(502, error.status());
        assertNull(error.code());
        assertNull(error.title());
        assertNull(error.body());
        assertEquals("HTTP 502", error.detail());
        assertEquals("502: HTTP 502", error.getMessage());

        api.enqueue(403, "", "Content-Type", "text/plain");
        PermissionDeniedException empty = assertThrows(PermissionDeniedException.class, client::me);
        assertEquals("403: HTTP 403", empty.getMessage());

        api.enqueue(404, "{\"code\":5,\"detail\":\"\"}");
        NotFoundException wrongTypes = assertThrows(NotFoundException.class, client::me);
        assertNull(wrongTypes.code());
        assertEquals("HTTP 404", wrongTypes.detail());
    }

    @Test
    void redirectsAreNeverFollowed() throws Exception {
        try (MockApi elsewhere = new MockApi()) {
            elsewhere.enqueue(200, MockApi.ME_JSON);
            api.enqueue(302, "", "Location", elsewhere.url() + "/v1/me");
            ApiException error = assertThrows(ApiException.class, client::me);
            assertEquals(ApiException.class, error.getClass());
            assertEquals(302, error.status());
            assertEquals(Optional.of(elsewhere.url() + "/v1/me"), error.headers().firstValue("location"));
            assertEquals(0, elsewhere.requests.size(), "the token never reaches the redirect target");
        }
    }

    @Test
    void factoryMapsWithoutAServer() {
        assertEquals(GoneException.class, ApiException.of(410, headers(), null).getClass());
        assertEquals(RateLimitException.class, ApiException.of(429, headers(), null).getClass());
        assertEquals(RateLimitException.class, ApiException.of(429, headers(), Map.of("code", 7)).getClass());
        assertEquals(InternalServerException.class, ApiException.of(599, headers(), Map.of()).getClass());
        assertEquals(ApiException.class, ApiException.of(301, headers(), Map.of()).getClass());
        assertTrue(ApiException.of(400, headers(), null) instanceof PolyzonException);
    }

    @Test
    void retryAfterParsing() {
        assertEquals(Optional.of(Duration.ofSeconds(5)), ApiException.of(503, headers("retry-after", " 5 "), null).retryAfter());
        assertEquals(Optional.of(Duration.ZERO), ApiException.of(503, headers("retry-after", "0"), null).retryAfter());
        assertEquals(Optional.of(Duration.ofSeconds(Long.MAX_VALUE)),
                ApiException.of(503, headers("retry-after", "99999999999999999999999"), null).retryAfter());
        assertEquals(Optional.empty(), ApiException.of(503, headers("retry-after", "soon"), null).retryAfter());
        assertEquals(Optional.empty(), ApiException.of(503, headers("retry-after", "-5"), null).retryAfter());
        assertEquals(Optional.empty(), ApiException.of(503, headers("retry-after", "1.5"), null).retryAfter());

        String past = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).minusHours(1));
        assertEquals(Optional.of(Duration.ZERO), ApiException.of(503, headers("retry-after", past), null).retryAfter());
        String future = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(30));
        Duration wait = ApiException.of(503, headers("retry-after", future), null).retryAfter().orElseThrow();
        assertTrue(wait.compareTo(Duration.ofSeconds(20)) > 0 && wait.compareTo(Duration.ofSeconds(31)) <= 0, wait.toString());
    }

    @Test
    void bodyIsAnUnmodifiableCopy() {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("code", "gone"));
        ApiException error = ApiException.of(410, headers(), body);
        body.put("code", "changed");
        assertEquals("gone", error.code());
        assertEquals("gone", error.body().get("code"));
        assertThrows(UnsupportedOperationException.class, () -> error.body().put("x", 1));
    }

    @Test
    void transientFieldsSurviveSerializationAsEmpty() throws Exception {
        ApiException original = ApiException.of(404, headers("x-request-id", "r1"), Map.of("code", "not_found"));
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            NotFoundException copy = (NotFoundException) in.readObject();
            assertEquals("not_found", copy.code());
            assertEquals("r1", copy.requestId());
            assertEquals(Map.of(), copy.headers().map());
            assertNull(copy.body());
            assertEquals(original.getMessage(), copy.getMessage());
        }
    }

    @Test
    void sameInstanceIsRethrownFromModelResult() {
        GoneException gone = new GoneException(headers(), Map.of("code", "gone"));
        ModelResult result = new ModelResult(new ModelRef("a", "b"), null, gone);
        assertSame(gone, result.error());
    }
}

package org.polyzon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.polyzon.errors.ApiConnectionException;
import org.polyzon.errors.ApiException;
import org.polyzon.errors.ApiTimeoutException;
import org.polyzon.errors.BadRequestException;
import org.polyzon.errors.InternalServerException;
import org.polyzon.errors.InvalidResponseException;
import org.polyzon.errors.QuotaExceededException;
import org.polyzon.errors.RateLimitException;

@Timeout(60)
class RetryTest {
    private MockApi api;
    private PolyzonClient client;

    @BeforeEach
    void start() throws Exception {
        api = new MockApi();
        client = api.client();
    }

    @AfterEach
    void stop() {
        client.close();
        api.close();
    }

    private static String problem(int status, String code) {
        return MockApi.problem(status, code, "x");
    }

    @Test
    void retriesUnavailableAndHonoursRetryAfter() {
        api.enqueue(503, problem(503, "unavailable"), "Retry-After", "5").enqueue(200, MockApi.ME_JSON);
        assertEquals("Алексей", client.me().account().displayName());
        assertEquals(2, api.requests.size());
        assertEquals(List.of(Duration.ofSeconds(5)), api.delays);
    }

    @Test
    void retriesServerErrorsWithJitteredExponentialBackoff() {
        api.enqueue(500, problem(500, "internal")).enqueue(502, "<html>").enqueue(504, "");
        PolyzonClient threeRetries = client.withMaxRetries(3);
        api.enqueue(200, MockApi.ME_JSON);
        threeRetries.me();
        assertEquals(4, api.requests.size());
        assertEquals(3, api.delays.size());
        long[][] ranges = {{250, 500}, {500, 1000}, {1000, 2000}};
        for (int i = 0; i < ranges.length; i++) {
            long millis = api.delays.get(i).toMillis();
            assertTrue(millis >= ranges[i][0] && millis <= ranges[i][1], "attempt " + i + ": " + millis);
        }
    }

    @Test
    void givesUpAfterMaxRetries() {
        for (int i = 0; i < 3; i++) {
            api.enqueue(503, problem(503, "unavailable"), "Retry-After", "1");
        }
        InternalServerException error = assertThrows(InternalServerException.class, client::me);
        assertEquals(503, error.status());
        assertEquals(3, api.requests.size());
        assertEquals(2, api.delays.size());
    }

    @Test
    void zeroRetriesMeansOneAttempt() {
        api.enqueue(503, problem(503, "unavailable"));
        assertThrows(InternalServerException.class, () -> client.withMaxRetries(0).me());
        assertEquals(1, api.requests.size());
    }

    @Test
    void retriesRateLimitedButNotQuotaOrClientErrors() {
        api.enqueue(429, problem(429, "rate_limited"), "Retry-After", "3").enqueue(200, MockApi.ME_JSON);
        client.me();
        assertEquals(List.of(Duration.ofSeconds(3)), api.delays);

        api.enqueue(429, problem(429, "quota_exceeded"), "Retry-After", "10");
        assertThrows(QuotaExceededException.class, client::me);
        api.enqueue(400, problem(400, "bad_request"));
        assertThrows(BadRequestException.class, client::me);
        api.enqueue(501, problem(501, "not_implemented"));
        assertThrows(InternalServerException.class, client::me);
        api.enqueue(429, "<html>edge</html>");
        assertThrows(RateLimitException.class, client::me);
        api.enqueue(200, "garbage");
        assertThrows(InvalidResponseException.class, client::me);
        assertEquals(7, api.requests.size(), "none of the last five was retried");
    }

    @Test
    void retryAfterAboveSixtySecondsIsRaisedImmediately() {
        api.enqueue(429, problem(429, "rate_limited"), "Retry-After", "61");
        RateLimitException error = assertThrows(RateLimitException.class, client::me);
        assertEquals(Duration.ofSeconds(61), error.retryAfter().orElseThrow());
        assertEquals(1, api.requests.size());
        assertTrue(api.delays.isEmpty());

        api.enqueue(503, problem(503, "unavailable"), "Retry-After", "60").enqueue(200, MockApi.ME_JSON);
        client.me();
        assertEquals(List.of(Duration.ofSeconds(60)), api.delays);
    }

    @Test
    void retryPolicyUnit() {
        HttpHeaders none = HttpHeaders.of(Map.of(), (k, v) -> true);
        for (int attempt = 4; attempt < 70; attempt += 13) {
            Duration delay = PolyzonClient.retryDelay(attempt, new ApiConnectionException("x", null));
            assertTrue(delay.toMillis() >= 4000 && delay.toMillis() <= 8000, delay.toString());
        }
        assertNull(PolyzonClient.retryDelay(0, new InvalidResponseException("x", null)));
        assertNull(PolyzonClient.retryDelay(0, ApiException.of(404, none, null)));
        assertEquals(Duration.ZERO, PolyzonClient.retryDelay(0,
                ApiException.of(429, HttpHeaders.of(Map.of("Retry-After", List.of("0")), (k, v) -> true),
                        Map.of("code", "rate_limited"))));
    }

    @Test
    void connectionFailuresAreRetriedThenRaised() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        try (PolyzonClient offline = api.builder().baseUrl("http://127.0.0.1:" + closedPort).build()) {
            ApiConnectionException error = assertThrows(ApiConnectionException.class, offline::me);
            assertFalse(error instanceof ApiTimeoutException);
            assertFalse(error.getMessage().contains(MockApi.KEY));
            assertEquals(2, api.delays.size());
        }
    }

    @Test
    void timeoutCoversSlowHeaders() {
        api.enqueue(exchange -> sleep(8000));
        PolyzonClient fast = api.builder().timeout(Duration.ofMillis(1000)).maxRetries(0).build();
        ApiTimeoutException error = assertThrows(ApiTimeoutException.class, fast::me);
        assertTrue(error.getMessage().contains("1000 ms"), error.getMessage());
        fast.close();
    }

    @Test
    void timeoutCoversASlowBody() {
        api.enqueue(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 100);
            OutputStream body = exchange.getResponseBody();
            body.write("{\"account\":".getBytes());
            body.flush();
            sleep(10_000);
        });
        PolyzonClient fast = api.builder().timeout(Duration.ofMillis(1000)).maxRetries(0).build();
        long started = System.nanoTime();
        assertThrows(ApiTimeoutException.class, fast::me);
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(7000), "did not wait for the body");
        fast.close();
    }

    @Test
    void timeoutsAreRetried() {
        api.enqueue(exchange -> sleep(10_000)).enqueue(200, MockApi.ME_JSON);
        PolyzonClient fast = api.builder().timeout(Duration.ofMillis(2000)).build();
        assertEquals("Алексей", fast.me().account().displayName());
        assertEquals(1, api.delays.size());
        fast.close();
    }

    @Test
    void asyncCallsRetryWithoutBlocking() throws Exception {
        api.enqueue(503, problem(503, "unavailable"), "Retry-After", "2").enqueue(200, MockApi.LIMITS_JSON);
        assertEquals(20, client.limitsAsync().get(10, TimeUnit.SECONDS).perMinute().limit());
        assertEquals(List.of(Duration.ofSeconds(2)), api.delays);

        api.enqueue(429, problem(429, "quota_exceeded"));
        ExecutionException error = assertThrows(ExecutionException.class, () -> client.meAsync().get());
        assertInstanceOf(QuotaExceededException.class, error.getCause());
    }

    @Test
    void defaultDelayerReallyWaits() throws Exception {
        api.enqueue(503, problem(503, "unavailable"), "Retry-After", "1").enqueue(200, MockApi.ME_JSON);
        try (PolyzonClient real = PolyzonClient.builder().apiKey(MockApi.KEY).baseUrl(api.url()).build()) {
            long started = System.nanoTime();
            real.me();
            assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(900));
            api.enqueue(503, problem(503, "unavailable"), "Retry-After", "1").enqueue(200, MockApi.ME_JSON);
            started = System.nanoTime();
            real.meAsync().get(10, TimeUnit.SECONDS);
            assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(900));
        }
    }

    @Test
    void cancellingAnAsyncCallAbortsIt() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        api.enqueue(exchange -> {
            arrived.countDown();
            sleep(5000);
        });
        CompletableFuture<Me> future = client.meAsync();
        assertTrue(arrived.await(10, TimeUnit.SECONDS));
        assertTrue(future.cancel(true));
        assertThrows(CancellationException.class, future::join);
        assertTrue(api.delays.isEmpty());
    }

    @Test
    void interruptingASyncCallRestoresTheFlag() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        api.enqueue(exchange -> {
            arrived.countDown();
            sleep(5000);
        });
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> flag = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                client.me();
            } catch (RuntimeException e) {
                thrown.set(e);
                flag.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.start();
        assertTrue(arrived.await(10, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(10_000);
        assertInstanceOf(ApiConnectionException.class, thrown.get());
        assertInstanceOf(InterruptedException.class, thrown.get().getCause());
        assertTrue(flag.get());
        assertTrue(api.delays.isEmpty(), "an interrupt is not retried");
    }

    @Test
    void interruptDuringTheRetryWaitIsNotSwallowed() throws Exception {
        api.enqueue(503, problem(503, "unavailable"), "Retry-After", "30");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        PolyzonClient slowRetry = api.builder()
                .delayer(delay -> task -> waiting.countDown())
                .build();
        Thread caller = new Thread(() -> {
            try {
                slowRetry.me();
            } catch (RuntimeException e) {
                thrown.set(e);
            }
        });
        caller.start();
        assertTrue(waiting.await(10, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(10_000);
        assertInstanceOf(ApiConnectionException.class, thrown.get());
        assertEquals(1, api.requests.size());
    }

    @Test
    void attemptsAreLoggedWithoutSecrets() {
        Logger logger = Logger.getLogger("org.polyzon");
        List<LogRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level previous = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            api.enqueue(503, problem(503, "unavailable"), "Retry-After", "1").enqueue(200, MockApi.ME_JSON);
            client.me();
            api.enqueue(404, problem(404, "not_found"));
            assertThrows(ApiException.class, () -> client.getModel("thingiverse", "tv-1"));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
        List<String> messages = records.stream().map(LogRecord::getMessage).toList();
        assertTrue(messages.contains("GET /v1/me -> 503 (attempt 1)"), messages.toString());
        assertTrue(messages.contains("GET /v1/me failed (attempt 1): 503 unavailable: x; retrying in 1000 ms"),
                messages.toString());
        assertTrue(messages.contains("GET /v1/me -> 200 (attempt 2)"), messages.toString());
        assertTrue(messages.contains("GET /v1/models/thingiverse/tv-1 failed (attempt 1): 404 not_found: x; not retrying"),
                messages.toString());
        for (String message : messages) {
            assertFalse(message.contains(MockApi.KEY) || message.contains("Bearer"), message);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

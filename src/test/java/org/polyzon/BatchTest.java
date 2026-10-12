package org.polyzon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.polyzon.errors.ApiConnectionException;
import org.polyzon.errors.ApiException;
import org.polyzon.errors.InternalServerException;
import org.polyzon.errors.NotFoundException;
import org.polyzon.errors.PolyzonException;

@Timeout(60)
class BatchTest {
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

    private static String model(String id) {
        return "{\"source\":\"thingiverse\",\"id\":\"" + id + "\",\"title\":\"Model " + id + "\",\"url\":\"https://x\"}";
    }

    private static List<ModelRef> refs(int count) {
        return IntStream.range(0, count).mapToObj(i -> new ModelRef("thingiverse", "tv-" + i)).toList();
    }

    /**
     * Answers every model request with the model named in the path. The first two requests wait
     * for each other, which proves parallelism without relying on timing.
     */
    private void echoModels(AtomicInteger inFlight, AtomicInteger maxInFlight) {
        CountDownLatch firstTwo = new CountDownLatch(2);
        api.fallback = exchange -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                firstTwo.countDown();
                firstTwo.await(10, TimeUnit.SECONDS);
                Thread.sleep(ThreadLocalRandom.current().nextInt(20, 80));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            String path = exchange.getRequestURI().getPath();
            MockApi.respond(exchange, 200, model(path.substring(path.lastIndexOf('/') + 1)));
        };
    }

    @Test
    void resultsKeepInputOrderAndConcurrencyIsBounded() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        echoModels(inFlight, maxInFlight);

        List<ModelResult> results = client.getModels(refs(24), 3);

        assertEquals(24, results.size());
        for (int i = 0; i < results.size(); i++) {
            ModelResult result = results.get(i);
            assertEquals("tv-" + i, result.ref().id());
            assertTrue(result.isSuccess());
            assertEquals("tv-" + i, result.orElseThrow().id());
            assertNull(result.error());
        }
        assertEquals(24, api.requests.size());
        assertTrue(maxInFlight.get() <= 3, "max in flight " + maxInFlight.get());
        assertTrue(maxInFlight.get() >= 2, "requests did run in parallel: " + maxInFlight.get());
    }

    @Test
    void defaultConcurrencyIsFourAndLangIsSent() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        echoModels(inFlight, maxInFlight);
        assertEquals(12, client.getModels(refs(12)).size());
        assertTrue(maxInFlight.get() <= 4);

        api.requests.clear();
        List<ModelResult> english = client.getModels(refs(2), 8, Lang.EN);
        assertTrue(english.stream().allMatch(ModelResult::isSuccess));
        assertTrue(api.requests.stream().allMatch(request -> "lang=en".equals(request.uri().getRawQuery())));
    }

    @Test
    void perItemFailuresDoNotThrow() {
        api.fallback = exchange -> {
            String id = exchange.getRequestURI().getPath().replaceAll(".*/", "");
            if (id.equals("tv-1")) {
                MockApi.respond(exchange, 404, MockApi.problem(404, "not_found", "Модель не найдена."));
            } else {
                MockApi.respond(exchange, 200, model(id));
            }
        };
        List<ModelRef> refs = new ArrayList<>(refs(3));
        refs.add(new ModelRef("Bad Source", "x"));
        refs.add(new ModelRef("thingiverse", ".."));

        List<ModelResult> results = client.getModels(refs, 2);

        assertTrue(results.get(0).isSuccess());
        assertInstanceOf(NotFoundException.class, results.get(1).error());
        assertTrue(results.get(2).isSuccess());
        assertInstanceOf(IllegalArgumentException.class, results.get(3).error());
        assertInstanceOf(IllegalArgumentException.class, results.get(4).error());
        assertEquals(3, api.requests.size(), "invalid references send no request");
    }

    @ParameterizedTest
    @CsvSource({
        "401, invalid_token, org.polyzon.errors.AuthenticationException",
        "403, account_action_required, org.polyzon.errors.PermissionDeniedException",
        "429, quota_exceeded, org.polyzon.errors.QuotaExceededException"
    })
    void fatalErrorsShortCircuitTheRest(int status, String code, String type) {
        api.enqueue(200, model("tv-0")).enqueue(status, MockApi.problem(status, code, "stop"));

        List<ModelResult> results = client.getModels(refs(6), 1);

        assertTrue(results.get(0).isSuccess());
        RuntimeException fatal = results.get(1).error();
        assertEquals(type, fatal.getClass().getName());
        for (int i = 2; i < 6; i++) {
            assertSame(fatal, results.get(i).error(), "ref " + i + " gets the same error");
        }
        assertEquals(2, api.requests.size(), "nothing is sent after the fatal error");
    }

    @Test
    void retryableErrorsDoNotShortCircuit() {
        api.enqueue(500, MockApi.problem(500, "internal", "x")).enqueue(500, MockApi.problem(500, "internal", "x"))
                .enqueue(500, MockApi.problem(500, "internal", "x"));
        api.fallback = exchange -> MockApi.respond(exchange, 200, model("ok"));

        List<ModelResult> results = client.getModels(refs(3), 1);

        assertInstanceOf(InternalServerException.class, results.get(0).error());
        assertTrue(results.get(1).isSuccess());
        assertTrue(results.get(2).isSuccess());
    }

    @Test
    void argumentValidation() {
        assertThrows(IllegalArgumentException.class, () -> client.getModels(refs(2), 0));
        assertThrows(NullPointerException.class, () -> client.getModels(null, 2));
        List<ModelRef> withNull = new ArrayList<>(refs(1));
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> client.getModels(withNull, 2));
        assertThrows(NullPointerException.class, () -> client.getModels(refs(1), 2, null));
        assertEquals(List.of(), client.getModels(List.of(), 4));
        assertEquals(0, api.requests.size());
    }

    @Test
    void interruptingABatchStopsNewRequests() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        api.fallback = exchange -> {
            arrived.countDown();
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            MockApi.respond(exchange, 200, model("late"));
        };
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                client.getModels(refs(5), 1);
            } catch (RuntimeException e) {
                thrown.set(e);
            }
        });
        caller.start();
        assertTrue(arrived.await(10, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(10_000);
        assertInstanceOf(ApiConnectionException.class, thrown.get());
        Thread.sleep(3500);
        assertEquals(1, api.requests.size(), "refs not yet started are not sent after an interrupt");
    }

    // ---------------------------------------------------------------- searchAll

    private static String page(int page, boolean hasMore, String... ids) {
        String items = Stream.of(ids)
                .map(id -> "{\"source\":\"printables\",\"id\":\"" + id + "\",\"title\":\"t\",\"url\":\"u\"}")
                .collect(Collectors.joining(","));
        return "{\"query\":\"q\",\"total\":100,\"totalRelation\":\"eq\",\"page\":" + page + ",\"size\":2,\"hasMore\":"
                + hasMore + ",\"partial\":false,\"items\":[" + items + "]}";
    }

    @Test
    void searchAllWalksPagesUntilHasMoreIsFalse() {
        api.enqueue(200, page(1, true, "a", "b")).enqueue(200, page(2, true, "c", "d"))
                .enqueue(200, page(3, false, "e"));

        List<String> ids = client.searchAll(SearchParams.builder("q").size(2).build()).map(SearchItem::id).toList();

        assertEquals(List.of("a", "b", "c", "d", "e"), ids);
        assertEquals(List.of("q=q&size=2", "q=q&page=2&size=2", "q=q&page=3&size=2"),
                api.requests.stream().map(request -> request.uri().getRawQuery()).toList());
    }

    @Test
    void searchAllStartsAtTheGivenPageAndStopsOnAnEmptyPage() {
        api.enqueue(200, page(3, true, "a")).enqueue(200, page(4, true));

        List<SearchItem> items = client.searchAll(SearchParams.builder("q").page(3).build()).toList();

        assertEquals(1, items.size());
        assertEquals(List.of("q=q&page=3", "q=q&page=4"),
                api.requests.stream().map(request -> request.uri().getRawQuery()).toList());
    }

    @Test
    void searchAllNeverRequestsPage101() {
        // size 20 × 100 pages = 2000 < 2400: the server still says hasMore on page 100.
        api.enqueue(200, page(99, true, "a")).enqueue(200, page(100, true, "b"));
        api.fallback = exchange -> MockApi.respond(exchange, 400, MockApi.problem(400, "bad_request", "page"));

        List<String> ids = client.searchAll(SearchParams.builder("q").page(99).size(20).build())
                .map(SearchItem::id).toList();

        assertEquals(List.of("a", "b"), ids);
        assertEquals(List.of("q=q&page=99&size=20", "q=q&page=100&size=20"),
                api.requests.stream().map(request -> request.uri().getRawQuery()).toList());
    }

    @Test
    void searchAllIsLazy() {
        api.enqueue(200, page(1, true, "a", "b")).enqueue(200, page(2, true, "c", "d"));

        Stream<SearchItem> stream = client.searchAll(SearchParams.builder("q").build());
        assertEquals(0, api.requests.size(), "nothing is fetched before a terminal operation");
        assertEquals(List.of("a"), stream.limit(1).map(SearchItem::id).toList());
        assertEquals(1, api.requests.size());

        api.requests.clear();
        java.util.Iterator<SearchItem> iterator = client.searchAll(SearchParams.builder("q").build()).iterator();
        assertTrue(iterator.hasNext());
        assertEquals("c", iterator.next().id());
        iterator.next();
        api.enqueue(200, page(3, false));
        assertFalse(iterator.hasNext());
        assertThrows(java.util.NoSuchElementException.class, iterator::next);
    }

    @Test
    void searchAllThrowsRequestErrorsFromTheTerminalOperation() {
        api.enqueue(200, page(1, true, "a")).enqueue(400, MockApi.problem(400, "page_too_deep", "too deep"));
        Stream<SearchItem> stream = client.searchAll(SearchParams.builder("q").build());
        ApiException error = assertThrows(ApiException.class, stream::count);
        assertEquals("page_too_deep", error.code());
        assertInstanceOf(PolyzonException.class, error);
        assertThrows(NullPointerException.class, () -> client.searchAll(null));
    }
}

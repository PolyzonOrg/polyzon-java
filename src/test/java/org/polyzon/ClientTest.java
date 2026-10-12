package org.polyzon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.polyzon.errors.InternalServerException;
import org.polyzon.errors.InvalidResponseException;
import org.polyzon.errors.NotFoundException;

class ClientTest {
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

    @Test
    void searchParsesTheDocumentedExampleAndSendsOnlyTheExpectedHeaders() {
        api.enqueue(200, MockApi.SEARCH_JSON, "X-Request-ID", "req-1", "x-ratelimit-limit", "200",
                "x-ratelimit-remaining", "163", "x-ratelimit-reset", "1791590400");

        SearchPage page = client.search(SearchParams.builder("дракон").build());

        assertEquals("дракон", page.query());
        assertNull(page.correctedQuery());
        assertNull(page.didYouMean());
        assertEquals(1873, page.total());
        assertEquals(SearchPage.RELATION_EQ, page.totalRelation());
        assertEquals(1, page.page());
        assertEquals(20, page.size());
        assertTrue(page.hasMore());
        assertFalse(page.partial());
        SearchItem item = page.items().get(0);
        assertEquals(Sources.THINGIVERSE, item.source());
        assertEquals("tv-1234567", item.id());
        assertEquals("Дракон с подвижными сочленениями (пример)", item.title());
        assertEquals("https://www.thingiverse.com/thing:1234567", item.url());
        assertEquals("https://cdn.thingiverse.com/assets/dragon.jpg", item.thumbnail());
        assertEquals("example_maker", item.creator());
        assertEquals(Licenses.CC_BY_NC, item.license());
        assertEquals(new Price(Price.FREE, BigDecimal.ZERO, null, "Бесплатно"), item.price());
        assertEquals(List.of("stl"), item.formats());
        assertEquals(List.of("дракон", "игрушка"), item.tags());
        assertEquals(new SearchItem.Stats(51234L, 870L, 3120L, 61000L), item.stats());
        assertEquals("2021-01-17T10:21:00Z", item.publishedAt());
        assertEquals(new ModelRef("thingiverse", "tv-1234567"), item.ref());
        assertEquals(new ResponseMeta("req-1", new RateLimit(200, 163, 1791590400L)), page.meta());

        MockApi.Request request = api.last();
        assertEquals("GET", request.method());
        assertEquals("/v1/search", request.uri().getRawPath());
        assertEquals("q=%D0%B4%D1%80%D0%B0%D0%BA%D0%BE%D0%BD", request.uri().getRawQuery());
        assertEquals("Bearer " + MockApi.KEY, request.headers().getFirst("Authorization"));
        assertEquals("application/json", request.headers().getFirst("Accept"));
        assertTrue(request.headers().getFirst("User-Agent").startsWith("polyzon-java/1.0.0 (Java "));
        assertNull(request.headers().getFirst("Accept-Encoding"), "no compression is requested");
        assertNull(request.headers().getFirst("Cookie"));
        assertNull(request.headers().getFirst("Upgrade"), "no h2c upgrade over cleartext: it hangs some servers");
    }

    @Test
    void searchEncodesEveryParameterOnce() {
        api.enqueue(200, MockApi.SEARCH_JSON);
        client.search(SearchParams.builder("кот и пёс + 50% & co=1")
                .page(2)
                .size(50)
                .sort(Sort.NEWEST)
                .free(true)
                .sources(Sources.THINGIVERSE, Sources.PRINTABLES)
                .formats(List.of("stl", "3mf"))
                .licenses(Licenses.CC0, Licenses.CC_BY)
                .lang(Lang.EN)
                .build());

        String query = api.last().uri().getRawQuery();
        assertEquals("q=%D0%BA%D0%BE%D1%82%20%D0%B8%20%D0%BF%D1%91%D1%81%20%2B%2050%25%20%26%20co%3D1"
                + "&page=2&size=50&sort=newest&free=1&sources=thingiverse%2Cprintables&formats=stl%2C3mf"
                + "&license=cc0%2Ccc-by&lang=en", query);
        String q = query.substring(2, query.indexOf('&'));
        assertEquals("кот и пёс + 50% & co=1", URLDecoder.decode(q, StandardCharsets.UTF_8));
    }

    @Test
    void searchOmitsUnsetParameters() {
        api.enqueue(200, MockApi.SEARCH_JSON);
        client.search(SearchParams.builder("x").free(false).sort(null).lang(null).sources(List.of()).build());
        assertEquals("q=x", api.last().uri().getRawQuery());
    }

    @Test
    void getModelParsesTheDocumentedExample() {
        api.enqueue(200, MockApi.MODEL_JSON, "X-Request-ID", "m-1");

        Model model = client.getModel("thingiverse", "tv-1234567");

        assertEquals("/v1/models/thingiverse/tv-1234567", api.last().uri().getRawPath());
        assertNull(api.last().uri().getRawQuery());
        assertEquals("Дракон с подвижными сочленениями (пример)", model.title());
        assertEquals("Дракон печатается целиком, без поддержек…", model.description());
        assertEquals(new Model.License("cc-by-nc", "Creative Commons - Attribution - Non-Commercial"), model.license());
        assertEquals("Toys & Games", model.category());
        assertEquals("https://www.thingiverse.com/example_maker", model.creatorUrl());
        assertEquals(List.of("https://cdn.thingiverse.com/assets/dragon.jpg"), model.images());
        assertEquals(new Model.Stats(51234L, 870L, 3120L, 61000L, 410000L, 990000L), model.stats());
        assertEquals("2021-02-02T08:00:00Z", model.updatedAt());
        assertEquals(List.of(new ModelRef("printables", "pr-123456")), model.copies());
        assertEquals(new ModelRef("thingiverse", "tv-1234567"), model.ref());
        assertEquals("m-1", model.meta().requestId());
        assertNull(model.meta().rateLimit());
    }

    @Test
    void getModelSendsLang() {
        api.enqueue(200, MockApi.MODEL_JSON);
        client.getModel("3dtoday", "td-1_2.3", Lang.EN);
        assertEquals("/v1/models/3dtoday/td-1_2.3", api.last().uri().getRawPath());
        assertEquals("lang=en", api.last().uri().getRawQuery());
    }

    @Test
    void meAndLimits() {
        api.enqueue(200, MockApi.ME_JSON).enqueue(200, MockApi.LIMITS_JSON);

        Me me = client.me();
        assertEquals("/v1/me", api.last().uri().getPath());
        assertEquals(new Me.Account("0f8c2a4e-…", "Алексей", "2026-03-14T10:00:00.000Z"), me.account());
        assertEquals(new Me.Token("7d0e2f8a-…", "Телеграм-бот", "pzk_Q7xR2mVb", "2026-10-09T08:15:00.000Z"),
                me.token());

        Limits limits = client.limits();
        assertEquals("/v1/limits", api.last().uri().getPath());
        assertEquals(new Limits.Window(20, 1, 19, 1791579180L), limits.perMinute());
        assertEquals(new Limits.Window(200, 37, 163, 1791590400L), limits.daily());
    }

    @Test
    void asyncTwins() throws Exception {
        api.enqueue(200, MockApi.SEARCH_JSON).enqueue(200, MockApi.MODEL_JSON).enqueue(200, MockApi.MODEL_JSON)
                .enqueue(200, MockApi.ME_JSON).enqueue(200, MockApi.LIMITS_JSON);

        assertEquals(1873, client.searchAsync(SearchParams.builder("дракон").build()).get().total());
        assertEquals("tv-1234567", client.getModelAsync("thingiverse", "tv-1234567").get().id());
        assertEquals("tv-1234567", client.getModelAsync("thingiverse", "tv-1234567", Lang.RU).get().id());
        assertEquals("lang=ru", api.last().uri().getRawQuery());
        assertEquals("Алексей", client.meAsync().get().account().displayName());
        assertEquals(200, client.limitsAsync().get().daily().limit());
    }

    @Test
    void asyncFailuresCompleteTheFutureAndBadArgumentsThrowAtOnce() {
        api.enqueue(404, MockApi.problem(404, "not_found", "Модель не найдена."));
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> client.getModelAsync("thingiverse", "tv-1").get());
        assertInstanceOf(NotFoundException.class, error.getCause());
        assertThrows(IllegalArgumentException.class, () -> client.getModelAsync("Bad", "x"));
        assertThrows(NullPointerException.class, () -> client.searchAsync(null));
    }

    @Test
    void metadataIsOptionalAndMalformedRateLimitsAreIgnored() {
        api.enqueue(200, MockApi.ME_JSON, "x-ratelimit-limit", "200", "x-ratelimit-remaining", "lots",
                "x-ratelimit-reset", "1");
        assertEquals(new ResponseMeta(null, null), client.me().meta());
        api.enqueue(200, MockApi.ME_JSON, "x-ratelimit-limit", "200");
        assertNull(client.me().meta().rateLimit());
    }

    @Test
    void lenientParsingOfSearchPages() {
        api.enqueue(200, """
                {"total":12.9,"page":"1","hasMore":"yes","partial":true,"unknown":{"x":[1]},"items":[
                  42, null, "x",
                  {"source":"printables","id":"pr-1","title":5,"thumbnail":7,"formats":"stl","tags":[1,"a",null],
                   "price":{"type":"subscription","value":"9.99"},
                   "stats":{"likes":12.0,"comments":12.7,"makes":-3.9,"collects":1e999999999}},
                  {"source":"x","id":"y","price":"free","stats":[1],"license":{"id":"cc0"}},
                  {"price":{"type":"paid","value":4.99,"currency":"USD","display":"$4.99"},
                   "stats":{"likes":1e3,"comments":0.5,"makes":true,"collects":"9"}}
                ]}
                """);

        SearchPage page = client.search(SearchParams.builder("x").build());

        assertEquals(12, page.total());
        assertEquals(0, page.page());
        assertEquals(0, page.size());
        assertEquals("", page.query());
        assertEquals("", page.totalRelation());
        assertFalse(page.hasMore());
        assertTrue(page.partial());
        assertEquals(3, page.items().size(), "non-object items are skipped");

        SearchItem first = page.items().get(0);
        assertEquals("", first.title());
        assertNull(first.thumbnail());
        assertEquals(List.of(), first.formats());
        assertEquals(List.of("a"), first.tags());
        assertEquals(new Price("subscription", null, null, ""), first.price());
        assertEquals(new SearchItem.Stats(12L, 12L, -3L, null), first.stats());

        SearchItem second = page.items().get(1);
        assertNull(second.price());
        assertNull(second.license());
        assertEquals(new SearchItem.Stats(null, null, null, null), second.stats());

        SearchItem third = page.items().get(2);
        assertEquals("", third.source());
        assertEquals(new Price(Price.PAID, new BigDecimal("4.99"), "USD", "$4.99"), third.price());
        assertEquals(new SearchItem.Stats(1000L, 0L, null, null), third.stats());
    }

    @Test
    void lenientParsingOfModelsAndAccounts() {
        api.enqueue(200, """
                {"source":"nasa","id":"na-1","title":"Moon","url":"https://x","license":"cc0","images":[1,"a"],
                 "copies":[{"source":"printables"},{"id":"x"},{"source":"","id":"y"},7,{"source":"p","id":"q"}],
                 "stats":{"downloads":2.5e1,"views":-0.0}}
                """);
        Model model = client.getModel("nasa", "na-1");
        assertNull(model.license());
        assertEquals(List.of("a"), model.images());
        assertEquals(List.of(new ModelRef("p", "q")), model.copies());
        assertEquals(new Model.Stats(null, null, null, null, 25L, 0L), model.stats());
        assertNull(model.publishedAt());

        api.enqueue(200, "{\"account\":{\"id\":7}}");
        Me me = client.me();
        assertEquals(new Me.Account("", null, ""), me.account());
        assertEquals(new Me.Token("", "", "", ""), me.token());

        api.enqueue(200, "{\"daily\":{\"limit\":200.0,\"used\":\"1\"},\"perMinute\":null}");
        Limits limits = client.limits();
        assertEquals(new Limits.Window(200, 0, 0, 0), limits.daily());
        assertEquals(new Limits.Window(0, 0, 0, 0), limits.perMinute());
    }

    @Test
    void invalidSuccessBodiesAreInvalidResponses() {
        String[] bodies = {"not json", "[1,2]", "", "<html>oops</html>", "{\"a\":1} trailing", "\"text\""};
        for (String body : bodies) {
            api.enqueue(200, body, "Content-Type", "text/html", "X-Request-ID", "bad-1");
            InvalidResponseException error = assertThrows(InvalidResponseException.class, client::me, body);
            assertTrue(error.getMessage().contains("request id bad-1"), error.getMessage());
            assertFalse(error.getMessage().contains("oops"), "the body is never copied into the message");
        }
        api.enqueue(exchange -> MockApi.respond(exchange, 200, new byte[] {'{', '"', (byte) 0xC3, '"', ':', '1', '}'}));
        assertThrows(InvalidResponseException.class, client::me, "invalid UTF-8");
    }

    @Test
    void bodiesAreCappedAt8MiB() {
        String padding = "x".repeat(PolyzonClient.MAX_BODY_BYTES - "{\"p\":\"\"}".length());
        String exactly8MiB = "{\"p\":\"" + padding + "\"}";
        assertEquals(PolyzonClient.MAX_BODY_BYTES, exactly8MiB.length());
        api.enqueue(200, exactly8MiB);
        assertEquals("", client.me().account().id());

        api.enqueue(200, exactly8MiB + " ");
        InvalidResponseException tooBig = assertThrows(InvalidResponseException.class, client::me);
        assertTrue(tooBig.getMessage().contains("larger than 8 MiB"));

        api.enqueue(500, exactly8MiB + " ");
        PolyzonClient noRetry = client.withMaxRetries(0);
        InternalServerException serverError = assertThrows(InternalServerException.class, noRetry::me);
        assertNull(serverError.code());
        assertEquals("HTTP 500", serverError.detail());
    }

    @Test
    void resultsCompareWithoutMetadata() {
        api.enqueue(200, MockApi.SEARCH_JSON, "X-Request-ID", "one").enqueue(200, MockApi.SEARCH_JSON,
                "X-Request-ID", "two", "x-ratelimit-limit", "1", "x-ratelimit-remaining", "0", "x-ratelimit-reset", "5");
        SearchPage one = client.search(SearchParams.builder("дракон").build());
        SearchPage two = client.search(SearchParams.builder("дракон").build());
        assertNotEquals(one.meta(), two.meta());
        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());
        assertFalse(one.toString().contains("meta"));
        assertTrue(one.toString().startsWith("SearchPage[query=дракон, correctedQuery=null"), one.toString());
        assertNotEquals(one, null);
        assertNotEquals(one, "SearchPage");
        assertEquals(one, one);

        api.enqueue(200, MockApi.MODEL_JSON, "X-Request-ID", "a").enqueue(200, MockApi.MODEL_JSON)
                .enqueue(200, MockApi.ME_JSON, "X-Request-ID", "a").enqueue(200, MockApi.ME_JSON)
                .enqueue(200, MockApi.LIMITS_JSON, "X-Request-ID", "a").enqueue(200, MockApi.LIMITS_JSON);
        Model modelA = client.getModel("thingiverse", "tv-1234567");
        Model modelB = client.getModel("thingiverse", "tv-1234567");
        assertEquals(modelA, modelB);
        assertEquals(modelA.hashCode(), modelB.hashCode());
        assertFalse(modelA.toString().contains("meta"));
        assertEquals(client.me(), client.me());
        Limits limitsA = client.limits();
        Limits limitsB = client.limits();
        assertEquals(limitsA, limitsB);
        assertEquals(limitsA.toString(), limitsB.toString());
        assertNotEquals(limitsA, new Limits(limitsA.daily(), limitsA.perMinute(), null));
    }

    @Test
    void resultListsAreImmutableCopies() {
        List<String> formats = new java.util.ArrayList<>(List.of("stl"));
        SearchItem item = new SearchItem("s", "i", "t", "u", null, null, null, null, formats, List.of(),
                new SearchItem.Stats(null, null, null, null), null);
        formats.add("obj");
        assertEquals(List.of("stl"), item.formats());
        assertThrows(UnsupportedOperationException.class, () -> item.formats().add("x"));
        api.enqueue(200, MockApi.SEARCH_JSON);
        SearchPage page = client.search(SearchParams.builder("x").build());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
    }

    @Test
    void modelResult() {
        ModelRef ref = new ModelRef("a", "b");
        NotFoundException missing = new NotFoundException(java.net.http.HttpHeaders.of(java.util.Map.of(), (k, v) -> true), null);
        ModelResult failed = new ModelResult(ref, null, missing);
        assertFalse(failed.isSuccess());
        assertSame(missing, assertThrows(NotFoundException.class, failed::orElseThrow));
        assertThrows(IllegalArgumentException.class, () -> new ModelResult(ref, null, null));
        assertThrows(NullPointerException.class, () -> new ModelResult(null, null, missing));
        assertThrows(NullPointerException.class, () -> new ModelRef(null, "x"));
        assertThrows(NullPointerException.class, () -> new ModelRef("x", null));
    }

    @Test
    void enumWireValues() {
        assertEquals(List.of("relevant", "popular", "newest", "likes", "makes", "downloads"),
                java.util.Arrays.stream(Sort.values()).map(Sort::value).toList());
        assertEquals("ru", Lang.RU.value());
        assertEquals("en", Lang.EN.value());
    }
}

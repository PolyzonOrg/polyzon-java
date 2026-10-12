package org.polyzon;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigTest {
    private MockApi api;

    @BeforeEach
    void start() throws Exception {
        api = new MockApi();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private static PolyzonClient.Builder offline() {
        return PolyzonClient.builder().apiKey(MockApi.KEY).env(name -> null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://api.polyzon.org", "https://api.polyzon.org/", "https://example.com/prefix/", "HTTPS://API.POLYZON.ORG",
        "https://api.polyzon.org:8443", "http://localhost:8080", "http://LOCALHOST", "http://127.0.0.1:1",
        "http://[::1]:8080", "https://10.0.0.1"
    })
    void acceptsSafeBaseUrls(String url) {
        assertDoesNotThrow(() -> offline().baseUrl(url).build().close());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://api.polyzon.org", "http://10.0.0.1", "http://localhost.evil.com", "http://127.0.0.2", "ftp://api.polyzon.org",
        "https://user:secretpw@api.polyzon.org", "https://api.polyzon.org?x=1", "https://api.polyzon.org#frag",
        "not a url", "api.polyzon.org", "https://", "mailto:a@b.c", "https:/api.polyzon.org", "//api.polyzon.org",
        "https://exa_mple.com", "https://bad host secretpw"
    })
    void rejectsUnsafeBaseUrls(String url) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> offline().baseUrl(url));
        assertFalse(error.getMessage().contains("secretpw"));
        assertTrue(error.getMessage().startsWith("baseUrl"), error.getMessage());
    }

    @Test
    void trailingSlashesAreStripped() {
        try (PolyzonClient client = offline().baseUrl("https://example.com/api//").build()) {
            assertTrue(client.toString().contains("baseUrl=https://example.com/api,"), client.toString());
        }
        api.enqueue(200, MockApi.ME_JSON);
        try (PolyzonClient client = api.builder().baseUrl(api.url() + "/").build()) {
            client.me();
            assertEquals("/v1/me", api.last().uri().getRawPath());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "pzk_x\r\nX-Evil: 1", "pzk\u0000x", "ключ", "tab\tkey", "del\u007f"})
    void rejectsUnsafeKeys(String key) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PolyzonClient.builder().apiKey(key));
        if (!key.isEmpty()) {
            assertFalse(error.getMessage().contains(key));
            assertThrows(IllegalArgumentException.class,
                    () -> PolyzonClient.builder().env(name -> name.equals("POLYZON_API_KEY") ? key : null).build());
        }
    }

    @Test
    void keyLengthLimit() {
        assertDoesNotThrow(() -> offline().apiKey("k".repeat(512)).build().close());
        assertThrows(IllegalArgumentException.class, () -> offline().apiKey("k".repeat(513)));
        assertDoesNotThrow(() -> offline().apiKey("no-pzk-prefix!").build().close(), "the pzk_ format is not enforced");
    }

    @Test
    void missingKeyFailsAtConstruction() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PolyzonClient.builder().env(name -> null).build());
        assertTrue(error.getMessage().contains("POLYZON_API_KEY"));
        assertThrows(IllegalStateException.class, () -> PolyzonClient.builder().env(name -> "").build());
    }

    @Test
    void fromEnvWithoutVariableFails() {
        Assumptions.assumeTrue(System.getenv("POLYZON_API_KEY") == null);
        assertThrows(IllegalStateException.class, PolyzonClient::fromEnv);
    }

    @Test
    void environmentSuppliesKeyAndBaseUrl() {
        api.enqueue(200, MockApi.ME_JSON);
        Map<String, String> env = Map.of("POLYZON_API_KEY", MockApi.KEY, "POLYZON_BASE_URL", api.url() + "/");
        try (PolyzonClient client = PolyzonClient.builder().env(env::get).build()) {
            client.me();
            assertEquals("Bearer " + MockApi.KEY, api.last().headers().getFirst("Authorization"));
        }
        try (PolyzonClient client = PolyzonClient.builder().env(name -> name.equals("POLYZON_API_KEY") ? "k" : "").build()) {
            assertTrue(client.toString().contains("baseUrl=https://api.polyzon.org,"));
        }
        assertThrows(IllegalArgumentException.class, () -> PolyzonClient.builder()
                .env(name -> name.equals("POLYZON_API_KEY") ? "k" : "http://evil.example").build());
    }

    @Test
    void toStringMasksTheKey() {
        try (PolyzonClient client = offline().build()) {
            String text = client.toString();
            assertTrue(text.contains("apiKey=pzk_Test…"), text);
            assertFalse(text.contains(MockApi.KEY.substring(8)));
        }
        try (PolyzonClient shortKey = offline().apiKey("abcdefgh123").build()) {
            assertTrue(shortKey.toString().contains("apiKey=…"), shortKey.toString());
            assertFalse(shortKey.toString().contains("abcdefgh"));
        }
        PolyzonClient.Builder builder = offline().proxy(URI.create("http://bob:hunter2@proxy.local:3128"));
        String text = builder.toString();
        assertTrue(text.contains("apiKey=pzk_Test…"), text);
        assertTrue(text.contains("proxy=http://proxy.local:3128"), text);
        assertFalse(text.contains("hunter2") || text.contains("bob"), text);
        assertTrue(PolyzonClient.builder().toString().contains("apiKey=null"));
        assertTrue(offline().proxy(URI.create("http://proxy.local")).toString().contains("proxy=http://proxy.local:80"));
    }

    @Test
    void userAgentSuffix() {
        api.enqueue(200, MockApi.ME_JSON);
        try (PolyzonClient client = api.builder().userAgent("my-bot/2.1 (+https://example.com)").build()) {
            client.me();
        }
        String agent = api.last().headers().getFirst("User-Agent");
        assertTrue(agent.matches("polyzon-java/1\\.0\\.0 \\(Java [^;]+; [^/]+/[^)]+\\) my-bot/2\\.1 \\(\\+https://example\\.com\\)"),
                agent);
        for (String bad : List.of("", "  ", "bad\r\nX-Evil: 1", "ünï", "x".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> offline().userAgent(bad), bad);
        }
        assertTrue(PolyzonClient.defaultUserAgent().matches("polyzon-java/1\\.0\\.0 \\(Java \\S+; .+/.+\\)"));
    }

    @Test
    void versionMatchesTheBuild() {
        assertEquals(System.getProperty("polyzon.version"), PolyzonClient.VERSION);
    }

    @Test
    void numericOptionsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> offline().timeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> offline().timeout(Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> offline().timeout(null));
        assertThrows(IllegalArgumentException.class, () -> offline().connectTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> offline().maxRetries(-1));
        try (PolyzonClient client = offline().timeout(Duration.ofSeconds(5)).connectTimeout(Duration.ofSeconds(2))
                .maxRetries(0).build()) {
            assertTrue(client.toString().contains("timeout=PT5S, maxRetries=0"), client.toString());
            assertThrows(IllegalArgumentException.class, () -> client.withTimeout(Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> client.withMaxRetries(-1));
            assertTrue(client.withTimeout(Duration.ofSeconds(1)).withMaxRetries(5).toString()
                    .contains("timeout=PT1S, maxRetries=5"));
        }
    }

    @Test
    void customHttpClientMustNotFollowRedirects() {
        for (HttpClient.Redirect redirect : List.of(HttpClient.Redirect.ALWAYS, HttpClient.Redirect.NORMAL)) {
            HttpClient follows = HttpClient.newBuilder().followRedirects(redirect).build();
            assertThrows(IllegalArgumentException.class, () -> offline().httpClient(follows));
        }
        HttpClient own = HttpClient.newHttpClient();
        assertThrows(IllegalStateException.class,
                () -> offline().httpClient(own).proxy(URI.create("http://proxy.local:1")).build());
        assertThrows(IllegalStateException.class,
                () -> offline().httpClient(own).proxySelector(HttpClient.Builder.NO_PROXY).build());
        assertThrows(IllegalStateException.class,
                () -> offline().httpClient(own).connectTimeout(Duration.ofSeconds(1)).build());
    }

    @Test
    void customHttpClientIsUsedAndLeftOpen() {
        HttpClient own = HttpClient.newHttpClient();
        api.enqueue(200, MockApi.ME_JSON).enqueue(200, MockApi.ME_JSON);
        PolyzonClient first = api.builder().httpClient(own).build();
        first.me();
        first.close();
        try (PolyzonClient second = api.builder().httpClient(own).build()) {
            second.me();
        }
        assertEquals(2, api.requests.size());
    }

    @Test
    void closedClientRejectsCalls() {
        PolyzonClient client = api.client();
        PolyzonClient derived = client.withTimeout(Duration.ofSeconds(3));
        derived.close();
        assertThrows(IllegalStateException.class, derived::me);
        client.close();
        client.close();
        assertThrows(IllegalStateException.class, client::me);
        assertThrows(IllegalStateException.class, client::limitsAsync);
        assertEquals(0, api.requests.size());
    }

    @Test
    void executorRunsAsyncWork() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (PolyzonClient client = api.builder().executor(executor).build()) {
            api.enqueue(200, MockApi.ME_JSON);
            assertEquals("Алексей", client.meAsync().get().account().displayName());
        } finally {
            executor.shutdownNow();
        }
        assertThrows(NullPointerException.class, () -> offline().executor(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Thingiverse", "thing verse", "", "a234567890123456789012345678901234", "../x", "thing-verse", "тест"
    })
    void rejectsInvalidSources(String source) {
        try (PolyzonClient client = api.client()) {
            assertThrows(IllegalArgumentException.class, () -> client.getModel(source, "tv-1"));
            assertThrows(IllegalArgumentException.class, () -> client.getModelAsync(source, "tv-1", Lang.EN));
        }
        assertEquals(0, api.requests.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..", "a/b", "a b", "a?b", "a#b", "%2e%2e", "тест", "a\\b", "a\nb"})
    void rejectsInvalidIds(String id) {
        try (PolyzonClient client = api.client()) {
            assertThrows(IllegalArgumentException.class, () -> client.getModel("thingiverse", id));
        }
        assertEquals(0, api.requests.size());
    }

    @Test
    void idLengthLimitAndNulls() {
        try (PolyzonClient client = api.client()) {
            assertThrows(IllegalArgumentException.class, () -> client.getModel("thingiverse", "x".repeat(256)));
            assertThrows(NullPointerException.class, () -> client.getModel(null, "x"));
            assertThrows(NullPointerException.class, () -> client.getModel("thingiverse", null));
            assertThrows(NullPointerException.class, () -> client.getModel("thingiverse", "x", null));
            api.enqueue(200, MockApi.MODEL_JSON);
            client.getModel("a2345678901234567890123456789012", "...".concat("x".repeat(252)));
        }
        assertEquals(1, api.requests.size());
    }

    @Test
    void searchParamsValidation() {
        assertThrows(NullPointerException.class, () -> SearchParams.builder(null).build());
        assertThrows(IllegalArgumentException.class, () -> SearchParams.builder("  ").build());
        assertThrows(IllegalArgumentException.class, () -> SearchParams.builder("x").page(0).build());
        assertThrows(IllegalArgumentException.class, () -> SearchParams.builder("x").size(0).build());
        assertThrows(IllegalArgumentException.class, () -> SearchParams.builder("x").sources("a,b").build());
        assertThrows(IllegalArgumentException.class, () -> SearchParams.builder("x").formats(" ").build());
        assertThrows(IllegalArgumentException.class, () -> SearchParams.builder("x").licenses("").build());
        assertThrows(NullPointerException.class, () -> SearchParams.builder("x").sources((String) null));
        assertThrows(NullPointerException.class,
                () -> new SearchParams("x", null, null, null, false, null, List.of(), List.of(), null));

        SearchParams params = SearchParams.builder("x").page(3).size(10).sort(Sort.LIKES).free(true)
                .sources(List.of("nasa")).formats("stl").licenses(List.of("mit")).lang(Lang.EN).build();
        assertEquals(params, params.toBuilder().build());
        assertEquals(4, params.toBuilder().page(4).build().page());
        assertThrows(UnsupportedOperationException.class, () -> params.sources().add("x"));
        assertTrue(params.toString().contains("query=x"));
    }

    @Test
    void constantsCoverTheDocumentedValues() {
        assertEquals("3dtoday", Sources.THREE_D_TODAY);
        assertEquals("3dexport", Sources.THREE_D_EXPORT);
        assertEquals("cc-by-nc-nd", Licenses.CC_BY_NC_ND);
        assertEquals("private-use", Licenses.PRIVATE_USE);
        assertEquals("open", Price.OPEN);
        assertEquals("gte", SearchPage.RELATION_GTE);
    }
}

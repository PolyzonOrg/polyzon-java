package org.polyzon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.polyzon.errors.ApiConnectionException;
import org.polyzon.errors.ApiException;

@Timeout(60)
class ProxyTest {
    /** Plays the proxy: receives absolute-form requests and answers them itself. */
    private MockApi proxy;
    /** The target; it must never be contacted directly when a proxy is set. */
    private MockApi target;

    @BeforeEach
    void start() throws Exception {
        proxy = new MockApi();
        target = new MockApi();
    }

    @AfterEach
    void stop() {
        proxy.close();
        target.close();
    }

    @Test
    void explicitProxyReceivesAbsoluteFormRequests() {
        proxy.enqueue(200, MockApi.ME_JSON);
        try (PolyzonClient client = target.builder().proxy(URI.create(proxy.url())).build()) {
            assertEquals("Алексей", client.me().account().displayName());
        }
        assertEquals(1, proxy.requests.size());
        MockApi.Request request = proxy.last();
        assertEquals(URI.create(target.url() + "/v1/me"), request.uri());
        assertTrue(request.uri().isAbsolute());
        assertNull(request.headers().getFirst("Proxy-Authorization"));
        assertEquals(0, target.requests.size());
    }

    @Test
    void proxyCredentialsAreSentUpFrontAndTheKeyStillArrives() {
        String expected = "Basic " + Base64.getEncoder().encodeToString("user@corp:p:ss w".getBytes(StandardCharsets.UTF_8));
        proxy.fallback = exchange -> {
            if (expected.equals(exchange.getRequestHeaders().getFirst("Proxy-Authorization"))) {
                MockApi.respond(exchange, 200, MockApi.ME_JSON);
            } else {
                MockApi.respond(exchange, 407, "", "Proxy-Authenticate", "Basic realm=\"test\"");
            }
        };
        URI withCredentials = URI.create("http://user%40corp:p%3Ass%20w@127.0.0.1:" + proxy.port());
        try (PolyzonClient client = target.builder().proxy(withCredentials).build()) {
            assertEquals("Алексей", client.me().account().displayName());
            assertFalse(client.toString().contains("p:ss"));
        }
        // One request carrying both headers. With an Authenticator instead, JDK 17 and 21 would
        // answer the 407 but silently drop the Authorization header, so the API never got the key.
        assertEquals(1, proxy.requests.size());
        assertEquals(expected, proxy.last().headers().getFirst("Proxy-Authorization"));
        assertEquals("Bearer " + MockApi.KEY, proxy.last().headers().getFirst("Authorization"));
        assertEquals(0, target.requests.size());
    }

    @Test
    void wrongProxyCredentialsSurfaceAsAnError() {
        proxy.fallback = exchange -> MockApi.respond(exchange, 407, "", "Proxy-Authenticate", "Basic realm=\"test\"");
        URI withCredentials = URI.create("http://user:wrong@127.0.0.1:" + proxy.port());
        try (PolyzonClient client = target.builder().proxy(withCredentials).maxRetries(2).build()) {
            ApiException error = assertThrows(ApiException.class, client::me);
            assertEquals(407, error.status());
            assertTrue(error.getMessage().contains("jdk.http.auth.tunneling.disabledSchemes"), error.getMessage());
            assertFalse(error.getMessage().contains("wrong"), error.getMessage());
        }
        assertEquals(1, proxy.requests.size(), "a 407 is not retried");
    }

    @Test
    void httpsTargetsAreTunnelledWithConnect() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> head = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = server.accept()) {
                    String request = readHead(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return request;
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            URI viaProxy = URI.create("http://127.0.0.1:" + server.getLocalPort());
            try (PolyzonClient client = PolyzonClient.builder().apiKey(MockApi.KEY).env(name -> null)
                    .baseUrl("https://localhost:9").proxy(viaProxy).maxRetries(0).build()) {
                assertThrows(ApiConnectionException.class, client::me);
            }
            String request = head.get(10, TimeUnit.SECONDS);
            assertTrue(request.startsWith("CONNECT localhost:9 HTTP/1.1\r\n"), request);
            assertFalse(request.contains("Bearer") || request.contains(MockApi.KEY), "the proxy never sees the key");
        }
    }

    private static String readHead(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            int next = in.read();
            if (next < 0) {
                break;
            }
            head.write(next);
        }
        return head.toString(StandardCharsets.US_ASCII);
    }

    @Test
    void proxySelectorAndNoProxy() {
        target.enqueue(200, MockApi.ME_JSON);
        try (PolyzonClient client = target.builder()
                .proxy(URI.create(proxy.url()))
                .proxySelector(HttpClient.Builder.NO_PROXY)
                .build()) {
            client.me();
        }
        assertEquals(1, target.requests.size(), "the later proxySelector replaced the proxy");
        assertEquals(0, proxy.requests.size());

        proxy.enqueue(200, MockApi.ME_JSON);
        try (PolyzonClient client = target.builder()
                .proxySelector(HttpClient.Builder.NO_PROXY)
                .proxy(URI.create(proxy.url() + "/"))
                .build()) {
            client.me();
        }
        assertEquals(1, proxy.requests.size(), "the later proxy replaced the selector");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "socks5://127.0.0.1:1080", "https://proxy.local:443", "http://proxy.local:3128/path", "http://proxy.local:3128?x=1",
        "http://proxy.local#f", "proxy.local:3128", "http:proxy", "http://exa_mple:3128"
    })
    void rejectsUnsupportedProxyUrls(String url) {
        assertThrows(IllegalArgumentException.class, () -> PolyzonClient.builder().proxy(URI.create(url)));
    }

    @Test
    void basicCredentialsComeFromThePercentDecodedUserInfo() {
        assertNull(PolyzonClient.basicCredentials(URI.create("http://proxy.local:3128")));
        assertEquals("Basic " + Base64.getEncoder().encodeToString("bob@corp:pä+ss:x".getBytes(StandardCharsets.UTF_8)),
                PolyzonClient.basicCredentials(URI.create("http://bob%40corp:p%C3%A4+ss%3Ax@proxy.local:3128")));
    }

    @Test
    void proxyWithoutPasswordUsesAnEmptyOne() {
        String expected = "Basic " + Base64.getEncoder().encodeToString("token:".getBytes(StandardCharsets.UTF_8));
        proxy.fallback = exchange -> {
            if (expected.equals(exchange.getRequestHeaders().getFirst("Proxy-Authorization"))) {
                MockApi.respond(exchange, 200, MockApi.ME_JSON);
            } else {
                MockApi.respond(exchange, 407, "", "Proxy-Authenticate", "Basic realm=\"test\"");
            }
        };
        try (PolyzonClient client = target.builder()
                .proxy(URI.create("http://token@127.0.0.1:" + proxy.port())).build()) {
            client.me();
        }
        assertEquals(expected, proxy.last().headers().getFirst("Proxy-Authorization"));
    }
}

package org.polyzon;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local HTTP server that plays the API: scripted answers, recorded requests. */
final class MockApi implements AutoCloseable {
    static final String KEY = "pzk_TestSecret0123456789abcdefghijklmnopqrstuvw";

    static final String SEARCH_JSON = """
            {"query":"дракон","correctedQuery":null,"didYouMean":null,"total":1873,"totalRelation":"eq",
             "page":1,"size":20,"hasMore":true,"partial":false,"items":[
              {"source":"thingiverse","id":"tv-1234567","title":"Дракон с подвижными сочленениями (пример)",
               "url":"https://www.thingiverse.com/thing:1234567","thumbnail":"https://cdn.thingiverse.com/assets/dragon.jpg",
               "creator":"example_maker","license":"cc-by-nc",
               "price":{"type":"free","value":0,"currency":null,"display":"Бесплатно"},
               "formats":["stl"],"tags":["дракон","игрушка"],
               "stats":{"likes":51234,"comments":870,"makes":3120,"collects":61000},
               "publishedAt":"2021-01-17T10:21:00Z"}]}
            """;

    static final String MODEL_JSON = """
            {"source":"thingiverse","id":"tv-1234567","title":"Дракон с подвижными сочленениями (пример)",
             "url":"https://www.thingiverse.com/thing:1234567","thumbnail":"https://cdn.thingiverse.com/assets/dragon.jpg",
             "creator":"example_maker","license":{"id":"cc-by-nc","raw":"Creative Commons - Attribution - Non-Commercial"},
             "price":{"type":"free","value":0,"currency":null,"display":"Бесплатно"},"formats":["stl"],
             "tags":["дракон","игрушка"],
             "stats":{"likes":51234,"comments":870,"makes":3120,"collects":61000,"downloads":410000,"views":990000},
             "publishedAt":"2021-01-17T10:21:00Z","description":"Дракон печатается целиком, без поддержек…",
             "images":["https://cdn.thingiverse.com/assets/dragon.jpg"],
             "creatorUrl":"https://www.thingiverse.com/example_maker","category":"Toys & Games",
             "updatedAt":"2021-02-02T08:00:00Z","copies":[{"source":"printables","id":"pr-123456"}]}
            """;

    static final String ME_JSON = """
            {"account":{"id":"0f8c2a4e-…","displayName":"Алексей","createdAt":"2026-03-14T10:00:00.000Z"},
             "token":{"id":"7d0e2f8a-…","name":"Телеграм-бот","prefix":"pzk_Q7xR2mVb","createdAt":"2026-10-09T08:15:00.000Z"}}
            """;

    static final String LIMITS_JSON = """
            {"perMinute":{"limit":20,"used":1,"remaining":19,"resetAt":1791579180},
             "daily":{"limit":200,"used":37,"remaining":163,"resetAt":1791590400}}
            """;

    /** One recorded request. */
    record Request(String method, URI uri, Headers headers) {
    }

    /** Answers one request. */
    interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    final List<Request> requests = new CopyOnWriteArrayList<>();
    final List<Duration> delays = new CopyOnWriteArrayList<>();
    private final Queue<Handler> script = new ConcurrentLinkedQueue<>();
    private final HttpServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    volatile Handler fallback = exchange -> respond(exchange, 404, problem(404, "not_found", "Такого метода нет."));

    MockApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(pool);
        server.createContext("/", exchange -> {
            Headers headers = new Headers();
            headers.putAll(exchange.getRequestHeaders());
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI(), headers));
            Handler handler = script.poll();
            try {
                (handler == null ? fallback : handler).handle(exchange);
            } catch (IOException | RuntimeException ignored) {
                // The client may have hung up (timeouts, body cap); nothing to report.
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    String url() {
        return "http://127.0.0.1:" + port();
    }

    /** A builder pointed at this server; retry waits are recorded in {@link #delays}, not slept. */
    PolyzonClient.Builder builder() {
        return PolyzonClient.builder()
                .apiKey(KEY)
                .baseUrl(url())
                .env(name -> null)
                .delayer(delay -> {
                    delays.add(delay);
                    return Runnable::run;
                });
    }

    PolyzonClient client() {
        return builder().build();
    }

    MockApi enqueue(Handler handler) {
        script.add(handler);
        return this;
    }

    /** Queues an answer; {@code headers} are name/value pairs. */
    MockApi enqueue(int status, String body, String... headers) {
        return enqueue(exchange -> respond(exchange, status, body, headers));
    }

    Request last() {
        return requests.get(requests.size() - 1);
    }

    static String problem(int status, String code, String detail) {
        return "{\"type\":\"about:blank\",\"title\":\"T" + status + "\",\"status\":" + status
                + ",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}";
    }

    static void respond(HttpExchange exchange, int status, String body, String... headers) throws IOException {
        respond(exchange, status, body.getBytes(StandardCharsets.UTF_8), headers);
    }

    static void respond(HttpExchange exchange, int status, byte[] body, String... headers) throws IOException {
        Headers out = exchange.getResponseHeaders();
        out.set("Content-Type", "application/json; charset=utf-8");
        for (int i = 0; i < headers.length; i += 2) {
            out.set(headers[i], headers[i + 1]);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream stream = exchange.getResponseBody()) {
            stream.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }
}

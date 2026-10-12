package org.polyzon;

import java.io.ByteArrayOutputStream;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.StringJoiner;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.polyzon.errors.ApiConnectionException;
import org.polyzon.errors.ApiException;
import org.polyzon.errors.ApiTimeoutException;
import org.polyzon.errors.AuthenticationException;
import org.polyzon.errors.InvalidResponseException;
import org.polyzon.errors.PermissionDeniedException;
import org.polyzon.errors.PolyzonException;
import org.polyzon.errors.QuotaExceededException;
import org.polyzon.errors.RateLimitException;
import org.polyzon.internal.Json;

/**
 * Client for the Polyzon public API ({@code https://api.polyzon.org}).
 *
 * <pre>{@code
 * try (PolyzonClient polyzon = PolyzonClient.fromEnv()) {        // reads POLYZON_API_KEY
 *     SearchPage page = polyzon.search(SearchParams.builder("дракон").free(true).build());
 *     page.items().forEach(item -> System.out.println(item.title() + " " + item.url()));
 * }
 * }</pre>
 *
 * <p><b>Thread safety.</b> A client is immutable and safe to share between threads; create one
 * per application and reuse it. Requests share the connection pool of the underlying
 * {@link HttpClient} (HTTP/2 multiplexing over TLS, keep-alive for HTTP/1.1).
 *
 * <p><b>Errors.</b> Failed calls throw unchecked {@link PolyzonException}s (see
 * {@link org.polyzon.errors}); invalid arguments throw {@link IllegalArgumentException} or
 * {@link NullPointerException} before any request is sent.
 *
 * <p><b>Retries.</b> Connection errors, timeouts, HTTP 500/502/503/504 and HTTP 429
 * {@code rate_limited} are retried up to {@code maxRetries} times (2 by default). The delay is
 * the server's {@code Retry-After} when present (a request is not retried when it asks for more
 * than 60 seconds), otherwise exponential backoff {@code min(8 s, 0.5 s * 2^attempt)} with
 * jitter. Daily quota exhaustion ({@code quota_exceeded}) is never retried.
 *
 * <p><b>Quota.</b> {@code search}, {@code getModel} and every page of {@code searchAll} cost one
 * request of the daily quota (200 per UTC day by default); every method counts against the
 * per-minute limit (20 by default). Even a request rejected with 400 costs quota.
 *
 * <p><b>Logging.</b> Each attempt is logged at {@code DEBUG} on the {@link System.Logger} named
 * {@code org.polyzon}: method, path and query, status or failure, attempt and retry delay.
 * Headers (and so the API key) are never logged.
 *
 * <p><b>Security.</b> The API key never appears in {@code toString}, exceptions or logs, redirects
 * are never followed, plain {@code http} is allowed only for loopback hosts, and TLS certificate
 * verification cannot be disabled. Keep the key on your server: never ship it in browser or
 * mobile apps.
 */
public final class PolyzonClient implements AutoCloseable {
    /** Version of this SDK, sent in the {@code User-Agent} header. */
    public static final String VERSION = "1.0.0";
    /** Production API address. */
    public static final String DEFAULT_BASE_URL = "https://api.polyzon.org";
    /** Environment variable holding the API key. */
    public static final String API_KEY_ENV = "POLYZON_API_KEY";
    /** Environment variable that overrides the base URL. */
    public static final String BASE_URL_ENV = "POLYZON_BASE_URL";

    static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final int DEFAULT_MAX_RETRIES = 2;
    static final int DEFAULT_CONCURRENCY = 4;
    /** The search service rejects pages above 100 even when {@code hasMore} is true. */
    static final int MAX_PAGE = 100;

    private static final System.Logger LOG = System.getLogger("org.polyzon");
    private static final Pattern SOURCE = Pattern.compile("[a-z0-9]{1,32}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,255}");
    private static final Pattern HEADER_SAFE = Pattern.compile("[\\x20-\\x7E]+");

    private final String apiKey;
    private final String baseUrl;
    private final Duration timeout;
    private final int maxRetries;
    private final String userAgent;
    private final HttpClient http;
    /** Preemptive {@code Proxy-Authorization} for the configured proxy, or {@code null}. */
    private final String proxyAuthorization;
    private final boolean ownsHttp;
    private final Executor asyncExecutor;
    private final Function<Duration, Executor> delayer;
    private volatile boolean closed;

    private PolyzonClient(Builder builder) {
        String key = builder.apiKey != null ? builder.apiKey : builder.env.apply(API_KEY_ENV);
        if (key == null || key.isEmpty()) {
            throw new IllegalStateException("No API key: call apiKey(...) or set " + API_KEY_ENV
                    + " (tokens are issued at https://polyzon.org/account/)");
        }
        this.apiKey = checkApiKey(key);
        String url = builder.baseUrl;
        if (url == null) {
            String fromEnv = builder.env.apply(BASE_URL_ENV);
            url = fromEnv == null || fromEnv.isEmpty() ? DEFAULT_BASE_URL : fromEnv;
        }
        this.baseUrl = checkBaseUrl(url);
        this.timeout = builder.timeout;
        this.maxRetries = builder.maxRetries;
        this.userAgent = builder.userAgentSuffix == null
                ? defaultUserAgent()
                : defaultUserAgent() + " " + builder.userAgentSuffix;
        Executor executor = builder.executor != null
                ? builder.executor
                : new CompletableFuture<Void>().defaultExecutor();
        this.asyncExecutor = executor;
        this.delayer = builder.delayer != null
                ? builder.delayer
                : delay -> CompletableFuture.delayedExecutor(delay.toNanos(), TimeUnit.NANOSECONDS, executor);
        if (builder.httpClient != null) {
            if (builder.proxy != null || builder.proxySelector != null || builder.connectTimeout != null) {
                throw new IllegalStateException(
                        "proxy, proxySelector and connectTimeout configure the built-in HttpClient;"
                                + " set them on the HttpClient passed to httpClient(...) instead");
            }
            this.http = builder.httpClient;
            this.ownsHttp = false;
        } else {
            this.http = newHttpClient(builder);
            this.ownsHttp = true;
        }
        this.proxyAuthorization = builder.proxy == null ? null : basicCredentials(builder.proxy);
    }

    private PolyzonClient(PolyzonClient base, Duration timeout, int maxRetries) {
        this.apiKey = base.apiKey;
        this.baseUrl = base.baseUrl;
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.userAgent = base.userAgent;
        this.http = base.http;
        this.proxyAuthorization = base.proxyAuthorization;
        this.ownsHttp = false;
        this.asyncExecutor = base.asyncExecutor;
        this.delayer = base.delayer;
    }

    /**
     * Starts building a client. Without {@link Builder#apiKey(String)} the key is read from
     * {@code POLYZON_API_KEY}; without {@link Builder#baseUrl(String)} the base URL comes from
     * {@code POLYZON_BASE_URL} or defaults to {@value #DEFAULT_BASE_URL}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a client with default options, configured from {@code POLYZON_API_KEY} and
     * (optionally) {@code POLYZON_BASE_URL}.
     *
     * @return a new client
     * @throws IllegalStateException if {@code POLYZON_API_KEY} is not set
     * @throws IllegalArgumentException if a variable holds an invalid value
     */
    public static PolyzonClient fromEnv() {
        return builder().build();
    }

    /**
     * Returns a client with a different per-attempt timeout that shares this client's
     * connection pool. Closing the derived client does not close the pool.
     *
     * @param timeout the total time allowed for one attempt, positive
     * @return a new client
     */
    public PolyzonClient withTimeout(Duration timeout) {
        return new PolyzonClient(this, checkPositive(timeout, "timeout"), maxRetries);
    }

    /**
     * Returns a client with a different retry budget that shares this client's connection
     * pool. Closing the derived client does not close the pool.
     *
     * @param maxRetries retries after the first attempt; 0 disables retries
     * @return a new client
     */
    public PolyzonClient withMaxRetries(int maxRetries) {
        return new PolyzonClient(this, timeout, checkMaxRetries(maxRetries));
    }

    // ---------------------------------------------------------------- search

    /**
     * Searches the catalogue ({@code GET /v1/search}). Costs one request of the daily quota.
     *
     * @param params the query and filters
     * @return one page of results
     * @throws PolyzonException if the request fails
     */
    public SearchPage search(SearchParams params) {
        return call(searchPath(params), Decode::searchPage);
    }

    /**
     * Asynchronous {@link #search(SearchParams)}. The future fails with a
     * {@link PolyzonException}; cancelling it aborts the request.
     *
     * @param params the query and filters
     * @return the future page
     */
    public CompletableFuture<SearchPage> searchAsync(SearchParams params) {
        return callAsync(searchPath(params), Decode::searchPage);
    }

    /**
     * Lazily walks all result pages starting at {@code params.page()} (default 1) and streams
     * their items. A page is fetched only when the stream needs it; walking stops after a page
     * with {@code hasMore == false} or without items, and after page 100 (the last page the
     * server serves; with a page size below 24 the 2400-model window is not reached by then).
     * <b>Every page costs one request of the
     * daily quota</b>, so bound the stream (for example with {@code limit}). Request failures
     * are thrown from the stream's terminal operation.
     *
     * @param params the query and filters; {@code size} sets the page size
     * @return a sequential stream of items
     */
    public Stream<SearchItem> searchAll(SearchParams params) {
        Objects.requireNonNull(params, "params");
        Iterator<SearchItem> items = new Iterator<>() {
            private SearchParams next = params;
            private Iterator<SearchItem> current = Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                while (!current.hasNext() && next != null) {
                    SearchPage page = search(next);
                    current = page.items().iterator();
                    int number = next.page() == null ? 1 : next.page();
                    next = page.hasMore() && !page.items().isEmpty() && number < MAX_PAGE
                            ? next.toBuilder().page(number + 1).build()
                            : null;
                }
                return current.hasNext();
            }

            @Override
            public SearchItem next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return current.next();
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(items, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    // ---------------------------------------------------------------- models

    /**
     * Fetches a model card ({@code GET /v1/models/{source}/{id}}) in the server's default
     * language. Costs one request of the daily quota.
     *
     * @param source the source site id, {@code [a-z0-9]{1,32}}
     * @param id the model id, {@code [A-Za-z0-9._-]{1,255}}
     * @return the model
     * @throws IllegalArgumentException if {@code source} or {@code id} is malformed (no request is sent)
     * @throws org.polyzon.errors.NotFoundException if there is no such model
     * @throws org.polyzon.errors.GoneException if the model was removed at the right holder's request
     * @throws PolyzonException if the request fails otherwise
     */
    public Model getModel(String source, String id) {
        return call(modelPath(source, id, null), Decode::model);
    }

    /**
     * Fetches a model card in the given language. Costs one request of the daily quota.
     *
     * @param source the source site id, {@code [a-z0-9]{1,32}}
     * @param id the model id, {@code [A-Za-z0-9._-]{1,255}}
     * @param lang the language of title, description and tags
     * @return the model
     * @throws IllegalArgumentException if {@code source} or {@code id} is malformed (no request is sent)
     * @throws PolyzonException if the request fails
     */
    public Model getModel(String source, String id, Lang lang) {
        return call(modelPath(source, id, Objects.requireNonNull(lang, "lang")), Decode::model);
    }

    /**
     * Asynchronous {@link #getModel(String, String)}.
     *
     * @param source the source site id
     * @param id the model id
     * @return the future model
     * @throws IllegalArgumentException if {@code source} or {@code id} is malformed
     */
    public CompletableFuture<Model> getModelAsync(String source, String id) {
        return callAsync(modelPath(source, id, null), Decode::model);
    }

    /**
     * Asynchronous {@link #getModel(String, String, Lang)}.
     *
     * @param source the source site id
     * @param id the model id
     * @param lang the language
     * @return the future model
     * @throws IllegalArgumentException if {@code source} or {@code id} is malformed
     */
    public CompletableFuture<Model> getModelAsync(String source, String id, Lang lang) {
        return callAsync(modelPath(source, id, Objects.requireNonNull(lang, "lang")), Decode::model);
    }

    /**
     * Fetches many models with at most 4 requests in flight.
     *
     * @param refs the models to fetch
     * @return one result per reference, in input order
     * @see #getModels(List, int, Lang)
     */
    public List<ModelResult> getModels(List<ModelRef> refs) {
        return batch(refs, DEFAULT_CONCURRENCY, null);
    }

    /**
     * Fetches many models with bounded parallelism.
     *
     * @param refs the models to fetch
     * @param concurrency the maximum number of requests in flight, at least 1
     * @return one result per reference, in input order
     * @see #getModels(List, int, Lang)
     */
    public List<ModelResult> getModels(List<ModelRef> refs, int concurrency) {
        return batch(refs, concurrency, null);
    }

    /**
     * Fetches many models with at most {@code concurrency} requests in flight and waits for all
     * of them. Each model costs one request of the daily quota.
     *
     * <p>Per-model failures do not throw: each {@link ModelResult} holds either the model or the
     * error. Once a request fails with {@link AuthenticationException},
     * {@link PermissionDeniedException} or {@link QuotaExceededException}, the references not yet
     * started get that same error without a request.
     *
     * @param refs the models to fetch
     * @param concurrency the maximum number of requests in flight, at least 1
     * @param lang the language of titles, descriptions and tags
     * @return one result per reference, in input order
     * @throws IllegalArgumentException if {@code concurrency} is below 1
     * @throws ApiConnectionException if the calling thread is interrupted while waiting
     */
    public List<ModelResult> getModels(List<ModelRef> refs, int concurrency, Lang lang) {
        return batch(refs, concurrency, Objects.requireNonNull(lang, "lang"));
    }

    // ---------------------------------------------------------------- account

    /**
     * Returns the account and token behind the API key ({@code GET /v1/me}). Does not cost
     * daily quota.
     *
     * @return the account and token
     * @throws PolyzonException if the request fails
     */
    public Me me() {
        return call("/v1/me", Decode::me);
    }

    /**
     * Asynchronous {@link #me()}.
     *
     * @return the future account and token
     */
    public CompletableFuture<Me> meAsync() {
        return callAsync("/v1/me", Decode::me);
    }

    /**
     * Returns the account's quota windows ({@code GET /v1/limits}). Does not cost daily quota.
     *
     * @return the per-minute and daily windows
     * @throws PolyzonException if the request fails
     */
    public Limits limits() {
        return call("/v1/limits", Decode::limits);
    }

    /**
     * Asynchronous {@link #limits()}.
     *
     * @return the future quota windows
     */
    public CompletableFuture<Limits> limitsAsync() {
        return callAsync("/v1/limits", Decode::limits);
    }

    /**
     * Closes the client. Later calls throw {@link IllegalStateException}. On Java 21 and later
     * this also shuts down the built-in {@link HttpClient} once running requests finish; an
     * {@code HttpClient} passed to {@link Builder#httpClient(HttpClient)} is left open.
     */
    @Override
    public void close() {
        closed = true;
        if (ownsHttp && http instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception unexpected) {
                LOG.log(Level.DEBUG, "closing the HttpClient failed", unexpected);
            }
        }
    }

    /** Returns a description with the API key masked. */
    @Override
    public String toString() {
        return "PolyzonClient[baseUrl=" + baseUrl + ", apiKey=" + mask(apiKey) + ", timeout=" + timeout
                + ", maxRetries=" + maxRetries + "]";
    }

    // ---------------------------------------------------------------- request plumbing

    private static String searchPath(SearchParams params) {
        Objects.requireNonNull(params, "params");
        StringJoiner query = new StringJoiner("&", "/v1/search?", "");
        query.add("q=" + encode(params.query()));
        if (params.page() != null) {
            query.add("page=" + params.page());
        }
        if (params.size() != null) {
            query.add("size=" + params.size());
        }
        if (params.sort() != null) {
            query.add("sort=" + params.sort().value());
        }
        if (params.free()) {
            query.add("free=1");
        }
        if (!params.sources().isEmpty()) {
            query.add("sources=" + encode(String.join(",", params.sources())));
        }
        if (!params.formats().isEmpty()) {
            query.add("formats=" + encode(String.join(",", params.formats())));
        }
        if (!params.licenses().isEmpty()) {
            query.add("license=" + encode(String.join(",", params.licenses())));
        }
        if (params.lang() != null) {
            query.add("lang=" + params.lang().value());
        }
        return query.toString();
    }

    private static String modelPath(String source, String id, Lang lang) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(id, "id");
        if (!SOURCE.matcher(source).matches()) {
            throw new IllegalArgumentException("source must match [a-z0-9]{1,32}");
        }
        // "." and ".." would be normalised away by URL handling and change the path.
        if (!ID.matcher(id).matches() || id.equals(".") || id.equals("..")) {
            throw new IllegalArgumentException("id must match [A-Za-z0-9._-]{1,255} and not be . or ..");
        }
        return "/v1/models/" + encode(source) + "/" + encode(id) + (lang == null ? "" : "?lang=" + lang.value());
    }

    /** Percent-encodes one query value or path segment (UTF-8, space as %20). */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpRequest request(String pathAndQuery) {
        if (closed) {
            throw new IllegalStateException("the client is closed");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .GET();
        if (proxyAuthorization != null) {
            // Sent up front instead of via an Authenticator: with any Authenticator set, JDK 17 and 21
            // drop the user-set Authorization header, so the API would never see the key. The JDK
            // sends this header only to the proxy (on CONNECT for https) and strips it from the
            // tunnelled request.
            builder.header("Proxy-Authorization", proxyAuthorization);
        }
        // Cleartext (loopback testing) gets plain HTTP/1.1: the JDK would otherwise send an
        // h2c "Upgrade" request, which servers with their own upgrade handler (e.g. dev servers
        // with websockets) leave hanging. Over https the version is negotiated with ALPN.
        if (baseUrl.startsWith("http://")) {
            builder.version(HttpClient.Version.HTTP_1_1);
        }
        return builder.build();
    }

    private <T> T call(String pathAndQuery, BiFunction<Map<String, Object>, ResponseMeta, T> decoder) {
        HttpRequest request = request(pathAndQuery);
        for (int attempt = 0; ; attempt++) {
            PolyzonException failure;
            try {
                return handle(request, attempt, await(send(request)), decoder);
            } catch (PolyzonException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw e;
                }
                failure = e;
            }
            Duration delay = retryDelay(request, attempt, failure);
            if (delay == null) {
                throw failure;
            }
            await(CompletableFuture.runAsync(() -> { }, delayer.apply(delay)));
        }
    }

    private <T> CompletableFuture<T> callAsync(
            String pathAndQuery, BiFunction<Map<String, Object>, ResponseMeta, T> decoder) {
        HttpRequest request = request(pathAndQuery);
        CompletableFuture<T> result = new CompletableFuture<>();
        attemptAsync(request, 0, decoder, result);
        return result;
    }

    private <T> void attemptAsync(HttpRequest request, int attempt,
            BiFunction<Map<String, Object>, ResponseMeta, T> decoder, CompletableFuture<T> result) {
        if (result.isDone()) {
            return;
        }
        CompletableFuture<HttpResponse<byte[]>> pending = send(request);
        result.whenComplete((value, error) -> pending.cancel(true));
        pending.whenComplete((response, error) -> {
            PolyzonException failure;
            try {
                if (error != null) {
                    throw transportFailure(error);
                }
                result.complete(handle(request, attempt, response, decoder));
                return;
            } catch (PolyzonException e) {
                failure = e;
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
                return;
            }
            Duration delay = retryDelay(request, attempt, failure);
            if (delay == null) {
                result.completeExceptionally(failure);
            } else {
                CompletableFuture.runAsync(() -> attemptAsync(request, attempt + 1, decoder, result),
                        delayer.apply(delay));
            }
        });
    }

    /** One attempt, bounded by {@code timeout} in total (headers and body). */
    private CompletableFuture<HttpResponse<byte[]>> send(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> exchange;
        try {
            exchange = http.sendAsync(request, info -> new CappedBody());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<HttpResponse<byte[]>> bounded =
                exchange.copy().orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);
        // Cancelling the exchange aborts the HTTP request (timeout, interrupt or caller cancel).
        bounded.whenComplete((response, error) -> {
            if (error != null) {
                exchange.cancel(true);
            }
        });
        return bounded;
    }

    /** Waits for a future on the calling thread; failures are rethrown as SDK exceptions. */
    private <X> X await(CompletableFuture<X> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ApiConnectionException("interrupted while waiting for the Polyzon API", e);
        } catch (ExecutionException e) {
            throw transportFailure(e.getCause());
        }
    }

    private RuntimeException transportFailure(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof PolyzonException || cause instanceof CancellationException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error fatal) {
            throw fatal;
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return new ApiTimeoutException("request timed out after " + timeout.toMillis() + " ms", cause);
        }
        return new ApiConnectionException("request to the Polyzon API failed: " + cause, cause);
    }

    private <T> T handle(HttpRequest request, int attempt, HttpResponse<byte[]> response,
            BiFunction<Map<String, Object>, ResponseMeta, T> decoder) {
        int status = response.statusCode();
        LOG.log(Level.DEBUG, () -> "GET " + target(request) + " -> " + status + " (attempt " + (attempt + 1) + ")");
        HttpHeaders headers = response.headers();
        byte[] body = response.body();
        if (status == 407) {
            throw ApiException.of(status, headers, Map.of("detail", "the proxy requires authentication: put the credentials"
                    + " in the proxy URL; for HTTPS tunnels also start the JVM with"
                    + " -Djdk.http.auth.tunneling.disabledSchemes=\"\""));
        }
        if (status < 200 || status > 299) {
            throw ApiException.of(status, headers, problem(body));
        }
        String requestId = headers.firstValue("x-request-id").orElse(null);
        String suffix = requestId == null ? "" : " (request id " + requestId + ")";
        if (body == null) {
            throw new InvalidResponseException("response body is larger than 8 MiB" + suffix, null);
        }
        Map<String, Object> json;
        try {
            json = Json.parseObject(utf8(body));
        } catch (IllegalArgumentException | CharacterCodingException e) {
            throw new InvalidResponseException("HTTP " + status + " answer is not a JSON object" + suffix, e);
        }
        return decoder.apply(json, new ResponseMeta(requestId, rateLimit(headers)));
    }

    private static Map<String, Object> problem(byte[] body) {
        if (body == null) {
            return null;
        }
        try {
            return Json.parseObject(utf8(body));
        } catch (IllegalArgumentException | CharacterCodingException notProblemJson) {
            return null;
        }
    }

    private static String utf8(byte[] body) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
    }

    private static RateLimit rateLimit(HttpHeaders headers) {
        try {
            return new RateLimit(
                    Long.parseLong(headers.firstValue("x-ratelimit-limit").orElseThrow().trim()),
                    Long.parseLong(headers.firstValue("x-ratelimit-remaining").orElseThrow().trim()),
                    Long.parseLong(headers.firstValue("x-ratelimit-reset").orElseThrow().trim()));
        } catch (NoSuchElementException | NumberFormatException absent) {
            return null;
        }
    }

    private Duration retryDelay(HttpRequest request, int attempt, PolyzonException failure) {
        Duration delay = attempt < maxRetries ? retryDelay(attempt, failure) : null;
        LOG.log(Level.DEBUG, () -> "GET " + target(request) + " failed (attempt " + (attempt + 1) + "): "
                + failure.getMessage() + (delay == null ? "; not retrying" : "; retrying in " + delay.toMillis() + " ms"));
        return delay;
    }

    /** The wait before retry number {@code attempt + 1}, or {@code null} when not retryable. */
    static Duration retryDelay(int attempt, PolyzonException failure) {
        if (failure instanceof ApiException api) {
            boolean retryable = failure instanceof RateLimitException
                    ? "rate_limited".equals(api.code())
                    : api.status() == 500 || api.status() == 502 || api.status() == 503 || api.status() == 504;
            if (!retryable) {
                return null;
            }
            if (api.retryAfter().isPresent()) {
                Duration wait = api.retryAfter().get();
                return wait.compareTo(MAX_RETRY_AFTER) > 0 ? null : wait;
            }
        } else if (!(failure instanceof ApiConnectionException)) {
            return null;
        }
        long base = Math.min(8_000L, 500L << Math.min(attempt, 4));
        return Duration.ofMillis((long) (base * (0.5 + 0.5 * ThreadLocalRandom.current().nextDouble())));
    }

    private static String target(HttpRequest request) {
        URI uri = request.uri();
        return uri.getRawQuery() == null ? uri.getRawPath() : uri.getRawPath() + "?" + uri.getRawQuery();
    }

    // ---------------------------------------------------------------- batch

    private List<ModelResult> batch(List<ModelRef> refs, int concurrency, Lang lang) {
        List<ModelRef> list = List.copyOf(refs);
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be at least 1");
        }
        ModelResult[] results = new ModelResult[list.size()];
        AtomicInteger next = new AtomicInteger();
        AtomicReference<PolyzonException> stop = new AtomicReference<>();
        CompletableFuture<?>[] workers = new CompletableFuture<?>[Math.min(concurrency, list.size())];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = batchWorker(list, lang, results, next, stop);
        }
        try {
            await(CompletableFuture.allOf(workers));
        } catch (PolyzonException interrupted) {
            stop.compareAndSet(null, interrupted);
            throw interrupted;
        }
        return List.of(results);
    }

    /** Takes references one by one until none are left; at most one request in flight. */
    private CompletableFuture<Void> batchWorker(List<ModelRef> refs, Lang lang, ModelResult[] results,
            AtomicInteger next, AtomicReference<PolyzonException> stop) {
        while (true) {
            int index = next.getAndIncrement();
            if (index >= refs.size()) {
                return CompletableFuture.completedFuture(null);
            }
            ModelRef ref = refs.get(index);
            PolyzonException stopped = stop.get();
            if (stopped != null) {
                results[index] = new ModelResult(ref, null, stopped);
                continue;
            }
            CompletableFuture<Model> pending;
            try {
                pending = callAsync(modelPath(ref.source(), ref.id(), lang), Decode::model);
            } catch (RuntimeException invalid) {
                results[index] = new ModelResult(ref, null, invalid);
                continue;
            }
            return pending.handle((model, error) -> {
                if (error == null) {
                    results[index] = new ModelResult(ref, model, null);
                } else {
                    RuntimeException failure = transportFailure(error);
                    if (failure instanceof AuthenticationException
                            || failure instanceof PermissionDeniedException
                            || failure instanceof QuotaExceededException) {
                        stop.compareAndSet(null, (PolyzonException) failure);
                    }
                    results[index] = new ModelResult(ref, null, failure);
                }
                return null;
            }).thenComposeAsync(done -> batchWorker(refs, lang, results, next, stop), asyncExecutor);
        }
    }

    // ---------------------------------------------------------------- validation

    static String checkApiKey(String key) {
        if (key.isEmpty() || key.length() > 512) {
            throw new IllegalArgumentException("API key must be 1 to 512 characters long");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                throw new IllegalArgumentException(
                        "API key must contain only visible ASCII characters (no spaces or line breaks)");
            }
        }
        return key;
    }

    static String checkBaseUrl(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            // The input is not echoed: it could contain credentials.
            throw new IllegalArgumentException("baseUrl is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (uri.isOpaque() || host == null || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new IllegalArgumentException("baseUrl must be an absolute https URL such as " + DEFAULT_BASE_URL);
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not contain credentials, a query or a fragment");
        }
        if (scheme.equals("http") && !isLoopback(host)) {
            throw new IllegalArgumentException(
                    "baseUrl must use https; plain http is allowed only for localhost, 127.0.0.1 and [::1]");
        }
        String url = uri.toString();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static boolean isLoopback(String host) {
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
    }

    private static Duration checkPositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int checkMaxRetries(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        return maxRetries;
    }

    static String mask(String key) {
        return key.length() > 12 ? key.substring(0, 8) + "…" : "…";
    }

    static String defaultUserAgent() {
        String agent = "polyzon-java/" + VERSION + " (Java " + Runtime.version() + "; "
                + System.getProperty("os.name") + "/" + System.getProperty("os.arch") + ")";
        return agent.replaceAll("[^\\x20-\\x7E]", "_");
    }

    private static HttpClient newHttpClient(Builder builder) {
        HttpClient.Builder http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(builder.connectTimeout != null ? builder.connectTimeout : DEFAULT_CONNECT_TIMEOUT);
        if (builder.proxy != null) {
            URI proxy = builder.proxy;
            int port = proxy.getPort() == -1 ? 80 : proxy.getPort();
            http.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(proxy.getHost(), port)));
        } else if (builder.proxySelector != null) {
            http.proxy(builder.proxySelector);
        }
        if (builder.executor != null) {
            http.executor(builder.executor);
        }
        return http.build();
    }

    private static String percentDecode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /** {@code Basic …} from the proxy URL user info (percent-decoded), or {@code null} without it. */
    static String basicCredentials(URI proxy) {
        String userInfo = proxy.getRawUserInfo();
        if (userInfo == null) {
            return null;
        }
        int colon = userInfo.indexOf(':');
        String user = colon < 0 ? userInfo : userInfo.substring(0, colon);
        String password = colon < 0 ? "" : userInfo.substring(colon + 1);
        String pair = percentDecode(user) + ":" + percentDecode(password);
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    /** Collects the body up to 8 MiB; beyond that it cancels the read and yields {@code null}. */
    private static final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletableFuture<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + (long) buffer.remaining() > MAX_BODY_BYTES) {
                    subscription.cancel();
                    result.complete(null);
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }

    // ---------------------------------------------------------------- builder

    /**
     * Builder of {@link PolyzonClient}; not thread-safe. Its {@code toString} masks the key.
     *
     * <p><b>Proxies.</b> By default the JDK's default {@link ProxySelector} is used: it honours the
     * JVM system properties {@code https.proxyHost}, {@code https.proxyPort},
     * {@code http.proxyHost}, {@code http.proxyPort}, {@code http.nonProxyHosts} (loopback hosts
     * are excluded by default) and {@code java.net.useSystemProxies}. The JVM does <i>not</i> read
     * the {@code HTTPS_PROXY} environment variable; use {@link #proxy(URI)} for an explicit proxy
     * and {@code proxySelector(HttpClient.Builder.NO_PROXY)} to disable proxies.
     */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private Duration timeout = DEFAULT_TIMEOUT;
        private Duration connectTimeout;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private URI proxy;
        private ProxySelector proxySelector;
        private HttpClient httpClient;
        private Executor executor;
        private String userAgentSuffix;
        private UnaryOperator<String> env = System::getenv;
        private Function<Duration, Executor> delayer;

        private Builder() {
        }

        /**
         * Sets the API key ({@code pzk_…}, issued at https://polyzon.org/account/). Defaults to
         * the {@code POLYZON_API_KEY} environment variable.
         *
         * @param apiKey the key: 1 to 512 visible ASCII characters
         * @return this builder
         * @throws IllegalArgumentException if the key is empty, too long or contains spaces,
         *     line breaks or non-ASCII characters
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = checkApiKey(Objects.requireNonNull(apiKey, "apiKey"));
            return this;
        }

        /**
         * Sets the API address. Defaults to {@code POLYZON_BASE_URL} or
         * {@value PolyzonClient#DEFAULT_BASE_URL}.
         * Must be {@code https}; plain {@code http} is accepted only for {@code localhost},
         * {@code 127.0.0.1} and {@code [::1]}. Credentials, query and fragment are rejected; a
         * trailing slash is removed.
         *
         * @param baseUrl the base URL
         * @return this builder
         * @throws IllegalArgumentException if the URL is not acceptable
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = checkBaseUrl(Objects.requireNonNull(baseUrl, "baseUrl"));
            return this;
        }

        /**
         * Sets the total time allowed for one attempt, from sending the request to reading the
         * whole body. Defaults to 30 seconds.
         *
         * @param timeout a positive duration
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = checkPositive(timeout, "timeout");
            return this;
        }

        /**
         * Sets the time allowed to open a connection. Defaults to 10 seconds.
         *
         * @param connectTimeout a positive duration
         * @return this builder
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = checkPositive(connectTimeout, "connectTimeout");
            return this;
        }

        /**
         * Sets how many times a failed request is retried (see {@link PolyzonClient}). Defaults
         * to 2; 0 disables retries.
         *
         * @param maxRetries retries after the first attempt, not negative
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = checkMaxRetries(maxRetries);
            return this;
        }

        /**
         * Routes requests through an HTTP proxy: {@code http://[user:password@]host[:port]}
         * (port 80 when omitted; user and password are percent-decoded). HTTPS requests are
         * tunnelled with {@code CONNECT}, so the proxy never sees the API key. SOCKS and TLS
         * connections to the proxy itself are not supported by the JDK {@link HttpClient}; use
         * {@link #httpClient(HttpClient)} with your own setup for those.
         *
         * <p>Credentials are sent up front as Basic {@code Proxy-Authorization}, only to this proxy
         * (the JDK strips the header from the tunnelled request). Note: the JDK disables Basic
         * authentication for HTTPS tunnels by default; to allow it, start the JVM with
         * {@code -Djdk.http.auth.tunneling.disabledSchemes=""}. A {@code 407} answer surfaces as an
         * {@link ApiException} with status 407 and is not retried.
         *
         * <p>Replaces a {@link #proxySelector(ProxySelector)} set earlier.
         *
         * @param proxy the proxy URL
         * @return this builder
         * @throws IllegalArgumentException if the URL is not {@code http://host[:port]} with an
         *     optional user info
         */
        public Builder proxy(URI proxy) {
            Objects.requireNonNull(proxy, "proxy");
            String path = proxy.getRawPath();
            if (proxy.isOpaque() || !"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null
                    || proxy.getRawQuery() != null || proxy.getRawFragment() != null
                    || !(path == null || path.isEmpty() || path.equals("/"))) {
                throw new IllegalArgumentException("proxy must look like http://[user:password@]host:port");
            }
            this.proxy = proxy;
            this.proxySelector = null;
            return this;
        }

        /**
         * Chooses proxies with a {@link ProxySelector}, for example
         * {@code HttpClient.Builder.NO_PROXY} to ignore the JVM proxy settings. Replaces a
         * {@link #proxy(URI)} set earlier.
         *
         * @param proxySelector the selector
         * @return this builder
         */
        public Builder proxySelector(ProxySelector proxySelector) {
            this.proxySelector = Objects.requireNonNull(proxySelector, "proxySelector");
            this.proxy = null;
            return this;
        }

        /**
         * Uses your own {@link HttpClient}, for a custom {@link javax.net.ssl.SSLContext}
         * (corporate CA, mTLS), SOCKS proxy or tests. It must not follow redirects. It is not
         * closed by {@link PolyzonClient#close()}. Cannot be combined with {@code proxy},
         * {@code proxySelector} or {@code connectTimeout}.
         *
         * @param httpClient the client, with {@code followRedirects(Redirect.NEVER)}
         * @return this builder
         * @throws IllegalArgumentException if the client follows redirects
         */
        public Builder httpClient(HttpClient httpClient) {
            Objects.requireNonNull(httpClient, "httpClient");
            if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
                throw new IllegalArgumentException(
                        "httpClient must use Redirect.NEVER: following a redirect could leak the API key");
            }
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Sets the executor for the built-in {@link HttpClient}'s asynchronous work, for retry
         * scheduling and for {@code getModels}. Defaults to the {@link CompletableFuture} default
         * executor (and the {@code HttpClient}'s own pool).
         *
         * @param executor the executor
         * @return this builder
         */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /**
         * Appends a product token to the default {@code User-Agent}
         * ({@code polyzon-java/<version> (Java <version>; <os>/<arch>)}), for example
         * {@code "my-bot/2.1"}.
         *
         * @param suffix printable ASCII, at most 256 characters
         * @return this builder
         * @throws IllegalArgumentException if the suffix is blank, too long or not printable ASCII
         */
        public Builder userAgent(String suffix) {
            Objects.requireNonNull(suffix, "suffix");
            if (suffix.isBlank() || suffix.length() > 256 || !HEADER_SAFE.matcher(suffix).matches()) {
                throw new IllegalArgumentException("userAgent must be 1 to 256 printable ASCII characters");
            }
            this.userAgentSuffix = suffix;
            return this;
        }

        /** Test hook: where environment variables come from. */
        Builder env(UnaryOperator<String> env) {
            this.env = env;
            return this;
        }

        /** Test hook: the executor that runs a task after a retry delay. */
        Builder delayer(Function<Duration, Executor> delayer) {
            this.delayer = delayer;
            return this;
        }

        /**
         * Builds the client.
         *
         * @return a new client
         * @throws IllegalStateException if no API key is set and {@code POLYZON_API_KEY} is
         *     empty, or {@code httpClient} is combined with {@code proxy}, {@code proxySelector}
         *     or {@code connectTimeout}
         * @throws IllegalArgumentException if an environment variable holds an invalid value
         */
        public PolyzonClient build() {
            return new PolyzonClient(this);
        }

        /** Returns a description with the API key and proxy credentials masked. */
        @Override
        public String toString() {
            String proxyText = proxy == null ? "null"
                    : "http://" + proxy.getHost() + ":" + (proxy.getPort() == -1 ? 80 : proxy.getPort());
            return "PolyzonClient.Builder[apiKey=" + (apiKey == null ? "null" : mask(apiKey))
                    + ", baseUrl=" + baseUrl + ", timeout=" + timeout + ", maxRetries=" + maxRetries
                    + ", proxy=" + proxyText + "]";
        }
    }
}

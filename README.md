# Polyzon Java SDK

The official Java client for the [Polyzon public API](https://api.polyzon.org). Polyzon indexes 3D-printable models from more than twenty sites (Thingiverse, Printables, MakerWorld, Cults, MyMiniFactory and others); the API lets you search that catalogue and fetch model cards. The SDK has **no dependencies outside the JDK**, is thread-safe, retries transient failures, and never leaks your token.

[Русская версия](README.ru.md)

## Install

Requires **Java 17 or later**. Maven:

```xml
<dependency>
  <groupId>org.polyzon</groupId>
  <artifactId>polyzon-java</artifactId>
  <version>1.0.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("org.polyzon:polyzon-java:1.0.0")
```

The jar is a named module, `org.polyzon` (`requires org.polyzon;`), and works on the class path too.

## Quick start

```java
import org.polyzon.*;

try (PolyzonClient polyzon = PolyzonClient.fromEnv()) {        // reads POLYZON_API_KEY
    SearchPage page = polyzon.search(SearchParams.builder("дракон").free(true).build());
    for (SearchItem item : page.items()) {
        System.out.println(item.title() + " — " + item.url());
    }
}
```

## Authentication

Create a personal token at [polyzon.org/account](https://polyzon.org/account/) (section «API»). Tokens look like `pzk_…`. Pass it explicitly or through the environment:

```java
PolyzonClient polyzon = PolyzonClient.builder().apiKey(System.getenv("POLYZON_API_KEY")).build();
PolyzonClient polyzon = PolyzonClient.fromEnv();   // POLYZON_API_KEY, optional POLYZON_BASE_URL
```

A missing key fails at construction with `IllegalStateException`; a malformed one (empty, longer than 512 characters, spaces, line breaks, non-ASCII) with `IllegalArgumentException`.

## Methods

| Method | Endpoint | Daily quota |
| ------ | -------- | ----------- |
| `search(SearchParams)` / `searchAsync` | `GET /v1/search` | 1 request |
| `searchAll(SearchParams)` | `GET /v1/search`, page after page | 1 request per page |
| `getModel(source, id[, lang])` / `getModelAsync` | `GET /v1/models/{source}/{id}` | 1 request |
| `getModels(refs[, concurrency[, lang]])` | `GET /v1/models/…` per ref | 1 request per model |
| `me()` / `meAsync` | `GET /v1/me` | free |
| `limits()` / `limitsAsync` | `GET /v1/limits` | free |

### Search

```java
SearchParams params = SearchParams.builder("дракон")     // Russian or English, up to 200 characters
        .page(1)                                         // 1..100
        .size(50)                                        // 1..50; page × size ≤ 2400
        .sort(Sort.NEWEST)                               // RELEVANT, POPULAR, NEWEST, LIKES, MAKES, DOWNLOADS
        .free(true)                                      // only free models
        .sources(Sources.THINGIVERSE, Sources.PRINTABLES)
        .formats("stl", "3mf")
        .licenses(Licenses.CC0, Licenses.CC_BY)
        .lang(Lang.EN)                                   // language of titles and tags
        .build();
SearchPage page = polyzon.search(params);
page.total();          // matches; page.totalRelation() is "eq" (exact) or "gte" (lower bound)
page.hasMore();        // a next page exists within the 2400-model window
page.partial();        // part of the catalogue did not answer
page.correctedQuery(); // spelling correction that was searched instead, or null
page.items();          // List<SearchItem>: source, id, title, url, thumbnail, creator, license,
                       // price, formats, tags, stats (likes, comments, makes, collects), publishedAt
```

Unset parameters are not sent, so server defaults apply. `Sources` and `Licenses` hold the ids known at release time; any other string is accepted and passed through, so new sites work without an SDK update. The server enforces the upper limits and answers `400` beyond them — and a rejected request still costs quota.

### Model card

```java
Model model = polyzon.getModel("thingiverse", "tv-1234567");
Model english = polyzon.getModel("thingiverse", "tv-1234567", Lang.EN);
model.description(); model.images(); model.license(); // License(id, raw) or null
model.stats().downloads(); model.copies();           // the same model on other sites, as ModelRefs
```

`source` must match `[a-z0-9]{1,32}` and `id` `[A-Za-z0-9._-]{1,255}` (and not `.` or `..`); anything else throws `IllegalArgumentException` before a request is made. A `GoneException` (410) means the right holder had the model removed — delete your copy too.

### Account and limits

```java
Me me = polyzon.me();            // me.account().displayName(), me.token().name(), me.token().prefix()
Limits limits = polyzon.limits();
limits.perMinute().remaining();  // window of 20 requests a minute
limits.daily().remaining();      // 200 search/model requests per UTC day
limits.daily().resetAt();        // Unix seconds
```

## Pagination

`searchAll` returns a lazy `Stream<SearchItem>`. It starts at `params.page()` (default 1), fetches the next page only when the stream needs more items, and stops when `hasMore` is false, a page is empty, or after page 100 (the last page the server serves). **Every page costs one request of the daily quota**, so bound the stream:

```java
List<SearchItem> first300 = polyzon.searchAll(SearchParams.builder("vase").size(50).build())
        .limit(300)       // 6 requests
        .toList();
```

Request errors are thrown from the stream's terminal operation.

## Errors

Every failure is an unchecked `org.polyzon.errors.PolyzonException`:

| Exception | When |
| --------- | ---- |
| `ApiConnectionException` | network, DNS, TLS, proxy failure, interrupted thread |
| `ApiTimeoutException` (extends `ApiConnectionException`) | an attempt exceeded `timeout` |
| `InvalidResponseException` | a 2xx answer that is not a JSON object, not UTF-8, or larger than 8 MiB |
| `ApiException` | any other HTTP error, including 3xx (redirects are never followed) |
| `BadRequestException` | 400 `bad_request` (see `param()`), `page_too_deep` |
| `AuthenticationException` | 401 `unauthorized`, `invalid_token` |
| `PermissionDeniedException` | 403 `account_action_required` — accept the terms at polyzon.org/account |
| `NotFoundException` | 404 `not_found` |
| `GoneException` | 410 `gone` — removed at the right holder's request |
| `RateLimitException` | 429 `rate_limited` |
| `QuotaExceededException` (extends `RateLimitException`) | 429 `quota_exceeded` |
| `InternalServerException` | 5xx `internal`, `unavailable`, edge errors |

`ApiException` exposes `status()`, `code()` (null for non-JSON bodies), `title()`, `detail()`, `param()`, `requestId()`, `retryAfter()` (`Optional<Duration>`), `headers()` and `body()`. Its message reads `429 rate_limited: Не больше 20 запросов в минуту. (request id 6f1c…)`.

```java
try {
    polyzon.getModel("thingiverse", "tv-1");
} catch (NotFoundException | GoneException e) {
    // drop it
} catch (QuotaExceededException e) {
    Duration wait = e.retryAfter().orElse(Duration.ofHours(1));   // until 00:00 UTC
} catch (PolyzonException e) {
    log.warn("Polyzon failed: {}", e.getMessage());              // never contains the token
}
```

Invalid arguments throw `IllegalArgumentException` / `NullPointerException`, not `PolyzonException`.

## Retries

Up to `maxRetries` (default 2) retries on connection errors, timeouts, HTTP 500/502/503/504 and 429 `rate_limited`. The wait is the server's `Retry-After` (seconds or HTTP date); if the server asks for more than 60 seconds the error is thrown at once. Without `Retry-After` the wait is `min(8 s, 0.5 s × 2^attempt)` with random jitter (×0.5–1.0). `quota_exceeded` and other 4xx are never retried. Async calls wait on a scheduler, not on a blocked thread.

```java
PolyzonClient polyzon = PolyzonClient.builder().maxRetries(5).build();
PolyzonClient noRetries = polyzon.withMaxRetries(0);   // shares the connection pool
```

## Timeouts

`timeout` (default 30 s) bounds each attempt from sending the request to reading the whole body; `connectTimeout` (default 10 s) bounds opening a connection.

```java
PolyzonClient polyzon = PolyzonClient.builder()
        .timeout(Duration.ofSeconds(10))
        .connectTimeout(Duration.ofSeconds(3))
        .build();
PolyzonClient patient = polyzon.withTimeout(Duration.ofMinutes(1));
```

## Proxy

By default the client uses the JVM's default `ProxySelector`, which honours the system properties `https.proxyHost`, `https.proxyPort`, `http.proxyHost`, `http.proxyPort`, `http.nonProxyHosts` (loopback is excluded by default) and `java.net.useSystemProxies=true`. **The JVM does not read the `HTTPS_PROXY` environment variable.** Set a proxy explicitly:

```java
PolyzonClient polyzon = PolyzonClient.builder()
        .proxy(URI.create("http://user:password@proxy.example.com:3128"))   // percent-encode special characters
        .build();
```

* Only HTTP proxies are supported (that is what `java.net.http.HttpClient` supports). HTTPS requests are tunnelled with `CONNECT`, so TLS runs end to end and the proxy never sees your token.
* Credentials from the URL are sent up front as Basic `Proxy-Authorization`, only to that proxy: the JDK strips the header from the tunnelled request, so the API never sees it. They never appear in `toString`, logs or errors. A `407` from the proxy surfaces as `ApiException` with status 407 and is not retried.
* The SDK deliberately does not use a `java.net.Authenticator`: with one set, JDK 17 and 21 silently drop the `Authorization` header, and every call would fail with `401 unauthorized`.
* The JDK disables Basic authentication for HTTPS tunnels by default. To allow it, start the JVM with `-Djdk.http.auth.tunneling.disabledSchemes=""` (set it before the first request).
* `proxySelector(HttpClient.Builder.NO_PROXY)` ignores the JVM proxy settings; `proxySelector(yourSelector)` plugs in any selector.
* SOCKS proxies or a TLS connection to the proxy: build your own `HttpClient` (see below).

## Concurrency and batches

A `PolyzonClient` is immutable and safe to share between threads — create one and reuse it. Requests share the pooled connections of the underlying `java.net.http.HttpClient` (HTTP/2 multiplexing over TLS, keep-alive otherwise; the pool can be tuned with the JDK properties `jdk.httpclient.connectionPoolSize` and `jdk.httpclient.keepalive.timeout`).

`getModels` fetches many models with bounded parallelism (default 4 in flight) and returns one `ModelResult` per ref **in input order**, holding either the model or the error — it does not throw for per-item failures:

```java
List<ModelRef> refs = page.items().stream().map(SearchItem::ref).toList();
for (ModelResult result : polyzon.getModels(refs, 4, Lang.EN)) {
    if (result.isSuccess()) {
        System.out.println(result.model().title());
    } else {
        System.out.println(result.ref() + ": " + result.error().getMessage());
    }
}
```

After an `AuthenticationException`, `PermissionDeniedException` or `QuotaExceededException`, the refs not yet started get that same error without sending requests.

## Async

`searchAsync`, `getModelAsync`, `meAsync` and `limitsAsync` return `CompletableFuture`s that fail with the same exceptions. Cancelling a future aborts its request. Pass `executor(...)` to run callbacks and retries on your own executor.

```java
CompletableFuture<SearchPage> a = polyzon.searchAsync(SearchParams.builder("cat").build());
CompletableFuture<SearchPage> b = polyzon.searchAsync(SearchParams.builder("dog").build());
CompletableFuture.allOf(a, b).join();
```

## Logging

The SDK logs each attempt at `DEBUG` through `System.Logger` named `org.polyzon` (method, path with query, status or error, attempt number, retry delay). Headers — and therefore the token — are never logged. With `java.util.logging`:

```properties
org.polyzon.level = FINE
java.util.logging.ConsoleHandler.level = FINE
```

With SLF4J/Logback, route `System.Logger` through a bridge such as `slf4j-jdk-platform-logging`.

## Rate limits and quota

Limits are per account, not per token: 20 requests a minute (every method) and 200 search and model requests per UTC day. The server counts a request before validating it, so even a `400` costs quota. Each result carries metadata excluded from `equals`/`toString`:

```java
SearchPage page = polyzon.search(params);
page.meta().requestId();               // X-Request-ID, quote it to support
RateLimit daily = page.meta().rateLimit();   // limit, remaining, resetAt (Unix seconds); null if absent
```

## Custom HttpClient, certificates and tests

Pass your own `HttpClient` for a corporate CA, mTLS, SOCKS or tests. It must not follow redirects and is not closed by the SDK:

```java
SSLContext tls = ...;   // trust your corporate CA
HttpClient http = HttpClient.newBuilder()
        .sslContext(tls)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
PolyzonClient polyzon = PolyzonClient.builder().httpClient(http).build();
```

The default trust store of the JVM also works (`-Djavax.net.ssl.trustStore=…`). TLS verification cannot be switched off. For local tests, `baseUrl("http://localhost:8080")` is accepted; plain `http` to any other host is rejected.

## Closing

`PolyzonClient` implements `AutoCloseable`. `close()` makes later calls fail and, on Java 21+, shuts the built-in `HttpClient` down once running requests finish. Long-lived applications usually keep one client for their whole lifetime.

## Security

* **Never ship your token to browsers or mobile apps; keep it on your server.** Anyone holding the token spends your quota.
* The token is validated at construction (no header injection), masked in `toString` (`pzk_Q7xR…`), and never logged or included in exceptions.
* Only `https` base URLs are accepted (plain `http` only for `localhost`, `127.0.0.1`, `[::1]`); redirects are never followed, so the token cannot leak to another host.
* Responses are capped at 8 MiB; JSON is parsed by a strict built-in parser with a nesting limit of 64.

Report vulnerabilities to security@polyzon.org — see [SECURITY.md](SECURITY.md).

## Versioning

The SDK follows [Semantic Versioning](https://semver.org/). Fields may be added to result records in minor versions (their canonical constructors may then change); do not construct result records yourself in code that must survive minor upgrades. See [CHANGELOG.md](CHANGELOG.md).

## Requirements

Java 17 or later (tested on 17, 21 and 25). No other dependencies.

## Building

```sh
./mvnw verify   # compile with -Werror, tests, coverage ≥ 90 %, javadoc and sources jars
```

## Links

* API documentation and sandbox: https://api.polyzon.org
* Tokens: https://polyzon.org/account/
* Issues: https://github.com/PolyzonOrg/polyzon-java/issues

## License

[MIT](LICENSE) © 2026 Polyzon

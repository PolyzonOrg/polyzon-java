# Polyzon Java SDK

Официальный Java-клиент [публичного API Polyzon](https://api.polyzon.org). Polyzon собирает 3D-модели для печати с двадцати с лишним площадок (Thingiverse, Printables, MakerWorld, Cults, MyMiniFactory и других); API даёт искать по этому каталогу и получать карточки моделей. У SDK **нет зависимостей, кроме JDK**, клиент потокобезопасен, сам повторяет временные сбои и никогда не раскрывает ваш токен.

[English version](README.md)

## Установка

Нужна **Java 17 или новее**. Maven:

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

Jar — именованный модуль `org.polyzon` (`requires org.polyzon;`), работает и на classpath.

## Быстрый старт

```java
import org.polyzon.*;

try (PolyzonClient polyzon = PolyzonClient.fromEnv()) {        // читает POLYZON_API_KEY
    SearchPage page = polyzon.search(SearchParams.builder("дракон").free(true).build());
    for (SearchItem item : page.items()) {
        System.out.println(item.title() + " — " + item.url());
    }
}
```

## Авторизация

Создайте личный токен в [polyzon.org/account](https://polyzon.org/account/) (раздел «API»). Токены выглядят как `pzk_…`. Передайте его явно или через окружение:

```java
PolyzonClient polyzon = PolyzonClient.builder().apiKey(System.getenv("POLYZON_API_KEY")).build();
PolyzonClient polyzon = PolyzonClient.fromEnv();   // POLYZON_API_KEY и, если нужно, POLYZON_BASE_URL
```

Без ключа конструктор бросает `IllegalStateException`, с испорченным ключом (пустой, длиннее 512 символов, пробелы, переводы строк, не-ASCII) — `IllegalArgumentException`.

## Методы

| Метод | Адрес | Суточная квота |
| ----- | ----- | -------------- |
| `search(SearchParams)` / `searchAsync` | `GET /v1/search` | 1 запрос |
| `searchAll(SearchParams)` | `GET /v1/search`, страница за страницей | 1 запрос на страницу |
| `getModel(source, id[, lang])` / `getModelAsync` | `GET /v1/models/{source}/{id}` | 1 запрос |
| `getModels(refs[, concurrency[, lang]])` | `GET /v1/models/…` на каждую ссылку | 1 запрос на модель |
| `me()` / `meAsync` | `GET /v1/me` | не тратит |
| `limits()` / `limitsAsync` | `GET /v1/limits` | не тратит |

### Поиск

```java
SearchParams params = SearchParams.builder("дракон")     // по-русски или по-английски, до 200 символов
        .page(1)                                         // 1..100
        .size(50)                                        // 1..50; page × size ≤ 2400
        .sort(Sort.NEWEST)                               // RELEVANT, POPULAR, NEWEST, LIKES, MAKES, DOWNLOADS
        .free(true)                                      // только бесплатные
        .sources(Sources.THINGIVERSE, Sources.PRINTABLES)
        .formats("stl", "3mf")
        .licenses(Licenses.CC0, Licenses.CC_BY)
        .lang(Lang.EN)                                   // язык названий и тегов
        .build();
SearchPage page = polyzon.search(params);
page.total();          // сколько найдено; page.totalRelation() — "eq" (точно) или "gte" (не меньше)
page.hasMore();        // есть ли следующая страница в пределах 2400 моделей
page.partial();        // часть каталога не ответила, выдача неполная
page.correctedQuery(); // исправленный запрос, по которому искали, или null
page.items();          // List<SearchItem>: source, id, title, url, thumbnail, creator, license,
                       // price, formats, tags, stats (likes, comments, makes, collects), publishedAt
```

Незаданные параметры не отправляются — действуют значения сервера по умолчанию. В `Sources` и `Licenses` — идентификаторы, известные на момент выпуска; любая другая строка тоже принимается и уходит как есть, так что новые площадки работают без обновления SDK. Верхние границы проверяет сервер и отвечает `400`, а отклонённый запрос всё равно тратит квоту.

### Карточка модели

```java
Model model = polyzon.getModel("thingiverse", "tv-1234567");
Model english = polyzon.getModel("thingiverse", "tv-1234567", Lang.EN);
model.description(); model.images(); model.license(); // License(id, raw) или null
model.stats().downloads(); model.copies();           // та же модель на других площадках, как ModelRef
```

`source` должен подходить под `[a-z0-9]{1,32}`, а `id` — под `[A-Za-z0-9._-]{1,255}` (и не быть `.` или `..`); иначе `IllegalArgumentException` ещё до запроса. `GoneException` (410) значит, что модель удалена по требованию правообладателя, — удалите её и у себя.

### Аккаунт и лимиты

```java
Me me = polyzon.me();            // me.account().displayName(), me.token().name(), me.token().prefix()
Limits limits = polyzon.limits();
limits.perMinute().remaining();  // окно на 20 запросов в минуту
limits.daily().remaining();      // 200 запросов поиска и моделей за сутки UTC
limits.daily().resetAt();        // секунды Unix
```

## Страницы

`searchAll` возвращает ленивый `Stream<SearchItem>`. Он начинает с `params.page()` (по умолчанию 1), запрашивает следующую страницу, только когда потоку нужны новые элементы, и останавливается, когда `hasMore` ложно, страница пуста или пройдена страница 100 (последняя, которую отдаёт сервер). **Каждая страница тратит один запрос суточной квоты**, поэтому ограничивайте поток:

```java
List<SearchItem> first300 = polyzon.searchAll(SearchParams.builder("ваза").size(50).build())
        .limit(300)       // 6 запросов
        .toList();
```

Ошибки запросов бросаются из терминальной операции потока.

## Ошибки

Все сбои — непроверяемые `org.polyzon.errors.PolyzonException`:

| Исключение | Когда |
| ---------- | ----- |
| `ApiConnectionException` | сеть, DNS, TLS, сбой прокси, прерванный поток |
| `ApiTimeoutException` (наследник `ApiConnectionException`) | попытка дольше `timeout` |
| `InvalidResponseException` | ответ 2xx — не JSON-объект, не UTF-8 или больше 8 МиБ |
| `ApiException` | прочие HTTP-ошибки, в том числе 3xx (редиректы не выполняются) |
| `BadRequestException` | 400 `bad_request` (см. `param()`), `page_too_deep` |
| `AuthenticationException` | 401 `unauthorized`, `invalid_token` |
| `PermissionDeniedException` | 403 `account_action_required` — примите условия в polyzon.org/account |
| `NotFoundException` | 404 `not_found` |
| `GoneException` | 410 `gone` — удалена по требованию правообладателя |
| `RateLimitException` | 429 `rate_limited` |
| `QuotaExceededException` (наследник `RateLimitException`) | 429 `quota_exceeded` |
| `InternalServerException` | 5xx `internal`, `unavailable`, ошибки пограничного узла |

У `ApiException` есть `status()`, `code()` (null, если тело не JSON), `title()`, `detail()`, `param()`, `requestId()`, `retryAfter()` (`Optional<Duration>`), `headers()` и `body()`. Сообщение выглядит так: `429 rate_limited: Не больше 20 запросов в минуту. (request id 6f1c…)`.

```java
try {
    polyzon.getModel("thingiverse", "tv-1");
} catch (NotFoundException | GoneException e) {
    // убрать у себя
} catch (QuotaExceededException e) {
    Duration wait = e.retryAfter().orElse(Duration.ofHours(1));   // до 00:00 UTC
} catch (PolyzonException e) {
    log.warn("Polyzon: {}", e.getMessage());                     // токена в сообщении нет
}
```

Неверные аргументы бросают `IllegalArgumentException` / `NullPointerException`, а не `PolyzonException`.

## Повторы

До `maxRetries` (по умолчанию 2) повторов при сетевых ошибках, таймаутах, HTTP 500/502/503/504 и 429 `rate_limited`. Пауза — `Retry-After` сервера (секунды или HTTP-дата); если сервер просит больше 60 секунд, ошибка бросается сразу. Без `Retry-After` пауза `min(8 с, 0,5 с × 2^попытка)` со случайным множителем 0,5–1,0. `quota_exceeded` и прочие 4xx не повторяются. Асинхронные вызовы ждут в планировщике, а не в заблокированном потоке.

```java
PolyzonClient polyzon = PolyzonClient.builder().maxRetries(5).build();
PolyzonClient noRetries = polyzon.withMaxRetries(0);   // общий пул соединений
```

## Таймауты

`timeout` (по умолчанию 30 с) ограничивает одну попытку — от отправки запроса до чтения всего тела; `connectTimeout` (по умолчанию 10 с) — установку соединения.

```java
PolyzonClient polyzon = PolyzonClient.builder()
        .timeout(Duration.ofSeconds(10))
        .connectTimeout(Duration.ofSeconds(3))
        .build();
PolyzonClient patient = polyzon.withTimeout(Duration.ofMinutes(1));
```

## Прокси

По умолчанию клиент берёт стандартный `ProxySelector` JVM: он учитывает системные свойства `https.proxyHost`, `https.proxyPort`, `http.proxyHost`, `http.proxyPort`, `http.nonProxyHosts` (локальные адреса исключены по умолчанию) и `java.net.useSystemProxies=true`. **Переменную окружения `HTTPS_PROXY` JVM не читает.** Прокси можно задать явно:

```java
PolyzonClient polyzon = PolyzonClient.builder()
        .proxy(URI.create("http://user:password@proxy.example.com:3128"))   // спецсимволы — в %-кодировке
        .build();
```

* Поддерживаются только HTTP-прокси (это то, что умеет `java.net.http.HttpClient`). HTTPS-запросы идут через туннель `CONNECT`: TLS устанавливается напрямую с API, и прокси не видит токен.
* Логин и пароль из адреса отправляются сразу, заголовком Basic `Proxy-Authorization`, и только этому прокси: из запроса внутри туннеля JDK этот заголовок убирает, так что API его не видит. В `toString`, логи и ошибки они не попадают. Ответ прокси `407` превращается в `ApiException` со статусом 407 и не повторяется.
* `java.net.Authenticator` SDK намеренно не использует: если он задан, JDK 17 и 21 молча выбрасывают заголовок `Authorization`, и любой вызов падал бы с `401 unauthorized`.
* JDK по умолчанию запрещает Basic-авторизацию для HTTPS-туннелей. Чтобы разрешить, запустите JVM с `-Djdk.http.auth.tunneling.disabledSchemes=""` (до первого запроса).
* `proxySelector(HttpClient.Builder.NO_PROXY)` игнорирует настройки прокси JVM; `proxySelector(свой)` подключает любой селектор.
* SOCKS-прокси или TLS до самого прокси — через собственный `HttpClient` (см. ниже).

## Многопоточность и пакетная загрузка

`PolyzonClient` неизменяем и безопасен для использования из многих потоков — создайте один и переиспользуйте. Запросы делят пул соединений `java.net.http.HttpClient` (мультиплексирование HTTP/2 поверх TLS, иначе keep-alive; пул настраивается свойствами JDK `jdk.httpclient.connectionPoolSize` и `jdk.httpclient.keepalive.timeout`).

`getModels` загружает много моделей с ограниченным параллелизмом (по умолчанию 4 запроса одновременно) и возвращает по одному `ModelResult` на ссылку **в исходном порядке** — с моделью или с ошибкой; из-за отдельных сбоев метод не бросает исключение:

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

После `AuthenticationException`, `PermissionDeniedException` или `QuotaExceededException` ещё не начатые ссылки получают ту же ошибку без запросов.

## Асинхронность

`searchAsync`, `getModelAsync`, `meAsync` и `limitsAsync` возвращают `CompletableFuture`, которые завершаются теми же исключениями. Отмена future прерывает запрос. Через `executor(...)` колбэки и повторы можно перенести на свой пул.

```java
CompletableFuture<SearchPage> a = polyzon.searchAsync(SearchParams.builder("кот").build());
CompletableFuture<SearchPage> b = polyzon.searchAsync(SearchParams.builder("собака").build());
CompletableFuture.allOf(a, b).join();
```

## Логирование

SDK пишет каждую попытку на уровне `DEBUG` в `System.Logger` с именем `org.polyzon`: метод, путь с запросом, статус или ошибка, номер попытки, пауза до повтора. Заголовки — а значит и токен — не логируются. Для `java.util.logging`:

```properties
org.polyzon.level = FINE
java.util.logging.ConsoleHandler.level = FINE
```

Для SLF4J/Logback подключите мост `System.Logger`, например `slf4j-jdk-platform-logging`.

## Лимиты и квота

Лимиты считаются на аккаунт, а не на токен: 20 запросов в минуту (любые методы) и 200 запросов поиска и моделей за сутки UTC. Сервер учитывает запрос до проверки параметров, поэтому даже `400` тратит квоту. Каждый результат несёт метаданные, которые не участвуют в `equals`/`toString`:

```java
SearchPage page = polyzon.search(params);
page.meta().requestId();                     // X-Request-ID, назовите его поддержке
RateLimit daily = page.meta().rateLimit();   // limit, remaining, resetAt (секунды Unix); null, если нет
```

## Свой HttpClient, сертификаты и тесты

Собственный `HttpClient` пригодится для корпоративного CA, mTLS, SOCKS или тестов. Он не должен выполнять редиректы, и SDK его не закрывает:

```java
SSLContext tls = ...;   // доверять корпоративному CA
HttpClient http = HttpClient.newBuilder()
        .sslContext(tls)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
PolyzonClient polyzon = PolyzonClient.builder().httpClient(http).build();
```

Подходит и стандартное хранилище сертификатов JVM (`-Djavax.net.ssl.trustStore=…`). Отключить проверку TLS нельзя. Для локальных тестов принимается `baseUrl("http://localhost:8080")`; обычный `http` к другим хостам запрещён.

## Закрытие

`PolyzonClient` реализует `AutoCloseable`. После `close()` вызовы бросают исключение, а на Java 21+ встроенный `HttpClient` останавливается, когда завершатся идущие запросы. Долгоживущим приложениям обычно хватает одного клиента на всё время работы.

## Безопасность

* **Никогда не встраивайте токен в браузерные или мобильные приложения — держите его на сервере.** Любой, у кого есть токен, тратит вашу квоту.
* Токен проверяется при создании клиента (никакой подстановки заголовков), маскируется в `toString` (`pzk_Q7xR…`), не попадает в логи и исключения.
* Принимается только `https` (обычный `http` — только для `localhost`, `127.0.0.1`, `[::1]`); редиректы не выполняются, поэтому токен не уйдёт на чужой хост.
* Ответы ограничены 8 МиБ; JSON разбирается строгим встроенным парсером с пределом вложенности 64.

Об уязвимостях пишите на security@polyzon.org — см. [SECURITY.md](SECURITY.md).

## Версии

SDK следует [семантическому версионированию](https://semver.org/lang/ru/). В минорных версиях у записей-результатов могут появляться поля (и меняться их канонические конструкторы); не создавайте записи-результаты сами в коде, который должен переживать минорные обновления. Изменения — в [CHANGELOG.md](CHANGELOG.md).

## Требования

Java 17 или новее (проверяется на 17, 21 и 25). Других зависимостей нет.

## Сборка

```sh
./mvnw verify   # компиляция с -Werror, тесты, покрытие ≥ 90 %, jar с javadoc и исходниками
```

## Ссылки

* Документация API и песочница: https://api.polyzon.org
* Токены: https://polyzon.org/account/
* Задачи: https://github.com/PolyzonOrg/polyzon-java/issues

## Лицензия

[MIT](LICENSE) © 2026 Polyzon

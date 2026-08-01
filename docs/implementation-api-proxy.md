# Implementation: API (proxy)

Build instructions for the customer-facing component: `POST /v1/chat`, the
upstream inference client, the servlet filters, and the Gradle and configuration
skeleton the other two components boot on top of.

Read [implementation-contracts.md](implementation-contracts.md) first: the stack,
the [dependency rule](implementation-contracts.md#the-dependency-rule), the
[frozen types](implementation-contracts.md#frozen-types) and the full
[configuration table](implementation-contracts.md#configuration) live there.
Diagrams are in [architecture.md](architecture.md), the wire contract is
[plan/02-api-contract.md](plan/02-api-contract.md), and the siblings are
[implementation-eval-shadow.md](implementation-eval-shadow.md) and
[implementation-metrics.md](implementation-metrics.md). Per the
[build order](implementation-contracts.md#build-order), `ShadowDispatcher` is a
no-op bean until EVAL exists; this component completing is milestone M1.

## 1. Scope and the guarantee

This component owns one guarantee, and every decision below is downstream of it:

> The customer receives the primary model's status code and response bytes
> **verbatim**, and nothing on the path that produces them ever waits on the
> candidate.

"Verbatim" means the body travels as `byte[]` from the upstream socket to the
customer socket with no deserialise/reserialise round trip, which would silently
reorder keys, drop unknown fields and renormalise numbers. "Never waits" is not
achieved by ordering statements carefully; it holds because the only call into
EVAL is `ShadowDispatcher.submit`, which by contract is non-blocking, total and
incapable of reaching the candidate. Admission, the executor, the evaluator and
the `/metric` payload are out of scope, reached only through `submit` and
`MetricsRecorder`.

## 2. Files

```
gradle/libs.versions.toml            version catalog: every dependency version, once
gradle/verification-metadata.xml     dependency checksums; a substituted artifact fails the build
gradle/wrapper/                      committed wrapper, distribution pinned by SHA-256
src/main/java/com/example/shadowproxy/
  ShadowProxyApplication.java        entry point; @ConfigurationPropertiesScan
  config/ProxyProperties.java        validated @ConfigurationProperties record, whole config surface
  config/InferenceClientConfig.java  two RestClient beans, two independent connection pools
  inference/InferenceClient.java     upstream call: model override, raw body out, byte cap
  inference/ChatCompletion.java      status, raw body, truncation flag
  proxy/ChatController.java          POST /v1/chat
  proxy/ProxyExceptionHandler.java   @RestControllerAdvice: the proxy's own error shape
  web/RequestIdFilter.java           correlation id into MDC and the response header
  web/ConcurrencyLimitFilter.java    MAX_CONCURRENT_REQUESTS semaphore, 503 + Retry-After
  web/RequestSizeLimitFilter.java    MAX_REQUEST_BYTES on Content-Length and on the stream
  web/FilterConfig.java              explicit filter order and URL scoping
src/main/resources/application.yaml  explicit ${ENV_VAR} placeholders
```

## 3. Step-by-step build

### Step 1 — Gradle skeleton

Generate a Spring Boot project (web, validation, actuator) and **commit the
wrapper** with `distributionSha256Sum` set, so the build is reproducible from a
bare JDK and the distribution download is not an unverified supply-chain hole.
Declare every version in `gradle/libs.versions.toml`, because versions scattered
through `build.gradle.kts` drift between the main and test configurations.
Generate `gradle/verification-metadata.xml` with
`./gradlew --write-verification-metadata sha256 build`
([plan/06](plan/06-production-readiness.md#supply-chain-and-ci-hardening)), turn
on the configuration cache, and give the WireMock suites their own source set:

```kotlin
testing.suites {
    val test by getting(JvmTestSuite::class) { useJUnitJupiter() }
    val integrationTest by registering(JvmTestSuite::class) {
        useJUnitJupiter()
        dependencies { implementation(project()); implementation(libs.wiremock) }
    }
}
tasks.named("check") { dependsOn(testing.suites.named("integrationTest")) }
```

A `@SpringBootTest` with a WireMock server costs tens of seconds; left in `test`
that lands on every inner-loop run and people stop running tests locally. Wiring
the suite into `check` means CI still cannot skip it.

### Step 2 — `ProxyProperties`

One record, nested by concern, validated by Jakarta constraints. The **whole**
configuration surface lands here, including values only EVAL and METRICS read, so
there is exactly one place where an environment variable becomes a typed field.

```java
@Validated
@ConfigurationProperties(prefix = "proxy")
public record ProxyProperties(
        @Valid @NotNull Upstream upstream, @Valid @NotNull Limits limits,
        @Valid @NotNull Timeouts timeouts, @Valid @NotNull Shadow shadow,
        boolean allowAaMode) {

    public record Upstream(@NotBlank String baseUrl, @NotBlank String apiKey,
                           @NotBlank String primaryModel, @NotBlank String candidateModel) {
        @Override public String toString() {   // redaction on the type, not the log call
            return "Upstream[baseUrl=%s, apiKey=****, primaryModel=%s, candidateModel=%s]"
                    .formatted(baseUrl, primaryModel, candidateModel);
        }
    }

    @AssertTrue(message = "PRIMARY_MODEL and CANDIDATE_MODEL are identical; "
                        + "set ALLOW_AA_MODE=true if that is intentional")
    public boolean isModelPairMeaningful() {
        return upstream == null || allowAaMode
                || !upstream.primaryModel().equals(upstream.candidateModel());
    }
}
```

`Limits` carries `@Positive` byte and concurrency caps, `Timeouts` carries
`@NotNull Duration`s, and a second `@AssertTrue` enforces
`SHADOW_QUEUE_SIZE >= SHADOW_WORKERS`. Cross-field rules are `@AssertTrue`
methods rather than compact-constructor checks, because a constructor throw
during binding buries the real message inside a `BeanInstantiationException`
while `@AssertTrue` prints the property path and message directly; each
null-guards its group, since the parent is validated even when a nested group
failed to bind. The A/A guard earns its place: comparing a model with itself
yields a beautiful, meaningless match rate near 100%, and nothing else in the
system would flag it. Redaction on `toString()` covers the startup dump, a
debugger, exception messages and `/actuator/configprops` at once, whereas
redacting at the call site protects only the call sites you thought of.

Bind through explicit placeholders, because relaxed binding would accept
`PROXY_UPSTREAM_PRIMARYMODEL` and not `PRIMARY_MODEL`, making the documented
names in [plan/04-operations.md](plan/04-operations.md#configuration) fiction:

```yaml
spring:
  threads.virtual.enabled: true
  lifecycle.timeout-per-shutdown-phase: ${SHUTDOWN_GRACE:15s}
server: { port: "${SERVER_PORT:8080}", shutdown: graceful }
management:
  server.port: ${MANAGEMENT_SERVER_PORT:9090}
  endpoints.web.exposure.include: health,prometheus
proxy:
  upstream:
    base-url: ${DO_INFERENCE_BASE_URL:https://inference.do-ai.run}
    api-key: ${DO_INFERENCE_API_KEY}          # no default: startup aborts if unset
    primary-model: ${PRIMARY_MODEL}
    candidate-model: ${CANDIDATE_MODEL}
  limits: { max-concurrent-requests: "${MAX_CONCURRENT_REQUESTS:128}", ... }
  timeouts: { connect: "${CONNECT_TIMEOUT:2s}", primary: "${PRIMARY_TIMEOUT:30s}", ... }
  allow-aa-mode: ${ALLOW_AA_MODE:false}
```

Aborting startup on an unresolvable key placeholder beats booting and returning
upstream `401` on every request. Virtual threads make a request parked on a 30s
inference call cost a continuation rather than a platform thread, which also
removes the accidental back-pressure a fixed pool provided — exactly why the
semaphore in Step 6 is mandatory. `server.shutdown=graceful` gives in-flight
primary requests `SHUTDOWN_GRACE` while the shadow queue is discarded rather than
drained ([plan/06](plan/06-production-readiness.md#shutdown)), which is EVAL's
job. Actuator serves health probes on the **management port**; `/metric` stays
hand-written on the main port because the Objective specifies that exact path and
payload, and Actuator would put it under `/actuator` and shape it its own way.

### Step 3 — `InferenceClientConfig`: two pools

```java
@Bean RestClient primaryRestClient(ProxyProperties p, RestClient.Builder b) {
    return client(b, p, p.timeouts().primary(), p.limits().maxConcurrentRequests());
}

@Bean RestClient candidateRestClient(ProxyProperties p, RestClient.Builder b) {
    return client(b, p, p.timeouts().shadow(), p.shadow().workers());
}

private static RestClient client(RestClient.Builder b, ProxyProperties p,
                                 Duration readTimeout, int maxConnections) {
    var pool = PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(maxConnections)
            .setMaxConnPerRoute(maxConnections)       // both models are ONE route
            .setDefaultConnectionConfig(ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.of(p.timeouts().connect()))
                    .setSocketTimeout(Timeout.of(readTimeout)).build())
            .build();
    var httpClient = HttpClients.custom()
            .setConnectionManager(pool).disableAutomaticRetries().build();
    return b.clone().baseUrl(p.upstream().baseUrl())
            .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
            .defaultHeaders(h -> {
                h.setBearerAuth(p.upstream().apiKey());
                h.set(HttpHeaders.ACCEPT_ENCODING, "identity");
            })
            .build();
}
```

**Separate pools are the point of this step.** Both models resolve to the same
DigitalOcean host, so a shared pool is a channel through which 32 shadow workers
hold connections the customer path needs — customer latency and `502`s while the
shadow queue sits comfortably inside its bounds, which queue bounding cannot
detect. Sizes come from
[plan/06](plan/06-production-readiness.md#separate-connection-pools): primary to
`MAX_CONCURRENT_REQUESTS`, candidate to `SHADOW_WORKERS`. `setMaxConnPerRoute`
must accompany `setMaxConnTotal`, because Apache HttpClient's per-route default
is **5** and one host is one route, so setting only the total yields a pool of
five that reads as 128 in the configuration.

`CONNECT_TIMEOUT` (2s) is distinct from the read timeout because an unreachable
host and a model thinking for 25s are different facts, and one combined value
forces you to get one of them wrong. `disableAutomaticRetries()` because
inference calls are billed and non-idempotent. `Authorization` from the
default-header callback, so the key is materialised in one expression that no
call site can forget or log. `Accept-Encoding: identity`, so the bytes we buffer
are the bytes we relay rather than a gzipped body we would have to decompress or
relay with a `Content-Encoding` header we never copied. Register the request
factories as beans so both pools close on shutdown.

### Step 4 — `InferenceClient` and `ChatCompletion`

```java
public record ChatCompletion(int status, byte[] body, boolean truncated) {
    public boolean isSuccess() { return status >= 200 && status < 300; }
}

public ChatCompletion complete(String model, byte[] requestBody) {
    ObjectNode payload = (ObjectNode) mapper.readTree(requestBody);
    payload.put("model", model);                          // routing is server-decided
    return restClient.post()
            .uri("/v1/chat/completions")
            .headers(h -> { if (shadowRole) h.set("X-Shadow-Request", "true"); })
            .body(mapper.writeValueAsBytes(payload))
            .exchange((request, response) -> {
                byte[] buf = response.getBody().readNBytes(maxResponseBytes + 1);
                boolean over = buf.length > maxResponseBytes;
                return new ChatCompletion(response.getStatusCode().value(),
                        over ? Arrays.copyOf(buf, maxResponseBytes) : buf, over);
            });
}
```

Two beans, `primaryInferenceClient` and `candidateInferenceClient`, differ only
in their `RestClient` and the `shadowRole` flag; EVAL injects the candidate one.
`X-Shadow-Request` is a property of the **client role** rather than of the model
argument because the A/A control arm calls the *primary model* through the
*candidate* client, and that is still mirrored traffic upstream billing must be
able to identify
([plan/02](plan/02-api-contract.md#upstream-contract-digitalocean-serverless-inference)).
Overriding `model` rather than honouring the caller's value is what makes the
comparison valid at all: both arms must see byte-identical input apart from that
one field.

`exchange(...)` is used instead of `retrieve()` precisely because it bypasses
default status handling — `retrieve()` throws on 4xx/5xx, so an upstream `429`
carrying a useful `Retry-After` would become our exception and then our error
body, the opposite of a transparent proxy. `readNBytes(maxResponseBytes + 1)`
reads one byte past the ceiling, enough to detect an oversized response without
allocating it; `truncated` is fatal on the primary path, since half a JSON object
relayed as though whole is worse than an error, so the controller turns it into
`502` ([status codes](plan/02-api-contract.md#status-codes)) while EVAL records
`CONTENT_TRUNCATED` on the candidate side.

Retries, per
[plan/06](plan/06-production-readiness.md#retries-and-where-they-are-forbidden):
**exactly one**, primary role only, and only when the failure proves no request
bytes reached the server (`ConnectTimeoutException`, `ConnectException`), after
50–150ms of jittered backoff — never on `SocketTimeoutException`, which means the
model may be generating a response we are already being billed for.

### Step 5 — `ChatController`

```java
@PostMapping(path = "/v1/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
public ResponseEntity<byte[]> chat(@RequestBody byte[] body, HttpServletRequest request) {
    metrics.recordRequest();
    rejectStreaming(body);                       // 400 on stream:true or malformed JSON

    ChatCompletion upstream = primary.complete(properties.upstream().primaryModel(), body);
    if (upstream.truncated()) throw new UpstreamUnreadableException();

    ResponseEntity<byte[]> response = ResponseEntity.status(upstream.status())
            .contentType(MediaType.APPLICATION_JSON).body(upstream.body());

    if (upstream.isSuccess()) {
        dispatcher.submit(new ShadowJob(RequestIdFilter.currentId(request),
                body.clone(), upstream.body().clone(), System.nanoTime()));
    }
    return response;
}
```

`byte[]` in and `byte[]` out: binding to a DTO would make "relayed verbatim" a
claim rather than a fact. `rejectStreaming` walks the body with a Jackson
`JsonParser` looking for a top-level `stream` field, throwing
`StreamingNotSupportedException` when it is `true` and letting a parse error
become `400`, so malformed input costs no money and returns our error shape. The
body is therefore parsed twice — accepted, because bodies are capped at 128 KiB,
the scan builds no tree, and passing a parsed tree across the boundary would
break the frozen `complete(model, bytes)` signature EVAL also calls.

The submit sits **after the response entity is built and immediately before
returning it**, and is skipped entirely when the primary call did not succeed:
there is nothing to compare against. Read
[Where the hand-off happens](implementation-contracts.md#where-the-hand-off-happens)
before changing this — submitting after the socket write needs a
`ResponseBodyAdvice` or filter wrapper and makes enqueue time a function of
client network speed, pushing slow clients' jobs toward the TTL and biasing the
very number this service exists to report. The guarantee comes from the
signature, not the line number
([lifecycle](architecture.md#3-request-lifecycle)). Both arrays are cloned,
because the job outlives the request and every request-scoped object is invalid
once the response is committed.

`recordRequest()` is the first statement, matching that diagram, so
`total_requests` counts requests that reached the controller: `400`s are
included, and the filter-level `413`/`503` rejections are not. Any caller
`Authorization` header is never read here and never forwarded, since the upstream
call carries our own credential
([plan/06](plan/06-production-readiness.md#credential-handling)); do not add a
"copy the inbound headers" relay, which would leak the caller's token to a third
party and let a caller override our `Authorization` and `X-Shadow-Request`.

### Step 6 — The three filters, in order

Register them in `FilterConfig` with `FilterRegistrationBean`, explicit orders,
and `addUrlPatterns("/v1/*")` — scoping keeps the semaphore off the probe
endpoints, since a readiness probe that gets `503` because the service is *busy*
makes the orchestrator pull a healthy pod exactly when capacity is scarcest.

| Order | Filter | Why here |
| --- | --- | --- |
| `HIGHEST_PRECEDENCE` | `RequestIdFilter` | Every later log line, including the `503` and `413` rejections, must carry a correlation id |
| `+10` | `ConcurrencyLimitFilter` | The in-flight cap must apply before any work — including reading the body — is done |
| `+20` | `RequestSizeLimitFilter` | Runs holding a permit, so a flood of oversized bodies cannot bypass the cap |

`RequestIdFilter` accepts an inbound `X-Request-Id` only when it is at most 64
characters of `[A-Za-z0-9._-]`, otherwise generating a UUID, and puts it in the
MDC, a request attribute and the response header. An unvalidated value echoed
into a header and written to logs is header injection and log forgery. Clear the
MDC in a `finally`, or a leaked entry mislabels the next request.

`ConcurrencyLimitFilter` holds `new Semaphore(maxConcurrentRequests, false)` and
calls `tryAcquire()` with **no timeout**, releasing in a `finally`: waiting for a
permit is queueing, and queueing is what the cap exists to prevent
([plan/06](plan/06-production-readiness.md#bounded-inbound-concurrency)). It
writes `503` with `Retry-After: 1` itself rather than throwing, because
exceptions raised before `DispatcherServlet` never reach
`@RestControllerAdvice` — share one `ProxyError` writer between filter and advice
so the two paths cannot drift into different shapes.

`RequestSizeLimitFilter` enforces the cap **twice**, and both are necessary. A
declared `Content-Length` over `MAX_REQUEST_BYTES` is rejected before a byte is
read; a chunked request declares no length, so the filter wraps it in an
`HttpServletRequestWrapper` whose `ServletInputStream` counts bytes and throws
`RequestTooLargeException` past the cap, which the advice shapes into `413`.
Enforcing only `Content-Length` leaves a trivial hole: send chunked, send
forever.

### Step 7 — `ProxyExceptionHandler`

`@RestControllerAdvice` returning one shape,
`{"error":{"type":"proxy_error","code":"...","message":"...","request_id":"..."}}`.
The `proxy_error` marker is load-bearing: upstream error bodies are relayed
untouched and are OpenAI-shaped, so without it a caller debugging a `502` cannot
tell whether the model rejected the request or the proxy never reached it.
Relayed bodies are **never** rewrapped in this shape.

| Exception | Status | Meaning |
| --- | --- | --- |
| `HttpMessageNotReadableException` | `400` | Body is not parsable JSON |
| `StreamingNotSupportedException` | `400` | `stream: true`; unsupported in v1 |
| `HttpRequestMethodNotSupportedException` | `405` | Method other than `POST` |
| `HttpMediaTypeNotSupportedException` | `415` | `Content-Type` is not `application/json` |
| `RequestTooLargeException` | `413` | Chunked body exceeded `MAX_REQUEST_BYTES` |
| `ResourceAccessException` caused by `SocketTimeoutException` | `504` | Primary exceeded `PRIMARY_TIMEOUT` |
| `ResourceAccessException`, any other cause | `502` | Connect, TLS or DNS failure |
| `UpstreamUnreadableException` | `502` | Primary body exceeded `MAX_RESPONSE_BYTES` |

Splitting `ResourceAccessException` on its cause is the one subtle mapping: both
are "timeouts", but an unreachable endpoint and a slow model demand opposite
responses, and an operator paged on `504` should not be checking DNS.

## 4. Configuration read by this component

Every variable is bound in `ProxyProperties`; this table lists only what the API
component reads, with names and defaults from
[implementation-contracts.md](implementation-contracts.md#configuration).

| Variable | Default | Where it is read |
| --- | --- | --- |
| `DO_INFERENCE_API_KEY` | **required** | `Authorization` default header, both clients |
| `DO_INFERENCE_BASE_URL` | `https://inference.do-ai.run` | `baseUrl`, both clients |
| `PRIMARY_MODEL` | **required** | `model` override on the customer path |
| `MAX_CONCURRENT_REQUESTS` | `128` | Semaphore permits; primary pool size |
| `MAX_REQUEST_BYTES` | `131072` | `RequestSizeLimitFilter`, both checks |
| `MAX_RESPONSE_BYTES` | `262144` | Response read cap in `InferenceClient` |
| `CONNECT_TIMEOUT` | `2s` | Connect timeout on both pools |
| `PRIMARY_TIMEOUT` | `30s` | Read timeout on the primary pool |
| `SHADOW_TIMEOUT` | `20s` | Read timeout on the candidate pool (bean defined here) |
| `SHADOW_WORKERS` | `32` | Candidate pool size (bean defined here) |
| `SHUTDOWN_GRACE` | `15s` | `spring.lifecycle.timeout-per-shutdown-phase` |
| `ALLOW_AA_MODE` | `false` | Cross-field validation escape hatch |
| `SERVER_PORT` | `8080` | Listener port |
| `LOG_LEVEL` | `info` | Application log level |

`SHADOW_TIMEOUT` and `SHADOW_WORKERS` appear here even though the contracts table
attributes them to EVAL, because the candidate `RestClient` bean is defined by
this component; both remain EVAL's to tune.

## 5. Failure modes

| What goes wrong | What the customer sees | What is counted |
| --- | --- | --- |
| `Content-Length` over the cap | `413`, no upstream call | Nothing; the filter precedes the controller |
| Chunked body exceeds the cap mid-read | `413` via the advice | Nothing |
| Body is not JSON, or `stream: true` | `400`, no upstream call | `recordRequest` |
| Concurrency cap saturated | `503` with `Retry-After: 1`, immediately | Nothing; never reaches the controller |
| Primary refuses the connection | `502` after one retry | `recordRequest`; no shadow job |
| Primary exceeds `PRIMARY_TIMEOUT` | `504`, no retry | `recordRequest`; no shadow job |
| Primary returns `4xx`/`5xx` | That exact status and body, verbatim | `recordRequest`; no shadow job |
| Primary body over `MAX_RESPONSE_BYTES` | `502` rather than a truncated body | `recordRequest`; no shadow job |
| Shadow job shed at admission | Nothing; the response was already correct | EVAL's `shed_by_reason` |
| Candidate slow, failing or down | Nothing at all | EVAL's shadow counters |
| `SIGTERM` mid-request | Response completes within `SHUTDOWN_GRACE` | EVAL sheds the queue as `shutting_down` |

## 6. Tests

Unit tests in `test`, WireMock and `@SpringBootTest` in `integrationTest`.

Unit: `ProxyProperties` fails to bind on a blank `DO_INFERENCE_API_KEY`, on
`SHADOW_QUEUE_SIZE < SHADOW_WORKERS` and on equal models with
`ALLOW_AA_MODE=false`, and binds with `ALLOW_AA_MODE=true`; `Upstream.toString()`
does not contain the key; `InferenceClient` overwrites a caller-supplied
`"model": "attacker-choice"` and leaves every other field byte-identical; a
`MAX_RESPONSE_BYTES + 1` stub yields `truncated == true` with exactly
`MAX_RESPONSE_BYTES` retained; `RequestIdFilter` replaces a 500-character inbound
id and one containing `\r\n` with a UUID and clears the MDC afterwards. Plus the
ArchUnit rule, in `test` so it fails fast:

```java
noClasses().that().resideInAnyPackage("..shadow..", "..eval..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("jakarta.servlet..", "org.springframework.web..",
                        "..proxy..", "..web..");
```

Note the deliberate omission: `..inference..` is not forbidden to `shadow`, since
EVAL calls `InferenceClient` for the candidate. The rule targets the
servlet-bearing packages, which is where the hazard actually lives.

Integration, against a WireMock upstream:

- **Byte-identical relay.** Stub a body with unusual key order, a `1.0` float and
  an unknown top-level field; assert the response body equals the stub bytes
  exactly. A deserialise/reserialise regression fails this and only this test.
- **Oversized body** returns `413` with zero upstream requests recorded, asserted
  both with a declared `Content-Length` and with `Transfer-Encoding: chunked`.
- **`stream: true`** returns `400` with `error.type == "proxy_error"` and zero
  upstream requests.
- **Upstream `500` relayed.** A stubbed `500` with body `{"error":"model on fire"}`
  reaches the caller as that exact status and body, not a proxy-shaped `502`.
- **Transport failure** (`Fault.CONNECTION_RESET_BY_PEER`) returns `502`, and a
  stub delayed past a test-overridden `PRIMARY_TIMEOUT` returns `504`.
- **Concurrency cap.** With `MAX_CONCURRENT_REQUESTS=2` and a blocking stub, the
  third concurrent request returns `503` with `Retry-After` while the first two
  complete normally.
- **No caller credential forwarded.** Send `Authorization: Bearer caller-secret`;
  assert the recorded upstream request carries `Bearer test-key` and that
  `caller-secret` appears nowhere in it.
- **Hand-off.** With a recording no-op dispatcher, exactly one job on `200` and
  zero jobs after a `500`, a `504` or a `502`.

## 7. Exit checks

Done means all of these hold, not that the code compiles.

1. `./gradlew check` is green, including `integrationTest` and the ArchUnit rule.
2. Startup fails, naming the offending property, when `DO_INFERENCE_API_KEY` is
   unset, when the models match without `ALLOW_AA_MODE`, and when
   `SHADOW_QUEUE_SIZE < SHADOW_WORKERS`; the configuration log prints `****`.
3. Health answers on the management port; `8080` serves only `/v1/*`.
4. `SIGTERM` during an in-flight request lets it finish and exits within
   `SHUTDOWN_GRACE`.
5. The pools are demonstrably distinct: primary max connections and max per route
   equal `MAX_CONCURRENT_REQUESTS`, candidate equal `SHADOW_WORKERS`.
6. **M1** — with `ShadowDispatcher` bound to a no-op and `DO_INFERENCE_BASE_URL`
   pointed at a WireMock upstream, `curl -X POST localhost:8080/v1/chat` returns
   the stub's **exact** body and status, with no added fields, no added headers,
   and the caller's `Authorization` absent from the recorded upstream request.
   This is the milestone in
   [plan/03-implementation-plan.md](plan/03-implementation-plan.md#milestones).

## 8. Risks

- **Per-route pool default.** `setMaxConnTotal` without `setMaxConnPerRoute`
  yields an effective pool of five against a single host while the configuration
  reads `128`, presenting as unexplained primary latency rather than an error.
- **Virtual threads and pinning.** On Java 21 a carrier thread is pinned for the
  duration of a `synchronized` block. Apache HttpClient 5 leases through
  `ReentrantLock`, so it is safe today, but any library added to this path must
  be checked first.
- **`413` and connection reset.** Returning `413` without consuming the rest of
  the body can make Tomcat reset the connection, so the client sees a transport
  error instead of the status we sent. Set Tomcat's swallow size deliberately.
- **The double JSON parse** is on the customer path. If profiling ever shows it,
  the fix is a cheaper scan, not skipping validation and letting the upstream
  bill us for malformed input.
- **`total_requests` semantics.** Filter-level rejections never reach the
  counter; making shed-at-the-door volume visible is a change to the METRICS
  contract and belongs in
  [implementation-metrics.md](implementation-metrics.md).
- **Response compression.** `Accept-Encoding: identity` is what makes the
  verbatim claim true; dropping it silently makes the relayed bytes and the
  relayed headers inconsistent.

# Implementation Plan

Stack: **Java 21 (LTS), Spring Boot 4.1, Maven Wrapper.** See
[README.md](README.md) for why.

Topology: **one service, one image, three frozen components** — API (proxy),
EVAL (shadow), METRICS. The package tree below maps one-to-one onto those
components, and the interfaces between them are fixed in
[05-service-topology.md](05-service-topology.md#frozen-contracts).

## Project layout

```
.
├── mvnw / mvnw.cmd / .mvn/           Maven Wrapper (no local Maven needed)
├── pom.xml
├── src/main/java/com/example/shadowproxy/
│   ├── ShadowProxyApplication.java   entry point
│   ├── config/
│   │   ├── ProxyProperties.java      @ConfigurationProperties record + validation
│   │   ├── InferenceClientConfig.java  primary/candidate RestClient beans
│   │   └── ShadowExecutorConfig.java   bounded ThreadPoolExecutor + shed handler
│   ├── inference/
│   │   ├── InferenceClient.java      DO Serverless Inference chat client
│   │   └── ChatCompletion.java       response records
│   ├── proxy/
│   │   ├── ChatController.java       POST /v1/chat
│   │   └── ProxyExceptionHandler.java  @RestControllerAdvice: 400/413/502/504
│   ├── shadow/
│   │   ├── ShadowJob.java            immutable record: body, primary output, id
│   │   ├── ShadowDispatcher.java     non-blocking submit
│   │   └── ShadowTask.java           candidate call + eval + counters
│   ├── eval/
│   │   ├── Evaluator.java            deterministic heuristic (pure)
│   │   └── EvalResult.java           MATCH / MISMATCH / INVALID_* / MISSING_ACTION
│   ├── metrics/
│   │   ├── MetricsRegistry.java      LongAdder counters
│   │   ├── MetricsSnapshot.java      immutable record, snake_case JSON
│   │   └── MetricController.java     GET /metric
│   └── web/
│       ├── ApiKeyAuthFilter.java     inbound bearer auth, constant-time compare
│       ├── ConcurrencyLimitFilter.java  MAX_CONCURRENT_REQUESTS semaphore
│       ├── RateLimitFilter.java      per-key token bucket
│       ├── RequestIdFilter.java      correlation id into MDC and response
│       └── RequestSizeLimitFilter.java  MAX_REQUEST_BYTES enforcement
├── src/main/resources/application.yaml
├── src/test/java/com/example/shadowproxy/...
├── docs/
│   ├── Objective.md                  frozen, do not edit
│   └── plan/                         these documents
├── .github/workflows/ci.yml
├── Dockerfile
└── README.md
```

Rationale for the split: `eval` and `metrics` are pure and testable without a
Spring context; `shadow` owns all concurrency so the isolation guarantee lives in
one reviewable place; `proxy` stays thin enough that reading it makes the latency
claim obvious.

## Phase 1 — Skeleton and configuration

Deliverable: the application boots, serves both routes with stub behaviour, and
shuts down cleanly.

- Generate the Spring Boot 4.1 project (web + validation + actuator), commit the
  Maven Wrapper so `./mvnw` works with only a JDK present.
- `ProxyProperties`: a `@ConfigurationProperties`-bound record with
  `@NotBlank` / `@Positive` constraints, so a missing API key or model name fails
  at startup rather than on the first request. Bind through explicit
  `${ENV_VAR}` placeholders in `application.yaml` to keep the documented env
  names exact (Spring's relaxed binding would otherwise rename them).
- Add a cross-field check that `SHADOW_QUEUE_SIZE >= SHADOW_WORKERS`.
- Enable `spring.threads.virtual.enabled=true` and
  `server.shutdown=graceful`.
- Actuator exposes `/actuator/health` for container probes; `/metric` stays
  hand-written.

## Phase 2 — Synchronous primary proxy

Deliverable: `POST /v1/chat` proxies to the primary model and returns its
response verbatim.

- `InferenceClientConfig`: two `RestClient` beans over a shared connection pool,
  differing only in read timeout (`PRIMARY_TIMEOUT` vs `SHADOW_TIMEOUT`), each
  with the `Authorization` header applied by a default-header callback.
- `InferenceClient.complete(model, bodyBytes)`: overrides the `model` field on
  the incoming payload and returns status plus raw body, with default 4xx/5xx
  exception translation disabled so error bodies pass through.
- `ChatController`: reject non-JSON and `stream: true`; take the body as
  `byte[]`; relay upstream status and body unchanged; increment
  `totalRequests`.
- `RequestSizeLimitFilter`: reject on `Content-Length` over the cap and wrap the
  stream to enforce the cap for chunked requests too.
- `ProxyExceptionHandler`: map transport failure to `502` and read timeout to
  `504`.

Exit check: `curl` against a WireMock upstream returns the stub's exact body.

## Phase 3 — Bounded shadow pipeline

Deliverable: shadow execution that provably cannot affect the primary path.

- `ShadowExecutorConfig`: construct `ThreadPoolExecutor` explicitly with
  `ArrayBlockingQueue(SHADOW_QUEUE_SIZE)`, `SHADOW_WORKERS` core and max threads,
  a named daemon `ThreadFactory`, and a `RejectedExecutionHandler` that
  increments `shadowShed` and returns. Explicitly not `CallerRunsPolicy`, which
  would run the candidate call on the request thread.
- `@PreDestroy`: `shutdown()` then `awaitTermination(SHUTDOWN_GRACE)`.
- `ShadowDispatcher.submit(job)`: applies `SHADOW_SAMPLE_RATE` first (counting
  `shadowSampledOut`), then one `executor.execute(...)` call, incrementing
  `shadowEnqueued` on acceptance. Never blocks, never throws.
- `ShadowTask`: performs the candidate call, runs the evaluator, updates
  counters, and wraps everything in `try/catch (Throwable)` so nothing escapes
  into the pool. Distinguishes read timeout from other failures for the
  timeout counter.
- Wire the controller to submit **after** the response is written, and skip
  submission entirely when the primary call failed.
- Copy everything the task needs into `ShadowJob` at submit time; never touch
  `HttpServletRequest` or request-scoped state from a worker.

Exit check: with a candidate stub that sleeps 30s, primary latency is unchanged;
with a queue of size 2 and a burst of 100 requests, `shadow_shed` reports the
drops and thread count stays flat.

## Phase 4 — Deterministic evaluation

Deliverable: the heuristic from [02-api-contract.md](02-api-contract.md).

- `Evaluator.compare(byte[] primary, byte[] candidate)` returning `EvalResult`,
  with no I/O and no logging.
- Content extraction, fence stripping, Jackson parse, `action` extraction, exact
  string comparison.
- `ShadowTask` translates the `EvalResult` into counter updates through a single
  `switch`, so the mapping can be read at a glance.

## Phase 5 — Metrics endpoint

Deliverable: `GET /metric` returning the live summary.

- `MetricsRegistry`: `LongAdder` fields plus `snapshot()` returning
  `MetricsSnapshot`; rate computed at read time with a zero guard on the
  denominator.
- `MetricController`: return the snapshot with `Cache-Control: no-store`,
  including `queue_depth`, `queue_capacity`, and uptime.

## Phase 6 — Tests

Deliverable: the behavioural claims in these documents are enforced by tests.
JUnit 5, WireMock for the upstream, Awaitility for asynchronous assertions.

- `Evaluator` parameterised tests: match, case-sensitive mismatch, fenced JSON,
  extra keys, prose response, missing `action`, non-textual `action`, blank
  content.
- `MetricsRegistry`: rate math including the `evaluated == 0` zero-guard, and
  concurrent increments driven from multiple threads.
- Executor: submission when the queue is full increments `shadowShed` and returns
  immediately, asserted with a latch that blocks all workers.
- `@SpringBootTest` with WireMock: primary response returns while the candidate
  stub is still delayed; a failing candidate leaves the primary response
  byte-identical; oversized body yields `413`; `stream: true` yields `400`.
- Burst test: N requests against a size-2 queue, asserting
  `shadow_enqueued + shadow_shed == total_requests` and bounded thread growth.
- A test that asserts evaluation still completes after the client response is
  finished, guarding the request-context-detachment hazard from
  [01-architecture.md](01-architecture.md).
- Chaos cases: candidate 500, candidate hang past timeout, candidate non-JSON,
  candidate `429`, and primary failure with a healthy candidate.

The latency-isolation claim is proven by the k6 load test in `perf/`, not by
unit tests; see
[06-production-readiness.md](06-production-readiness.md#proving-the-isolation-claim).

## Phase 7 — Production hardening

Deliverable: everything in
[06-production-readiness.md](06-production-readiness.md) that is code rather
than process.

- `ApiKeyAuthFilter`, `RateLimitFilter`, `ConcurrencyLimitFilter`, and
  `RequestIdFilter`, ordered so authentication runs before any work is done.
- Separate connection pools per upstream, sized to `MAX_CONCURRENT_REQUESTS` and
  `SHADOW_WORKERS` respectively, with a distinct `CONNECT_TIMEOUT`.
- Resilience4j circuit breaker on the candidate call only, with the
  `shadow_circuit_open` counter.
- Micrometer latency histograms and token counters, exposed on the management
  port; liveness and readiness deliberately independent of upstream health.
- Logging filter redacting `Authorization`, with bodies restricted to `TRACE`.
- Shutdown that finishes in-flight primary requests and discards the shadow
  queue, counting `shadow_dropped_shutdown`.

Exit check: an unauthenticated request gets `401`; exceeding the concurrency cap
gets `503` rather than growing the heap; killing the candidate trips the breaker
and stops the calls.

## Phase 8 — Documentation and CI/CD

Deliverable: the artifacts the Objective's "TO build" section names.

- `README.md`: what it is, setup, env table, run/Docker instructions, example
  `curl` for both endpoints, and the required **"How this bounds memory
  footprint"** section, summarising
  [01-architecture.md](01-architecture.md#how-this-bounds-the-memory-footprint)
  with the `(queue + workers) x bytes_per_job` formula.
- Keep the architecture diagrams in [01-architecture.md](01-architecture.md) and
  link them from the README rather than duplicating them.
- `.github/workflows/ci.yml`: on push and PR — Spotless format check, compile,
  `./mvnw verify` (unit plus integration tests), and a Docker image build, with
  Temurin 21 and Maven dependency caching via `actions/setup-java`.
- `Dockerfile`: multi-stage, dependency layer cached separately from application
  classes, `eclipse-temurin:21-jre` runtime, non-root user.
- `docker-compose.yaml`: the app plus a WireMock `mock-inference` container, so
  `docker compose up` demonstrates shedding, timeouts, and mismatches with no API
  key. See
  [05-service-topology.md](05-service-topology.md#docker-topology).

## Milestones

- **M1 — Transparent proxy.** Phases 1–2. Primary traffic served correctly.
- **M2 — Isolated shadowing.** Phase 3. Burst protection demonstrated.
- **M3 — Full Objective.** Phases 4–5. Evaluation and `/metric` complete.
- **M4 — Hardened.** Phases 6–7. Tests green under load, auth, limits, breaker,
  and telemetry in place.
- **M5 — Production ready.** Phase 8. CI, image scanning, docs published, and
  the go-live checklist in
  [06-production-readiness.md](06-production-readiness.md#go-live-checklist)
  satisfied.

## Risks and mitigations

- **Background work touching request state** — the most likely bug in this
  design. A worker reading `HttpServletRequest` or a request-scoped bean after
  the response is committed fails intermittently and confusingly. Mitigated by
  the immutable `ShadowJob` copy and a test that runs an evaluation after the
  client has disconnected.
- **Accidentally unbounded executor** — `Executors.newFixedThreadPool` uses an
  unbounded `LinkedBlockingQueue` and would silently void the memory guarantee.
  Mitigated by constructing `ThreadPoolExecutor` explicitly and asserting the
  queue capacity in a test.
- **Candidate rate limiting (429) polluting the error rate** — counted as
  `shadow_errors` and left un-retried by design; `shadow_shed` and error counts
  together tell the operator to lower `SHADOW_WORKERS`.
- **Response shape drift between models** — extraction is defensive: missing
  choices or blank content produce an explicit invalid outcome rather than an
  exception.
- **Metric denominator confusion** — `evaluated` excludes shed and errored jobs,
  documented in [02-api-contract.md](02-api-contract.md) and covered by tests, so
  the match rate is never diluted by jobs that never ran.

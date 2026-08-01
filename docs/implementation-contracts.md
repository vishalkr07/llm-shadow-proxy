# Implementation Contracts

The shared vocabulary for the three implementation plans. Everything here is
consumed by more than one component, which is exactly why it lives in one file:
if these types were restated in each plan they would drift, and the seams between
the components are the only place a drift actually hurts.

| Component | Plan | Owns |
| --- | --- | --- |
| API (proxy) | [implementation-api-proxy.md](implementation-api-proxy.md) | `POST /v1/chat`, the upstream client, the servlet filters |
| EVAL (shadow) | [implementation-eval-shadow.md](implementation-eval-shadow.md) | admission, the bounded executor, the candidate call, the evaluator |
| METRICS | [implementation-metrics.md](implementation-metrics.md) | the counter registry, the snapshot, `GET /metric` |

Design reasoning for all of it is in [plan/](plan/); the diagrams are in
[architecture.md](architecture.md). These documents are the build instructions —
they say what to write, in what order, and what must be true when each step is
finished.

## Stack

Java 21 (LTS), Spring Boot 4.1, Gradle with the Kotlin DSL via the wrapper.
Jackson for JSON, JUnit 5 with WireMock and Awaitility for tests, ArchUnit for
the isolation rules, Resilience4j for the candidate circuit breaker, Micrometer
on the separate management port. Package root `com.example.shadowproxy`.

## The dependency rule

```
API  ───▶  EVAL  ───▶  METRICS
 │                        ▲
 └────────────────────────┘
```

**API depends on EVAL and METRICS. EVAL depends on METRICS. METRICS depends on
nothing.** EVAL never calls back into API. An ArchUnit test fails the build on
any edge not in that diagram, and on any import of `jakarta.servlet` or
`org.springframework.web` from `shadow` or `eval` — that rule is what makes the
isolation guarantee structural instead of reviewed.

METRICS depending on nothing has one consequence worth stating up front: it
cannot reach into the executor to read queue depth. The gauge interface below is
how that inversion is resolved.

## Frozen types

Every type in this block is shared across component boundaries. Change one and
you are changing a contract, so it happens here first and in the plans second.

```java
// ---- API -> EVAL : the single crossing between sync and async ----

public interface ShadowDispatcher {
    /**
     * Hands a job to the shadow path. Non-blocking, allocation-light, and total:
     * it either admits the job or sheds it, and it never throws. The customer
     * path calls this and immediately continues.
     */
    void submit(ShadowJob job);
}

/**
 * Everything a worker needs, copied. No servlet type can be reached from here,
 * which is what makes it safe to touch after the response is committed.
 */
public record ShadowJob(
    String requestId,
    byte[] requestBody,          // defensive copy taken at submit time
    byte[] primaryResponseBody,  // defensive copy taken at submit time
    long capturedAtNanos         // System.nanoTime(), for the TTL check
) {}

// ---- inside EVAL, surfaced to METRICS ----

public interface Evaluator {
    /** Pure: no I/O, no logging, no clock, no state, no exceptions. */
    EvalResult compare(byte[] primaryBody, byte[] candidateBody);
}

public enum EvalVerdict {
    MATCH, MISMATCH, PRIMARY_UNUSABLE, CANDIDATE_UNUSABLE, BOTH_UNUSABLE
}

public enum UnusableReason {
    EMPTY_CONTENT, CONTENT_TRUNCATED, NOT_JSON, TRAILING_GARBAGE,
    NOT_OBJECT, ACTION_ABSENT, ACTION_NOT_STRING
}

public record EvalResult(
    EvalVerdict verdict,
    UnusableReason primaryReason,    // null when the primary side was usable
    UnusableReason candidateReason,  // null when the candidate side was usable
    boolean nearMatch                // only ever true when verdict == MISMATCH
) {}

public enum ShadowOutcome { OK, ERROR, TIMEOUT }

public enum ShedReason {
    SAMPLED_OUT, QUEUE_FULL, BYTE_BUDGET, BREAKER_OPEN, STALE_IN_QUEUE, SHUTTING_DOWN
}

// ---- API, EVAL -> METRICS : write side only ----

public interface MetricsRecorder {
    void recordRequest();
    void recordShadowEligible();
    void recordShadowEnqueued();
    void recordShadowShed(ShedReason reason);
    void recordShadowCompleted(ShadowOutcome outcome);
    void recordEvaluation(EvalResult result);
    void recordControlArmEvaluation(EvalResult result);
    void recordTokens(boolean candidate, long promptTokens, long completionTokens);
}

// ---- METRICS -> API : read side only ----

public interface MetricsReader {
    MetricsSnapshot snapshot();
}

/**
 * Declared by METRICS, implemented by EVAL, bound once at startup. This is how
 * live executor state reaches the snapshot without METRICS taking a dependency
 * on EVAL. Every method must be cheap and non-blocking: they are called on the
 * scrape path.
 */
public interface ShadowGauges {
    int    queueDepth();
    int    queueCapacity();
    long   bytesUsed();
    long   bytesCapacity();
    int    inFlight();
    int    workers();
    double currentAdmitProbability();
    String breakerState();   // "closed" | "open" | "half_open"
}
```

Splitting `MetricsRecorder` from `MetricsReader` is deliberate. EVAL is injected
only with the writer and therefore cannot read or reason about aggregate state,
and `MetricController` is injected only with the reader. The compiler enforces
the direction of the dependency rule rather than a reviewer having to.

### Two corrections against the plan folder

The plan documents are frozen as a record of the design debate, so these two
divergences are resolved here rather than by editing them:

- `plan/05-service-topology.md` lists `EvalResult` as an enum with
  `INVALID_PRIMARY / INVALID_CANDIDATE / INVALID_BOTH / MISSING_ACTION`. That
  predates the richer per-side reasons. **The block above is authoritative**, and
  it is the shape the `/metric` wire contract in
  [plan/02-api-contract.md](plan/02-api-contract.md) already assumes.
- Near-match is **not** a verdict. A near match is a `MISMATCH` that additionally
  sets the `nearMatch` flag, because `"Refund"` versus `"refund"` genuinely is a
  mismatch to a downstream `switch (action)` and must be counted as one. The flag
  exists so the cost of that strictness is visible rather than hidden.

## Shed reasons and counter names

One name per concept, used identically in code, logs, and the `/metric` payload.
The `ShedReason` enum constant lowercased is the JSON key, and nothing else is
acceptable — `plan/03-implementation-plan.md` and
`plan/06-production-readiness.md` use `shadow_circuit_open` and
`shadow_dropped_shutdown` in places, and those names are superseded by
`breaker_open` and `shutting_down`.

| `ShedReason` | JSON key under `shed_by_reason` | Raised when |
| --- | --- | --- |
| `SAMPLED_OUT` | `sampled_out` | The uniform-random admission draw failed, or `SHADOW_SAMPLE_RATE` excluded the request |
| `QUEUE_FULL` | `queue_full` | The bounded queue rejected the offer |
| `BYTE_BUDGET` | `byte_budget` | The compare-and-set reservation would exceed `SHADOW_BUDGET_BYTES` |
| `BREAKER_OPEN` | `breaker_open` | The candidate circuit breaker is open, checked at admission |
| `STALE_IN_QUEUE` | `stale_in_queue` | Job age exceeded `SHADOW_TASK_TTL`, checked at dequeue |
| `SHUTTING_DOWN` | `shutting_down` | `SIGTERM` arrived; the queue is discarded, not drained |

The breaker is checked at **admission**, not at the worker. Reserving byte budget
and a queue slot for a call that is known to be impossible is memory spent to
produce nothing.

## Configuration

Bound to a validated `@ConfigurationProperties` record through explicit
`${ENV_VAR}` placeholders in `application.yaml`, so Spring's relaxed binding
cannot silently rename the documented variables. The application refuses to boot
on invalid input rather than failing per request.

| Variable | Default | Read by |
| --- | --- | --- |
| `DO_INFERENCE_API_KEY` | **required** | API, EVAL |
| `PRIMARY_MODEL` | **required** | API |
| `CANDIDATE_MODEL` | **required** | EVAL |
| `DO_INFERENCE_BASE_URL` | `https://inference.do-ai.run` | API, EVAL |
| `MAX_CONCURRENT_REQUESTS` | `128` | API |
| `MAX_REQUEST_BYTES` | `131072` | API, EVAL |
| `MAX_RESPONSE_BYTES` | `262144` | API, EVAL |
| `MAX_EVAL_CONTENT_BYTES` | `32768` | EVAL |
| `PRIMARY_TIMEOUT` | `30s` | API |
| `SHADOW_TIMEOUT` | `20s` | EVAL |
| `CONNECT_TIMEOUT` | `2s` | API, EVAL |
| `SHADOW_QUEUE_SIZE` | `32` | EVAL |
| `SHADOW_WORKERS` | `32` | EVAL |
| `SHADOW_BUDGET_BYTES` | `8388608` | EVAL |
| `SHADOW_TASK_TTL` | `30s` | EVAL |
| `SHADOW_SAMPLE_RATE` | `1.0` | EVAL |
| `SHADOW_CONTROL_ARM_RATE` | `0.01` | EVAL |
| `CANDIDATE_QUOTA_SHARE` | `0.4` | EVAL |
| `SHADOW_BREAKER_FAILURE_RATE` | `50` | EVAL |
| `SHADOW_BREAKER_WAIT` | `60s` | EVAL |
| `SHUTDOWN_GRACE` | `15s` | API, EVAL |
| `MANAGEMENT_SERVER_PORT` | `9090` | METRICS |
| `LOG_LEVEL` | `info` | all |

Validation: timeouts positive, `SHADOW_WORKERS >= 1`,
`SHADOW_QUEUE_SIZE >= SHADOW_WORKERS`, `SHADOW_SAMPLE_RATE` and
`SHADOW_CONTROL_ARM_RATE` within `0.0`–`1.0`, `DO_INFERENCE_API_KEY` non-blank,
and `PRIMARY_MODEL != CANDIDATE_MODEL` unless `ALLOW_AA_MODE=true`. Secrets are
redacted from the startup configuration log. Derivation of the concurrency
constants from the upstream rate limit is in
[plan/04-operations.md](plan/04-operations.md#deriving-the-concurrency-constants);
re-derive them rather than nudging them.

## Where the hand-off happens

The controller submits the shadow job **after the primary response is in hand and
immediately before returning the `ResponseEntity`** — that is, before the
framework writes bytes to the socket.

This is worth being explicit about because the opposite choice is tempting. In
Spring MVC a controller returns a `ResponseEntity` and the framework performs the
write afterwards, so submitting "after the socket write" needs a
`ResponseBodyAdvice`, an async callback, or a filter wrapper — real machinery
whose only purpose is to make an ordering argument easier to state. It is also
worse: enqueue time would then depend on client network speed, so a slow or
distant client pushes its job toward the TTL and gets quietly under-sampled,
biasing the sample toward fast clients.

The isolation guarantee does not rest on that ordering anyway. It rests on
`submit` being non-blocking, total, and incapable of calling the candidate: the
customer's response cannot wait on shadow work because there is nothing in
`submit` to wait for. Ordering would be a coincidence; the signature is a proof.

**On primary failure, no job is submitted at all** — there is nothing to compare
against.

## Build order

The components are not independent, so build them in this order:

1. **Skeleton** — Gradle wrapper, `ProxyProperties`, boot and graceful shutdown.
   Covered by the API plan, since that is where configuration lands.
2. **METRICS first.** It depends on nothing, and both other components need
   `MetricsRecorder` injected. Build it against an empty `ShadowGauges` stub.
3. **API.** The synchronous proxy, end to end, with `ShadowDispatcher` stubbed as
   a no-op. This is milestone M1 and is independently demonstrable.
4. **EVAL.** Admission, the executor, the candidate call, the evaluator. Bind the
   real `ShadowGauges`, swap out the no-op dispatcher.
5. **Hardening and CI** per [plan/03-implementation-plan.md](plan/03-implementation-plan.md).

Each plan states its own exit checks. A component is done when its exit checks
pass, not when the code is written.

# EVAL (shadow): build instructions

Build plan for the **EVAL** component: admission and load shedding, the bounded
executor, the candidate call, and the deterministic evaluator. Read the
[frozen types](implementation-contracts.md#frozen-types) in
[implementation-contracts.md](implementation-contracts.md) first — this document
builds against them and does not restate them. Diagrams are in
[architecture.md](architecture.md), reasoning in [plan/](plan/), siblings in
[implementation-api-proxy.md](implementation-api-proxy.md) and
[implementation-metrics.md](implementation-metrics.md). Build this component
**fourth** per the [build order](implementation-contracts.md#build-order); until
then API runs against a no-op dispatcher, which the last step swaps out.

## 1. Scope and the guarantee

**Guarantee 1 — shadow work can never delay or affect the primary response.** The
only entry point is `ShadowDispatcher.submit(ShadowJob)`: non-blocking, total (it
admits or sheds), and it never throws. It cannot call the candidate, so there is
no join point at which the customer's response could wait for one. `ShadowJob`
carries copied bytes and no field able to hold a servlet type, so a worker
running after the response is committed cannot touch state that has already gone
invalid. Step 8 makes that structural rather than reviewed.

**Guarantee 2 — peak retained state is a product of configured constants, not of
traffic.** Three gates close the three leak vectors of an in-process queue: a
count gate (`SHADOW_QUEUE_SIZE`) bounds how many jobs are retained, a byte gate
(`SHADOW_BUDGET_BYTES`) bounds how many bytes they occupy, and a lifetime gate
(`SHADOW_TASK_TTL`) bounds how long any one may hold its share. Section 6 does
the arithmetic the Objective requires.

Not owned here: the controller and
[hand-off point](implementation-contracts.md#where-the-hand-off-happens), the
servlet filters, the `RestClient` and `InferenceClient` wiring (API owns them;
EVAL consumes the candidate bean), the registry internals, and the `/metric`
payload shape. Those are reached only through frozen interfaces.

## 2. Files

`ShedReason` and `ShadowOutcome` live in `metrics/`, not here: they appear in
`MetricsRecorder`, which METRICS owns and which depends on nothing.

```
config/
├── ShadowExecutorConfig.java     bounded ThreadPoolExecutor, thread factory, shed handler, @PreDestroy
└── CandidateBreakerConfig.java   Resilience4j CircuitBreaker for the candidate call
shadow/
├── ShadowDispatcher.java         frozen interface: submit — non-blocking, total, never throws
├── BoundedShadowDispatcher.java  implementation: eligible counter, admission, one execute call
├── AdmissionController.java      the gates in order, each drop attributed to a ShedReason
├── AdmissionGovernor.java        once-a-second recomputation of the admit probability
├── ByteBudget.java               AtomicLong reserve/release via a compare-and-set loop
├── ShadowJob.java                frozen record: copied bytes, request id, capture nanos
├── ShadowTask.java               TTL check, candidate call, evaluation, counters, release
└── ExecutorShadowGauges.java     ShadowGauges implementation, published as a bean for METRICS
eval/
├── Evaluator.java                frozen interface: pure compare
├── HeuristicEvaluator.java       per-side parse, comparison, EVAL_VERSION
├── SideResult.java               package-private: an action string or an UnusableReason
└── EvalResult.java, EvalVerdict.java, UnusableReason.java   frozen
```

Tests mirror this tree under `src/test/java`: `HeuristicEvaluatorTest` and
`HeuristicEvaluatorPropertyTest` in `eval/`, `ByteBudgetConcurrencyTest`,
`ShadowExecutorTest` and `ShadowTaskTest` in `shadow/`, `IsolationRulesTest` in
`arch/`. Section 7 says what each asserts.

## 3. Step-by-step build

### Step 1 — `ByteBudget`

Write this first; the memory guarantee rests on it.

```java
public boolean tryReserve(long bytes) {          // used is an AtomicLong, capacity a constant
    for (;;) {
        long current = used.get();
        if (current + bytes > capacity) return false;
        if (used.compareAndSet(current, current + bytes)) return true;
    }
}
public void release(long bytes) { used.addAndGet(-bytes); }
public long used() { return used.get(); }

static final int RESERVATION_OVERHEAD = 512;     // record header, request id, task wrapper
static long reservationFor(ShadowJob job) {
    return job.requestBody().length + job.primaryResponseBody().length + RESERVATION_OVERHEAD;
}
```

The loop matters. `addAndGet` followed by a check-and-undo is the obvious
alternative and it is wrong: between the add and the undo the counter sits above
the cap, so a concurrent reservation observes a state the invariant says cannot
exist and a scrape can report `bytes_used > bytes_capacity`. Compare-and-set
makes the bound true at **every** observation, which is what section 7 asserts.

The documented per-job ceiling is `MAX_REQUEST_BYTES + MAX_EVAL_CONTENT_BYTES +
512 B`
([plan/01-architecture.md](plan/01-architecture.md#how-this-bounds-the-memory-footprint)).
Charging measured lengths rather than the ceiling is what makes the aggregate
invariant hold regardless of which ceiling is right; section 6 records a
discrepancy between that formula and the field actually retained.

**A reservation leak is the bug that would invalidate the whole memory bound.** It
is silent, accumulates only under the load that matters, and ends in permanent
`byte_budget` shedding. Five paths must each release exactly once: rejected
offer, stale at dequeue, breaker denial at the worker, normal completion, thrown
task.

### Step 2 — `ShadowExecutorConfig`

```java
@Bean(destroyMethod = "")   // shutdown ordering is handled by @PreDestroy in step 8
ThreadPoolExecutor shadowExecutor(ProxyProperties props) {
    RejectedExecutionHandler shed = (runnable, executor) ->
        ((ShadowTask) runnable).onRejected(
            executor.isShutdown() ? ShedReason.SHUTTING_DOWN : ShedReason.QUEUE_FULL);
    return new ThreadPoolExecutor(
        props.shadowWorkers(), props.shadowWorkers(),      // fixed: core == max, no elastic growth
        0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(props.shadowQueueSize()), // bounded: the count gate
        new ShadowThreadFactory(),                         // "shadow-worker-N", daemon
        shed);
}
```

Not `Executors.newFixedThreadPool`: it hands you an unbounded
`LinkedBlockingQueue`, so the backlog grows until `OutOfMemoryError` — silently,
because the code looks identical and the tests still pass while the memory
guarantee is void. Not `CallerRunsPolicy`: it runs the rejected task on the
calling thread, which is the request thread, so under exactly the load where
shedding matters the customer is handed the candidate's latency on top of the
primary's. Not `AbortPolicy` (the default): it throws into the request path, but
shedding is a **normal** outcome here, the designed response to overload, so it
increments a counter and returns rather than forcing a `try/catch` onto the
customer path whose only job is to ignore something.

**The handler returns normally, so `execute()` also returns normally on
rejection** — the caller cannot distinguish acceptance by return value or
exception. Resolve it on the task. The handler runs synchronously on the
submitting thread, so no memory barrier is needed.

```java
private boolean rejected;                        // written and read on the submitting thread only

void onRejected(ShedReason reason) {
    rejected = true;
    budget.release(reserved);
    metrics.recordShadowShed(reason);
}
boolean wasRejected() { return rejected; }
```

### Step 3 — `AdmissionController` and the dispatcher

Gates run in the order fixed by the
[shed-reason table](implementation-contracts.md#shed-reasons-and-counter-names)
and drawn in
[architecture.md](architecture.md#4-shadow-admission-and-load-shedding): breaker,
sampling draw, byte reservation, queue offer, then the TTL check at dequeue. Each
gate is cheaper than the next, and the breaker is first because reserving budget
and a queue slot for a call known to be impossible is memory spent to produce
nothing.

```java
/** @return bytes reserved, or -1 when shed (already counted, nothing reserved). */
long admit(ShadowJob job) {
    if (breaker.getState() == CircuitBreaker.State.OPEN) {
        metrics.recordShadowShed(ShedReason.BREAKER_OPEN);  return -1;
    }
    double p = props.shadowSampleRate() * governor.admitProbability();
    if (p < 1.0 && ThreadLocalRandom.current().nextDouble() >= p) {
        metrics.recordShadowShed(ShedReason.SAMPLED_OUT);   return -1;
    }
    long bytes = reservationFor(job);
    if (!budget.tryReserve(bytes)) {
        metrics.recordShadowShed(ShedReason.BYTE_BUDGET);   return -1;
    }
    return bytes;
}

@Override public void submit(ShadowJob job) {               // BoundedShadowDispatcher
    metrics.recordShadowEligible();
    long reserved = admission.admit(job);
    if (reserved < 0) return;
    var task = new ShadowTask(job, reserved, deps...);
    executor.execute(task);                                 // the handler cannot throw
    if (!task.wasRejected()) metrics.recordShadowEnqueued();
}
```

`SHADOW_SAMPLE_RATE` is the operator's ceiling with the governor's probability
under it, so `0.25` yields at most 25% and less while the governor throttles.
Both count as `SAMPLED_OUT`, since both are the deliberate-sampling case an
operator must tell apart from overload. `ThreadLocalRandom` rather than a shared
`Random`, whose single seed would be contended on the customer's path.
`recordShadowEligible` fires before any gate, so `coverage_pct` has a denominator
meaning "jobs that could have been evaluated". `submit` has no path producing an
unchecked exception: `admit` is total, `execute` cannot throw with this handler,
and the counters are `LongAdder` increments.

### Step 4 — `AdmissionGovernor`, and why admission is uniform random

The most important design point in the component, and the one most likely to be
"simplified" away by someone who has not read this section.

The obvious design is a bounded queue that drops whatever arrives once it is
full. It satisfies the Objective's wording and it **quietly biases the one number
this service exists to produce.** A FIFO that drops on overflow admits jobs from
the *beginning* of every burst and rejects the *tail*. Bursts are not random
samples of production traffic: they line up with peak hours, with retry storms
after an incident, and with individual heavy customers. The surviving sample is
therefore skewed toward whatever kind of request starts a burst, and
`exact_match_rate` over it is a biased estimator of the true rate.

The failure is invisible, which is what makes it serious. The figure still
carries four significant digits and a confidence interval, and the interval is
*narrow* — precise and wrong is worse than obviously noisy, because nothing in
the payload hints that the sample is unrepresentative. An operator comparing
week-over-week rates would be comparing two differently-skewed samples and
attributing the difference to the models, and every decision this service exists
to support rests on that number.

Uniform random admission fixes it because dropping uniformly at random preserves
the distribution of what survives. Under a 50x burst roughly 98% of jobs are
dropped, but the surviving 2% look statistically like the whole, so the match
rate stays a valid estimator with a correspondingly wider interval — the honest
outcome rather than a hidden one.

```java
private static final double ALPHA = 0.3;    // ~3 s time constant at a 1 s tick
private static final double FLOOR = 0.01;   // measurement never stops entirely

/** Scheduled at a fixed rate of one second on a single named daemon thread. */
void tick() {
    arrivalEwma = ALPHA * arrivals.sumThenReset()    + (1 - ALPHA) * arrivalEwma;
    drainEwma   = ALPHA * completions.sumThenReset() + (1 - ALPHA) * drainEwma;
    double p = arrivalEwma <= 0 ? 1.0 : Math.min(1.0, drainEwma / arrivalEwma);
    admitProbability = Math.max(FLOOR, p);  // volatile double, read by admit()
}
```

`drainEwma` starts at `SHADOW_WORKERS / SHADOW_TIMEOUT` — `32 / 20s = 1.6` jobs
per second, the worst case when every call runs to the timeout. It agrees with
the 1.7 req/s the constants were
[derived from](plan/04-operations.md#deriving-the-concurrency-constants), and
starting pessimistic throttles too early rather than too late on a cold start.

The 1% floor exists because a governor that can reach zero will: under a
sustained candidate outage it stops sampling, so the drain EWMA stays at zero
because no calls are made, so the probability stays at zero and the recovery is
never observed. Recomputing once a second rather than per admission keeps an EWMA
update off the customer's thread, and a control loop reacting faster than the
system it controls oscillates.

The queue is deliberately **shallow** (`SHADOW_QUEUE_SIZE == SHADOW_WORKERS` at
the defaults). A deep queue is not burst absorption; it is a bias reservoir that
holds memory and throws the samples away at the TTL. Random admission absorbs
burst; the queue only smooths sub-second jitter
([plan/01-architecture.md](plan/01-architecture.md#load-shedding-and-why-it-is-not-just-drop-when-full)).

### Step 5 — `ShadowTask`

```java
@Override public void run() {
    inFlight.incrementAndGet();
    try {
        if (System.nanoTime() - job.capturedAtNanos() > ttlNanos) {
            metrics.recordShadowShed(ShedReason.STALE_IN_QUEUE);  return;
        }
        if (!controlArm && !breaker.tryAcquirePermission()) {
            metrics.recordShadowShed(ShedReason.BREAKER_OPEN);    return;
        }
        execute();
    } catch (Throwable t) {                          // nothing escapes into the pool
        metrics.recordShadowCompleted(ShadowOutcome.ERROR);
        log.debug("shadow task failed requestId={}", job.requestId(), t);
    } finally {
        budget.release(reserved);
        inFlight.decrementAndGet();
        governor.onCompletion();
    }
}

private void execute() {
    String model = controlArm ? props.primaryModel() : props.candidateModel();
    long start = System.nanoTime();
    try {
        InferenceResponse response = candidateClient.complete(model, job.requestBody());
        long took = System.nanoTime() - start;
        if (response.status() >= 400) {
            if (!controlArm) breaker.onError(took, NANOSECONDS, new UpstreamStatus(response.status()));
            metrics.recordShadowCompleted(ShadowOutcome.ERROR);  return;
        }
        if (!controlArm) breaker.onSuccess(took, NANOSECONDS);
        metrics.recordShadowCompleted(ShadowOutcome.OK);
        metrics.recordTokens(true, response.promptTokens(), response.completionTokens());
        EvalResult result = evaluator.compare(job.primaryResponseBody(), response.body());
        if (controlArm) metrics.recordControlArmEvaluation(result);
        else            metrics.recordEvaluation(result);
    } catch (ResourceAccessException e) {
        boolean timeout = e.getCause() instanceof SocketTimeoutException
                       || e.getCause() instanceof HttpTimeoutException;
        if (!controlArm) breaker.onError(System.nanoTime() - start, NANOSECONDS, e);
        metrics.recordShadowCompleted(timeout ? ShadowOutcome.TIMEOUT : ShadowOutcome.ERROR);
    }
}
```

The TTL is rechecked at dequeue because staleness accrues *in the queue*: a job
admitted while the pool was idle can be minutes old by the time a worker reaches
it if the candidate has slowed, and a bounded queue with unbounded item lifetime
still leaks. `System.nanoTime()` rather than wall-clock time because it is
monotonic and immune to NTP steps, which is why the frozen record carries
`capturedAtNanos`.

`catch (Throwable)` rather than `catch (Exception)`: an exception escaping a
pooled task is swallowed by `ThreadPoolExecutor` and the worker silently
replaced, so a systematic failure appears as "evaluations stopped happening" with
no signal anywhere. Catching, counting, and logging at `DEBUG` turns it into a
number; a shed or sampled-out job is never logged as an error.

EVAL injects the candidate-flavoured `InferenceClient`
(`@Qualifier("candidateInferenceClient")`, shape owned by
[API](implementation-api-proxy.md)), built on the candidate `RestClient` with its
**own connection pool** sized to `SHADOW_WORKERS`, its own `SHADOW_TIMEOUT`, and
`X-Shadow-Request: true` as a default header so mirrored traffic is identifiable
upstream
([plan/06-production-readiness.md](plan/06-production-readiness.md#separate-connection-pools)).
An EVAL test asserts that header reaches WireMock, because a default set in
another component's configuration is exactly what disappears in a refactor.

**There are no retries on this path, ever** — not on timeout, not on `429`, not
on `503`. Retrying a best-effort sample under rate-limit pressure adds load to an
upstream already telling us to send less, and the sample it rescues is worth less
than the pressure it adds. Shedding is the correct response, and sustained
failure trips the breaker, which is the same response at a larger scale
([plan/06-production-readiness.md](plan/06-production-readiness.md#retries-and-where-they-are-forbidden)).
Timeout is separated from other failures because they call for opposite actions:
a wall of timeouts means `SHADOW_WORKERS` is too high or the candidate is
degraded, a wall of 5xx means it is broken. The verdict-to-counter mapping is a
single `switch` inside METRICS, so `/metric` reconciles against one place.

### Step 6 — candidate circuit breaker and the A/A control arm

```java
CircuitBreaker.of("candidate", CircuitBreakerConfig.custom()
    .slidingWindowType(SlidingWindowType.COUNT_BASED)
    .slidingWindowSize(20).minimumNumberOfCalls(20)
    .failureRateThreshold(props.shadowBreakerFailureRate())   // default 50%
    .waitDurationInOpenState(props.shadowBreakerWait())       // default 60s
    .permittedNumberOfCallsInHalfOpenState(3)
    .automaticTransitionFromOpenToHalfOpenEnabled(true)
    .build());
```

The breaker wraps the candidate call only; one on the primary path would mask
upstream failures behind a synthetic error, and a transparent proxy should relay
what the upstream actually said.

`automaticTransitionFromOpenToHalfOpenEnabled(true)` is not optional here. We
check the breaker at **admission** by reading `getState()`, which acquires no
permit, and Resilience4j normally leaves `OPEN` only when a call tries to acquire
one after the wait duration. With admission-side shedding no call is ever
attempted while open, so without the automatic transition the breaker stays open
forever and shadowing never recovers. `HALF_OPEN` is admissible at the gate: only
three trial calls hold permits and the worker's `tryAcquirePermission()` sheds
the rest as `BREAKER_OPEN`, so trials are counted against calls that actually
happen. Manual permits mean manual results, hence `onSuccess`/`onError` in step
5. State reaches `/metric` via `ShadowGauges.breakerState()`, mapping
`CLOSED`/`DISABLED`/`METRICS_ONLY` to `"closed"`, `OPEN`/`FORCED_OPEN` to
`"open"` and `HALF_OPEN` to `"half_open"` — the only three values that may appear.

`SHADOW_CONTROL_ARM_RATE` (default `0.01`) of jobs go to the **primary** model
instead of the candidate, decided in the `ShadowTask` constructor
(`ThreadLocalRandom.current().nextDouble() < rate`) rather than at admission,
which keeps `AdmissionController` about gates only, and reported through
`recordControlArmEvaluation` so it never contaminates the A/B numbers. The arm
buys a noise floor: customer requests may use `temperature > 0`, so
primary-versus-primary does not return 100%, and without knowing what it does
return an A/B figure is uninterpretable — if A/A is 96% and A/B is 93% the models
are far closer than the raw gap suggests. Two easy mistakes: the control-arm call
still goes through the **candidate** `RestClient`, so it can never consume a
connection the customer path needs, and it must not touch the breaker in either
direction, because it is not evidence about the candidate.

### Step 7 — `Evaluator`

Pure: no I/O, no logging, no clock, no state, no exceptions. A `@Component` only
so it can be injected; its tests instantiate it directly. Each side is parsed
independently into a `SideResult` — an action string or an `UnusableReason` — and
the two are combined. This sequence is the exact specification from
[plan/02-api-contract.md](plan/02-api-contract.md#deterministic-evaluation-rules)
and must be implemented in this order.

```java
private SideResult parse(byte[] completionBody) {
    JsonNode envelope = readTreeOrNull(completionBody);          // never throws
    JsonNode choice   = envelope == null ? null : envelope.path("choices").path(0);
    String finish     = choice == null ? null : choice.path("finish_reason").asText(null);
    String content    = choice == null ? null : choice.path("message").path("content").asText(null);

    if ("length".equals(finish)) return unusable(CONTENT_TRUNCATED);          // 1. truncation first
    if (content != null && utf8Length(content) >= maxEvalContentBytes) return unusable(CONTENT_TRUNCATED);
    if (content == null || content.isBlank()) return unusable(EMPTY_CONTENT); // 2. content
    String payload = stripSingleSurroundingFence(content).trim();             // 3. one fence, trim

    JsonNode value;                                                           // 4. exactly one value
    try (JsonParser parser = mapper.createParser(payload)) {
        value = mapper.readTree(parser);
        if (value == null || value.isMissingNode()) return unusable(NOT_JSON);
        if (parser.nextToken() != null)             return unusable(TRAILING_GARBAGE);
    } catch (IOException e) { return unusable(NOT_JSON); }
    if (!value.isObject()) return unusable(NOT_OBJECT);                       // 5. object required

    JsonNode action = value.get("action");                                    // 6. no coercion
    if (action == null || action.isNull()) return unusable(ACTION_ABSENT);
    if (!action.isTextual())               return unusable(ACTION_NOT_STRING);
    return SideResult.action(action.textValue());
}

public EvalResult compare(byte[] primaryBody, byte[] candidateBody) {
    SideResult p = parse(primaryBody), c = parse(candidateBody);
    if (!p.usable() && !c.usable()) return new EvalResult(BOTH_UNUSABLE, p.reason(), c.reason(), false);
    if (!p.usable())                return new EvalResult(PRIMARY_UNUSABLE, p.reason(), null, false);
    if (!c.usable())                return new EvalResult(CANDIDATE_UNUSABLE, null, c.reason(), false);
    if (p.action().equals(c.action())) return new EvalResult(MATCH, null, null, false);
    boolean near = p.action().trim().equalsIgnoreCase(c.action().trim());
    return new EvalResult(MISMATCH, null, null, near);
}

public static final String EVAL_VERSION = "1";
```

**Truncation is checked first**, before parsing, because a cut-off payload will
not parse and reporting it as malformed JSON blames the model for what is
actually a `max_tokens` misconfiguration — two findings that send an operator to
opposite places. One subtlety makes "before parsing" precise: the *envelope* must
be read first to see `finish_reason` at all, and it is the model's **content**
that is not parsed before the truncation verdict. An unreadable envelope, or one
without `choices[0].message.content`, yields `EMPTY_CONTENT`.

**A single surrounding fence is stripped**, because models emit `json` code
fences constantly and rejecting them would make the metric measure prompt
formatting rather than model behaviour. Exactly one *surrounding* fence, never
every fence in the string: stripping arbitrarily would let a payload be
reassembled out of prose, which the next rule exists to prevent.

**Exactly one JSON value is required, and we deliberately do not scan for the
first `{`.** Find-the-first-brace is the obvious way to be forgiving and is
precisely wrong: it is non-deterministic on adversarial output and will lift JSON
out of an example embedded in prose, so `Sure! For instance {"action":"refund"}
would be returned` scores as a match on a response that answered nothing.
Determinism is the entire point, and a rule whose output depends on where a brace
happens to appear is not deterministic in any useful sense.

Comparison is `String.equals` and nothing else — no trimming, no case folding, no
whitespace collapsing, no unicode normalisation — because a downstream consumer
doing `switch (action)` breaks on `"Refund"` where it expected `"refund"`.
Strictness has a cost, so `nearMatch` makes it visible; it is a flag on a
`MISMATCH`, never a verdict. `EVAL_VERSION` is surfaced in `/metric` and **bumped
on any behavioural change**, otherwise a fence-stripping fix moves the match rate
and somebody spends a week debugging a model regression that never happened.

Worked examples from
[plan/02-api-contract.md](plan/02-api-contract.md#worked-examples), one
parameterised test case each:

| Primary content | Candidate content | Verdict | Reason | `nearMatch` |
| --- | --- | --- | --- | --- |
| `{"action":"refund"}` | `{"action":"refund"}` | `MATCH` | — | `false` |
| `{"action":"refund"}` | `{"action":"Refund"}` | `MISMATCH` | — | `true` |
| `{"action":"refund"}` | the same in a `json` code fence | `MATCH` | — | `false` |
| `{"action":"refund","confidence":0.9}` | `{"action":"refund"}` | `MATCH` | — | `false` |
| `{"action":"refund"}` | `Sure! I'd refund that.` | `CANDIDATE_UNUSABLE` | `NOT_JSON` | `false` |
| `{"action":"refund"}` | `Here you go: {"action":"refund"}` | `CANDIDATE_UNUSABLE` | `TRAILING_GARBAGE` | `false` |
| `{"action":"refund"}` | cut off, `finish_reason: "length"` | `CANDIDATE_UNUSABLE` | `CONTENT_TRUNCATED` | `false` |
| `{"action":1}` | `{"action":"refund"}` | `PRIMARY_UNUSABLE` | `ACTION_NOT_STRING` | `false` |
| `{"action":"refund"}` | `[{"action":"refund"}]` | `CANDIDATE_UNUSABLE` | `NOT_OBJECT` | `false` |
| `{"action":"refund"}` | `{"reason":"policy"}` | `CANDIDATE_UNUSABLE` | `ACTION_ABSENT` | `false` |
| `{"action":"refund"}` | whitespace only | `CANDIDATE_UNUSABLE` | `EMPTY_CONTENT` | `false` |
| `Sure!` | `Also sure!` | `BOTH_UNUSABLE` | both `NOT_JSON` | `false` |

### Step 8 — shutdown, gauges, and the isolation rules

The queue is **discarded, not drained**: shadow work is best-effort by
definition, and holding a pod open to finish background evaluations delays every
deploy for no user benefit
([plan/06-production-readiness.md](plan/06-production-readiness.md#shutdown)).

```java
@PreDestroy
void shutdown() throws InterruptedException {
    executor.shutdown();                                     // stop accepting new work
    var discarded = new ArrayList<Runnable>();
    executor.getQueue().drainTo(discarded);                  // discard the backlog
    discarded.forEach(r -> ((ShadowTask) r).onDiscarded());  // SHUTTING_DOWN + release
    if (!executor.awaitTermination(props.shutdownGrace().toMillis(), MILLISECONDS)) {
        executor.shutdownNow();
    }
    governorScheduler.shutdownNow();
}
```

`SHUTDOWN_GRACE` applies to work already **in flight**, not to the queue: those
calls are already being paid for upstream, so letting them finish costs nothing.
Discarded jobs are counted so the number is visible rather than silently missing.
A worker may take a job between `shutdown()` and `drainTo`, which is harmless —
that job is in flight and covered by the grace instead.

`ExecutorShadowGauges` implements the frozen interface and is published as a
Spring bean; METRICS injects it as an `ObjectProvider<ShadowGauges>` with an
empty default, so METRICS compiles and boots with no dependency on EVAL and step
2 of the build order works before this component exists (see
[implementation-metrics.md](implementation-metrics.md)). Every method runs on the
scrape path and must be cheap and non-blocking.

| Method | Implementation | Note |
| --- | --- | --- |
| `queueDepth()` | `executor.getQueue().size()` | O(1) under the `ArrayBlockingQueue` lock |
| `queueCapacity()` | the configured constant | not `size() + remainingCapacity()`, which is racy |
| `bytesUsed()` / `bytesCapacity()` | `budget.used()` / capacity | a plain atomic read |
| `inFlight()` | our own `AtomicInteger` | **not** `getActiveCount()`, which takes the pool's `mainLock` |
| `workers()` | the configured constant | fixed pool, so it never changes |
| `currentAdmitProbability()` | `governor.admitProbability()` | volatile double |
| `breakerState()` | mapped from `breaker.getState()` | only the three contract strings |

`getActiveCount()` looks like the obvious `inFlight()` and is the one to avoid: it
acquires the executor's main lock, so a frequently scraped `/metric` contends
with submission and completion on the shadow path.

```java
@ArchTest static final ArchRule shadowAndEvalAreServletFree =
    noClasses().that().resideInAnyPackage("..shadow..", "..eval..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("jakarta.servlet..", "org.springframework.web..");

@ArchTest static final ArchRule shadowJobCarriesOnlyData =
    fields().that().areDeclaredInClassesThat().haveSimpleName("ShadowJob")
        .should(haveRawTypeIn(String.class, byte[].class, long.class));
```

The second rule is what would otherwise be enforced by hope. A reviewer can
verify "no worker touches the servlet request" by reading a record definition,
but only if nothing can ever be added to that record capable of holding one. Pin
the permitted field types rather than blacklisting servlet types, so a future
field of some third type that transitively reaches a request also fails.

## 4. Configuration read by this component

Consistent with the
[configuration table](implementation-contracts.md#configuration), where the
defaults and validation rules live.

| Variable | Default | Used for |
| --- | --- | --- |
| `DO_INFERENCE_API_KEY` | required | authorization on the candidate call (applied by the API-owned client) |
| `CANDIDATE_MODEL` | required | the `model` field on the shadow call |
| `DO_INFERENCE_BASE_URL` | `https://inference.do-ai.run` | candidate endpoint |
| `MAX_REQUEST_BYTES` | `131072` | request half of the reservation ceiling |
| `MAX_RESPONSE_BYTES` | `262144` | bounded read of the candidate response |
| `MAX_EVAL_CONTENT_BYTES` | `32768` | the `CONTENT_TRUNCATED` threshold |
| `SHADOW_TIMEOUT` | `20s` | candidate read timeout; also the drain-rate bootstrap |
| `CONNECT_TIMEOUT` | `2s` | candidate connect timeout |
| `SHADOW_QUEUE_SIZE` | `32` | `ArrayBlockingQueue` capacity — the count gate |
| `SHADOW_WORKERS` | `32` | core and max pool size — concurrent candidate calls |
| `SHADOW_BUDGET_BYTES` | `8388608` | `ByteBudget` capacity — the byte gate |
| `SHADOW_TASK_TTL` | `30s` | staleness check at dequeue — the lifetime gate |
| `SHADOW_SAMPLE_RATE` | `1.0` | operator ceiling, multiplied into the governor's probability |
| `SHADOW_CONTROL_ARM_RATE` | `0.01` | share of jobs sent to the primary model for the A/A floor |
| `SHADOW_BREAKER_FAILURE_RATE` | `50` | breaker threshold, percent, over a 20-call window |
| `SHADOW_BREAKER_WAIT` | `60s` | how long the breaker stays open |
| `SHUTDOWN_GRACE` | `15s` | `awaitTermination` for in-flight tasks |
| `CANDIDATE_QUOTA_SHARE` | `0.4` | derivation input for `SHADOW_WORKERS`; no runtime effect here |
| `PRIMARY_MODEL` | required | the A/A control arm's target model — see section 9 |

## 5. Failure modes

The last column is the point of the component: it is the same in every row.

| Situation | What happens | Counted as | Customer sees |
| --- | --- | --- | --- |
| Candidate returns 500 | no retry; breaker records a failure | `completed(ERROR)` | nothing |
| Candidate exceeds `SHADOW_TIMEOUT` | abandoned at the read timeout; breaker failure | `completed(TIMEOUT)` | nothing |
| Candidate returns 429 | ordinary failure, never retried; sustained 429s trip the breaker | `completed(ERROR)` | nothing |
| Candidate returns non-JSON | the call succeeded, so the evaluator runs and attributes the fault per side | `completed(OK)` + `CANDIDATE_UNUSABLE` | nothing |
| Primary output unusable | evaluated normally; the primary side carries the reason | `PRIMARY_UNUSABLE` or `BOTH_UNUSABLE` | nothing — the body was already relayed |
| Breaker open | shed at admission, before any reservation | `shed(BREAKER_OPEN)` | nothing |
| Queue full | handler counts, releases the reservation, returns | `shed(QUEUE_FULL)` | nothing |
| Byte budget exhausted | CAS reservation fails; no queue slot taken | `shed(BYTE_BUDGET)` | nothing |
| Job older than `SHADOW_TASK_TTL` | dropped at dequeue, before the candidate call | `shed(STALE_IN_QUEUE)` | nothing |
| `SIGTERM` with a non-empty queue | queue discarded, in-flight work gets `SHUTDOWN_GRACE` | `shed(SHUTTING_DOWN)` | nothing |
| Unexpected `Throwable` in a worker | caught per task; the worker survives and keeps draining | `completed(ERROR)` | nothing |

Unusable output is excluded from `exact_match_rate` rather than counted as a
mismatch, with both populations reported alongside: a candidate emitting prose
has a *formatting* problem and one emitting a different action has a *semantic*
one, and averaging them produces a number that drives neither fix.

## 6. How this bounds the memory footprint

```
reservation_i    = len(requestBody) + len(primaryResponseBody) + 512 B
invariant (CAS)  : sum of reservation_i over admitted-but-unfinished jobs
                   <= SHADOW_BUDGET_BYTES,  at every observation
peak shadow heap ~= SHADOW_BUDGET_BYTES + SHADOW_WORKERS x MAX_RESPONSE_BYTES
                 ~= 8 MiB + 32 x 256 KiB = 16 MiB of application-controlled state
```

Every term is a configured constant, each for a reason independent of traffic.
`SHADOW_BUDGET_BYTES` covers **queued and in-flight jobs alike**, because the
reservation is taken at admission and released only in the task's `finally` — one
term, not two, with no way for a job to be retained without being charged. The
invariant holds at every observation, not merely eventually, because `tryReserve`
is a compare-and-set loop. `SHADOW_WORKERS` fixes the number of concurrent
candidate calls, so at most that many response buffers exist at once regardless
of arrival rate, each bounded by `MAX_RESPONSE_BYTES`. `SHADOW_TASK_TTL` bounds
item *lifetime*, without which the other gates are insufficient: tasks stuck
against a degraded candidate pin their reservations indefinitely, and a queue
bounded in count and bytes but not in time still leaks. Nothing here is an
unbounded collection — in particular the queue is an `ArrayBlockingQueue` at
fixed capacity, not the `LinkedBlockingQueue` `Executors.newFixedThreadPool`
would have supplied. And submission never blocks, so backpressure is absorbed by
dropping work rather than by parking request threads that each keep a body
reachable.

Two caveats. The per-job ceiling in
[plan/01-architecture.md](plan/01-architecture.md#how-this-bounds-the-memory-footprint)
uses `MAX_EVAL_CONTENT_BYTES`, but the frozen `ShadowJob` retains a whole
`primaryResponseBody` capped by `MAX_RESPONSE_BYTES`, so the honest ceiling is
about 384.5 KiB. That does **not** weaken the aggregate bound: the invariant is
enforced on measured lengths, and the ceiling only sets the smallest
`SHADOW_BUDGET_BYTES` at which a maximal job is still admissible, where 8 MiB has
ample margin. Second, this bounds the memory *this design controls*; JVM baseline
footprint is typically 150–250 MB and dominates, so size the container from
[plan/06-production-readiness.md](plan/06-production-readiness.md#capacity-and-deployment).

## 7. Tests

JUnit 5, WireMock for the upstream, Awaitility for asynchronous assertions. The
evaluator tests need no Spring context and must stay that way.

**Evaluator, parameterised.** One case per row of the worked-examples table,
asserting the full `EvalResult`: verdict, both per-side reasons, and `nearMatch`.
Asserting only the verdict would let a reason regression through, and the reasons
are what an operator reads.

**Evaluator properties.** *Determinism*: 1,000 repeated invocations on a fixed
input pair return an equal result. *Reflexivity*: `compare(x, x)` never returns
`MISMATCH` for any `x`, including malformed, empty and truncated bodies — a
violation means the parser produced two different actions from identical bytes,
the failure that would silently corrupt the match rate. *Fuzzing*: hundreds of
thousands of arbitrary byte arrays including invalid UTF-8, deeply nested JSON,
lone surrogates and 1 MiB inputs, asserting only that no exception escapes; an
NPE in a worker would be counted as a shadow error and be near-impossible to
attribute.

**Queue-full shedding returns immediately.** Block every worker on a
`CountDownLatch`, fill the queue to capacity, submit one more, and assert
`submit` returns in well under a millisecond, `shed_by_reason.queue_full`
incremented by exactly one, `enqueued` did not increment, and the budget did not
grow. Release the latch and assert the budget returns to zero.

**Byte-budget invariant under contention.** Hammer `tryReserve`/`release` from 64
threads with randomly sized reservations while an observer samples `used()`
continuously. No observation may ever exceed `SHADOW_BUDGET_BYTES` — one
violating sample fails the test — and after every thread has released, `used()`
must be **exactly zero**. A non-zero residue is a reservation leak, precisely the
bug that would invalidate the memory bound.

**Burst accounting.** Fire N requests at a size-2 queue and assert
`enqueued + shed == eligible` exactly, that every shed carries a reason, and that
thread count stays within `SHADOW_WORKERS` plus a fixed baseline. An identity
that always holds is the cheapest guard against a gate that returns without
counting.

**A slow candidate does not move primary latency.** With a WireMock candidate
sleeping 30 seconds, primary latency is indistinguishable from a healthy run. The
real gate is the k6 distribution comparison in
[plan/06-production-readiness.md](plan/06-production-readiness.md#proving-the-isolation-claim).

**Evaluation completes after the client response is finished.** Issue a request,
read the full body, close the connection, then use Awaitility to assert the
evaluation counter increments afterwards. If a worker ever reaches for
`HttpServletRequest` or a request-scoped bean, this is the test that catches it.

**Outcome classification.** WireMock cases for candidate 500, 429, hanging past
`SHADOW_TIMEOUT`, and non-JSON, asserting `ERROR`/`ERROR`/`TIMEOUT`/`OK`-plus-
`CANDIDATE_UNUSABLE`, with `verify(exactly(1), ...)` proving no retry.

**Breaker.** Twenty consecutive failures open it; subsequent submissions shed as
`breaker_open` with no upstream request; after `SHADOW_BREAKER_WAIT` it reaches
`HALF_OPEN` **with no call having been attempted**.

**Executor shape.** `getQueue().remainingCapacity() == SHADOW_QUEUE_SIZE` on an
idle pool, `getCorePoolSize() == getMaximumPoolSize() == SHADOW_WORKERS`, every
pool thread a daemon. **ArchUnit** rules run in the normal `test` task so they
cannot be skipped locally.

## 8. Exit checks

1. `./gradlew test` green, including both ArchUnit rules.
2. `getQueue().remainingCapacity()` on the idle pool equals `SHADOW_QUEUE_SIZE`,
   asserted by a test rather than checked by eye.
3. With a candidate stub sleeping 30 s, primary p99 is unchanged against a
   healthy-candidate baseline.
4. A burst of 100 requests against a size-2 queue satisfies
   `enqueued + shed == eligible`, every shed has a reason, thread count flat.
5. After any test drains, `ByteBudget.used()` is exactly zero, and `bytes_used`
   never exceeded `bytes_capacity` in any scrape during the burst.
6. Killing the candidate for 20 calls flips `breaker_state` to `"open"`, and it
   returns to `"half_open"` after `SHADOW_BREAKER_WAIT` with no traffic.
7. `SIGTERM` under load reports non-zero `shutting_down`, and the process exits
   within `SHUTDOWN_GRACE` plus a small margin.
8. Every worked example in
   [plan/02-api-contract.md](plan/02-api-contract.md#worked-examples) has a
   passing test asserting the full `EvalResult`.
9. WireMock confirms `X-Shadow-Request: true` on every candidate call.

## 9. Risks

**A reservation leak.** The highest-severity bug available here: silent,
accumulating only under the load that matters, ending in permanent `byte_budget`
shedding. Mitigated by keeping every release in a `finally` or in the rejection
handler — never in a normal return path — and by the exactly-zero assertion.

**Counting an enqueue the handler shed.** Because the rejection handler returns
normally, `execute()` gives the caller no signal; drop the `wasRejected()` check
in a refactor and `enqueued` over-counts, so `coverage_pct` reports evaluations
that never happened. The `enqueued + shed == eligible` identity catches it.

**A breaker that never recovers.** With admission-side shedding no call is
attempted while open, so without `automaticTransitionFromOpenToHalfOpenEnabled`
one sustained candidate outage disables shadowing until the next deploy.

**Governor starvation.** A control loop whose input is its own output can latch:
throttling reduces completions, which reduces the drain estimate, which throttles
further. The 1% floor breaks the loop and the bootstrap drain rate keeps the
first ticks off zero. A `current_admit_probability` pinned at the floor under
light load is this bug.

**The A/A arm contaminating the A/B numbers.** A control-arm result routed to
`recordEvaluation` inflates the match rate by roughly its share, invisibly. One
`if` in step 5, plus a test at `SHADOW_CONTROL_ARM_RATE=1.0` asserting
`evaluation.evaluated == 0`.

**Evaluator strictness read as a model regression.** Case-sensitive comparison
means a candidate returning `"Refund"` scores 0%. That is the intended answer and
it looks like catastrophic model failure; `near_matches` and the per-side
`unusable_by_reason` breakdown put the real explanation one field away, and
`EVAL_VERSION` keeps a change in this logic from being misread as a model change.

**Scrape-path contention.** `ShadowGauges` runs on every `/metric` request, so a
well-meaning `inFlight()` as `getActiveCount()`, or `queueCapacity()` as
`size() + remainingCapacity()`, puts the pool's main lock on the scrape path.

### Inconsistencies found, reported not fixed

- **`PRIMARY_MODEL` is marked "read by API"** in the
  [configuration table](implementation-contracts.md#configuration), but the A/A
  control arm requires EVAL to read it too. This plan assumes EVAL reads it.
- **The per-job reservation ceiling** in
  [plan/01-architecture.md](plan/01-architecture.md#how-this-bounds-the-memory-footprint)
  uses `MAX_EVAL_CONTENT_BYTES` while `ShadowJob` retains a full response body
  capped by `MAX_RESPONSE_BYTES`. Analysed in section 6; the aggregate invariant
  is unaffected.
- **`EVAL_VERSION` has no seam in the frozen block.** It is declared in `eval` and
  must appear in the `/metric` payload owned by METRICS, but `ShadowGauges` has no
  method for it. This plan assumes METRICS obtains it by the same bean-publication
  route as the gauges; the setter belongs to
  [implementation-metrics.md](implementation-metrics.md).
- **`completed` versus `evaluated` with a live control arm.** This plan records
  `recordShadowCompleted` for control-arm jobs — they consumed a worker and an
  upstream call — while their comparison goes only to
  `recordControlArmEvaluation`, so `completed` exceeds `evaluated` by the
  control-arm count. The illustrative payload in
  [plan/02-api-contract.md](plan/02-api-contract.md) shows them equal, which holds
  only when the arm is disabled.

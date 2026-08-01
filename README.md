# llm-shadow-proxy

An HTTP API proxy that serves customer traffic from a **primary** LLM and returns
that response unchanged, while asynchronously mirroring the exact same request to
a **candidate** LLM in the background. The two outputs are compared with a
deterministic heuristic — both must parse as JSON, and their `action` fields must
match exactly — and the running comparison is exposed live on `GET /metric`. The
shadow path is strictly best-effort and structurally isolated, so candidate
latency, candidate failures, and traffic bursts can never delay the customer's
response or grow the heap without bound.

## Status

**Design complete, implementation in progress.** This repository currently
contains the specification in [docs/Objective.md](docs/Objective.md), the design
reasoning under [docs/plan/](docs/plan/), and the build instructions in
`docs/implementation-*.md` — one per component, over a shared contract. There is
no application code yet: the commands, endpoints, and configuration below
describe the system as designed and specify how it will be set up and run once
those plans are executed. Nothing here should be read as a description of running
software.

## How it works

A request arrives at `POST /v1/chat`. The controller reads the body once into a
`byte[]`, forwards it to the primary model on DigitalOcean Serverless Inference
with the `model` field overridden by `PRIMARY_MODEL`, and relays the upstream
status and body back to the caller verbatim — errors included, because the proxy
is transparent rather than opinionated.

Once the primary response is in hand, an immutable `ShadowJob` carrying only
copied bytes is handed to `ShadowDispatcher.submit(...)` and the response is
returned. That call either admits the job onto a bounded queue instantly or sheds
it; it never blocks, never throws, and carries no reference to the servlet
request. The isolation guarantee rests on that signature rather than on ordering:
there is nothing in `submit` for the customer's response to wait on, because it
cannot call the candidate, cannot wait for queue capacity, and cannot fail. A
fixed pool of worker threads later dequeues the job, calls the candidate model,
runs the deterministic evaluator, and folds the outcome into in-process counters.
If the primary call failed, no shadow job is submitted at all — there is nothing
to compare against.

```mermaid
flowchart LR
  Client(["Client"])

  subgraph proxy ["shadow-proxy: one container, one JVM"]
    Chat["ChatController for POST /v1/chat"]
    Metric["MetricController for GET /metric"]
    Dispatcher["ShadowDispatcher.submit"]
    Queue[["ArrayBlockingQueue, bounded"]]
    Workers["ThreadPoolExecutor, fixed size"]
    Evaluator["Evaluator, deterministic heuristic"]
    Registry[("LongAdder metrics registry")]
  end

  DO{{"DigitalOcean Serverless Inference"}}

  Client -->|"chat request"| Chat
  Chat ==>|"synchronous, model = PRIMARY_MODEL"| DO
  DO ==>|"completion"| Chat
  Chat ==>|"relayed verbatim"| Client
  Chat -.->|"submit copied bytes, never blocks"| Dispatcher
  Dispatcher --> Queue
  Queue --> Workers
  Workers -->|"asynchronous, model = CANDIDATE_MODEL"| DO
  Workers --> Evaluator
  Chat --> Registry
  Workers --> Registry
  Evaluator --> Registry
  Registry --> Metric
  Metric -->|"live summary"| Client
```

The heavy edges are the customer's critical path and the dotted edge is the
isolation boundary — the only point at which the synchronous and asynchronous
worlds meet, crossed in one direction, without blocking.

The full diagram set, including the request lifecycle and the admission and
load-shedding pipeline, is in [docs/architecture.md](docs/architecture.md). The
reasoning behind every decision is in [docs/plan/](docs/plan/).

## Requirements

- **JDK 21** (LTS). The service targets Java 21 and Spring Boot 4.1.
- **Docker** with Compose, for the container build and the local stub environment.
- **Gradle is not required.** The Gradle Wrapper (`./gradlew`) bootstraps a
  pinned, checksum-verified distribution, so a clone builds with nothing but a
  JDK installed.

## Setup

### 1. Obtain a DigitalOcean Serverless Inference key

The upstream is DigitalOcean Serverless Inference at
`https://inference.do-ai.run`, whose `POST /v1/chat/completions` endpoint is
OpenAI-compatible and authenticated with `Authorization: Bearer`. Create a model
access key for the models you intend to run and export it as
`DO_INFERENCE_API_KEY`.

[docs/plan/06-production-readiness.md](docs/plan/06-production-readiness.md#credential-handling)
recommends issuing a **separate model access key per role**, because DigitalOcean
model access keys are scoped to specific models: that turns quota isolation
between primary and candidate from an in-process concern into an
upstream-enforced boundary. Keys are supplied as environment variables from a
secret store, are never baked into the image or written to disk, and are redacted
from the startup configuration log.

### 2. Configure

Every setting is an environment variable bound to a validated
`@ConfigurationProperties` record and checked at startup; the application refuses
to boot on invalid input rather than failing per request. Defaults are taken from
[docs/plan/04-operations.md](docs/plan/04-operations.md#configuration), where the
concurrency constants are also derived from the upstream rate limit by Little's
law rather than chosen.

| Variable | Default | Purpose |
| --- | --- | --- |
| `DO_INFERENCE_API_KEY` | **required** | Bearer token for DigitalOcean Serverless Inference |
| `PRIMARY_MODEL` | **required** | Model serving customer traffic, e.g. `llama3.3-70b-instruct` |
| `CANDIDATE_MODEL` | **required** | Model under evaluation |
| `DO_INFERENCE_BASE_URL` | `https://inference.do-ai.run` | Upstream base URL; pointed at WireMock locally |
| `SERVER_PORT` | `8080` | Bind port |
| `MANAGEMENT_SERVER_PORT` | `9090` | Actuator and Prometheus port, not published externally |
| `MAX_CONCURRENT_REQUESTS` | `128` | In-flight inbound request cap; beyond it the service returns `503` |
| `CONNECT_TIMEOUT` | `2s` | Connect timeout for both upstream pools |
| `PRIMARY_TIMEOUT` | `30s` | Read timeout on the customer path |
| `SHADOW_TIMEOUT` | `20s` | Read timeout for a candidate call |
| `UPSTREAM_RPM_LIMIT` | `250` | Documented DigitalOcean default; tiers differ, so it is configuration |
| `CANDIDATE_QUOTA_SHARE` | `0.4` | Share of upstream quota the shadow path may consume |
| `SHADOW_QUEUE_SIZE` | `32` | `ArrayBlockingQueue` capacity; the count gate. Held equal to `SHADOW_WORKERS` and under `drain_rate x SHADOW_TASK_TTL`, so a job that is admitted can still be run before it expires |
| `SHADOW_WORKERS` | `32` | Fixed pool size; the cap on concurrent candidate calls |
| `SHADOW_BUDGET_BYTES` | `8388608` | Byte ceiling on admitted-but-unfinished work; the byte gate |
| `SHADOW_TASK_TTL` | `30s` | Jobs older than this are dropped at dequeue; the lifetime gate |
| `SHADOW_SAMPLE_RATE` | `1.0` | Operator ceiling on eligibility; `1.0` mirrors every request |
| `SHADOW_CONTROL_ARM_RATE` | `0.01` | Fraction of shadow jobs sent to the primary, giving an A/A noise floor |
| `MAX_REQUEST_BYTES` | `131072` | Inbound body cap; exceeding it returns `413` |
| `MAX_RESPONSE_BYTES` | `262144` | Cap on bytes read from an upstream response |
| `MAX_EVAL_CONTENT_BYTES` | `32768` | Assistant content retained for comparison |
| `SHADOW_BREAKER_FAILURE_RATE` | `50` | Candidate circuit breaker failure percentage over a 20-call window |
| `SHADOW_BREAKER_WAIT` | `60s` | How long the candidate breaker stays open |
| `SHUTDOWN_GRACE` | `15s` | Grace for in-flight primary requests; the shadow queue is discarded |
| `ALLOW_AA_MODE` | unset | Permits `PRIMARY_MODEL == CANDIDATE_MODEL`, which otherwise refuses to boot |
| `LOG_LEVEL` | `info` | Request and response bodies are never logged at this level |

### 3. Run

```bash
export DO_INFERENCE_API_KEY=...
export PRIMARY_MODEL=llama3.3-70b-instruct
export CANDIDATE_MODEL=openai-gpt-oss-120b

./gradlew bootRun
```

## Running with Docker

```bash
docker compose up
```

Compose starts two containers: `proxy`, built from the local multi-stage
`Dockerfile`, and `mock-inference`, a WireMock stand-in for
`https://inference.do-ai.run` with configurable latency and failure injection.
`DO_INFERENCE_BASE_URL` is the only variable that differs between this
environment and production, which keeps the local demo honest.

This is how load shedding, timeouts, and mismatches are demonstrated **without an
API key and without spending tokens**. See
[docs/plan/05-service-topology.md](docs/plan/05-service-topology.md#docker-topology).

## API usage

### `POST /v1/chat`

The `model` field in the request is optional and ignored — routing is decided by
server configuration so that primary and candidate are always compared on
identical input.

```bash
curl -s localhost:8080/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [
      {"role": "system", "content": "Reply with JSON: {\"action\": \"...\"}"},
      {"role": "user", "content": "The user wants to cancel their order."}
    ],
    "temperature": 0,
    "max_completion_tokens": 256
  }'
```

The upstream response is relayed verbatim. Nothing about the shadow evaluation
appears in it — no extra fields, no extra headers, no added latency.

### `GET /metric`

```bash
curl -s localhost:8080/metric | jq
```

Abbreviated response; the full field list and the rationale for each field are in
[docs/plan/02-api-contract.md](docs/plan/02-api-contract.md#get-metric).

```json
{
  "schema_version": "1",
  "eval_version": "1",
  "scope": "replica_local",
  "uptime_seconds": 3600,
  "total_requests": 1482,
  "shadow": {
    "eligible": 1470,
    "enqueued": 1301,
    "coverage_pct": 88.50,
    "shed_total": 169,
    "shed_by_reason": {
      "sampled_out": 0, "queue_full": 121, "byte_budget": 8,
      "breaker_open": 0, "stale_in_queue": 40, "shutting_down": 0
    },
    "completed": 1288,
    "errors": 9,
    "timeouts": 4,
    "queue_depth": 12,
    "queue_capacity": 32,
    "bytes_used": 61440,
    "bytes_capacity": 8388608,
    "breaker_state": "closed"
  },
  "evaluation": {
    "evaluated": 1288,
    "exact_matches": 1174,
    "mismatches": 90,
    "exact_match_rate": 92.88,
    "exact_match_rate_basis": "exact_matches / (exact_matches + mismatches)",
    "exact_match_rate_ci95": { "low": 91.30, "high": 94.19, "method": "wilson" }
  }
}
```

`coverage_pct` and the Wilson interval are present because a match rate over 3%
of traffic and one over 100% look identical without them, and 89% from 50 samples
is noise while 89% from 50,000 is a fact.

## How this bounds the memory footprint

This section is required by [docs/Objective.md](docs/Objective.md), and it is the
part of the design worth reading closely. The claim is specific: **peak retained
state is a product of configured constants, not of traffic.** No amount of
inbound load changes the ceiling; it only changes how much work is discarded to
stay under it.

Five mechanisms combine to make that true.

**The shadow queue is bounded twice.** `SHADOW_QUEUE_SIZE` is the capacity of an
`ArrayBlockingQueue` allocated once at startup, so at most that many jobs are
retained — notably *not* the unbounded `LinkedBlockingQueue` that
`Executors.newFixedThreadPool` would have supplied, which would let a backlog
grow until `OutOfMemoryError`. A count gate alone still permits a large swing in
footprint depending on payload size, so a second gate bounds the actual bytes:
`SHADOW_BUDGET_BYTES` is an `AtomicLong` reserved with a **compare-and-set loop**,
so the budget is never exceeded even transiently. The reservation is taken at
admission and released in a `finally` block only when the task finishes, which is
why one term covers queued and in-flight work alike.

**Submission never blocks; it sheds.** The `RejectedExecutionHandler` is
deliberately neither `CallerRunsPolicy` — which would run the candidate call on
the request thread and hand the customer the candidate's latency — nor
`AbortPolicy`, which would make shedding an exception on the request path.
Shedding is a normal outcome, so it increments a counter and returns.
Backpressure is absorbed by dropping work rather than by parking request threads
that each keep a request body reachable.

**Dropping is uniform random, so the sample stays unbiased.** A queue that simply
drops whatever arrives once it is full admits jobs from the *beginning* of every
burst and rejects the *tail*. Bursts are not random samples of production
traffic — they line up with peak hours, retry storms, and individual heavy
customers — so tail-dropping systematically skews the surviving sample and turns
`exact_match_rate` into a biased estimator of the very number this service exists
to report, while the figure still looks perfectly precise. Admission is therefore
uniform random against a probability recomputed once a second,
`min(1, sustainable_drain_rate / ewma(arrival_rate))`, floored so measurement
never stops entirely. Under a 50x burst roughly 98% of shadow jobs are dropped,
but dropped at random, so the match rate remains a valid estimator.

**Worker concurrency is fixed.** `SHADOW_WORKERS` platform threads, pre-sized and
never growing, cap the number of concurrent candidate calls, candidate
connections, and in-flight response buffers regardless of arrival rate. A fixed
pool rather than a virtual thread per request is precisely what makes this a
bound.

**A task TTL closes the unbounded-lifetime leak.** A bounded queue with unbounded
item *lifetime* still leaks, because a handful of slow tasks pin their
reservations indefinitely. `SHADOW_TASK_TTL` is checked at dequeue, before the
candidate is called, and stale jobs are dropped as `stale_in_queue` rather than
spending upstream quota on a sample that is no longer current. This is the leak
vector most often forgotten, and it is sufficient on its own to turn a "bounded"
queue into an unbounded one.

**Inbound concurrency is capped separately.** Bounding the shadow path is
necessary but not sufficient. Virtual threads impose no limit of their own, so a
slow primary would let arriving requests accumulate without limit, each holding a
body: the shadow subsystem would stay inside its ceiling while the process died
anyway. A semaphore filter caps in-flight requests at `MAX_CONCURRENT_REQUESTS`
and returns `503` with `Retry-After` immediately beyond it.

### The formula

```
reservation_i     =  len(requestBody) + len(primaryContent) + overhead
                  <= MAX_REQUEST_BYTES + MAX_EVAL_CONTENT_BYTES + 512 B

invariant (CAS)   :  sum(reservation_i) <= SHADOW_BUDGET_BYTES
                     over every admitted-but-not-yet-completed job

peak heap        ~=  MAX_CONCURRENT_REQUESTS x (MAX_REQUEST_BYTES + MAX_RESPONSE_BYTES)
                   + SHADOW_BUDGET_BYTES
                   + SHADOW_WORKERS x (MAX_RESPONSE_BYTES + per-thread overhead)
```

At the documented defaults:

```
inbound   :  128 x (128 KiB + 256 KiB)  =  48 MiB
shadow    :  SHADOW_BUDGET_BYTES        =   8 MiB
in-flight :   32 x 256 KiB              =   8 MiB
                                          --------
application-controlled state           ~=  65 MB
```

Every term is a configured constant. There is no traffic-dependent term anywhere
in it.

### The honest caveat

Application-controlled state is bounded at roughly **65 MB**, but on the JVM the
**baseline footprint dominates the total**: heap reserve, metaspace, code cache,
thread stacks, and GC structures are typically **150–250 MB** before the
application stores a single byte. The formula above genuinely bounds the part of
memory this design controls — the part that could otherwise grow without limit —
but it does not make the container small, and it is not the sizing input.

**Container sizing:** set the memory limit to **768 MB** with
`-XX:MaxRAMPercentage=70`, letting the JVM size its heap from the container limit
rather than hard-coding `-Xmx`. Treat the bounded terms as the guarantee that load
cannot push you past that limit, not as the number you size to. Derivation in
[docs/plan/06-production-readiness.md](docs/plan/06-production-readiness.md#capacity-and-deployment).

## Testing

```bash
./gradlew build                  # spotless, ArchUnit, unit and integration tests, assemble
./gradlew test integrationTest   # the suites individually
./gradlew spotlessApply          # format
```

Unit and integration tests cover the evaluator's rules, the byte-budget invariant
under concurrent admission, burst behaviour against a size-2 queue, and the chaos
cases (candidate 500, candidate hang past timeout, candidate non-JSON, candidate
`429`, primary failure with a healthy candidate). An ArchUnit rule fails the build
if anything under `shadow` or `eval` imports `jakarta.servlet` or
`org.springframework.web`, making the isolation guarantee structural rather than
reviewed.

The latency-isolation claim is **not** asserted by a unit test. It is proven by a
k6 load gate in `perf/`, which runs the same load twice — once with a healthy
candidate, once with the candidate stub injected at 30s latency and a 100% error
rate — and compares the whole primary-latency distributions with a two-sample
Kolmogorov–Smirnov test rather than a single percentile, because shadow
interference would most plausibly appear as a fattened tail. A non-vacuity gate
asserts `shadow_shed_total > 0`, so the test cannot pass by never stressing the
path it claims to certify. Details in
[docs/plan/06-production-readiness.md](docs/plan/06-production-readiness.md#proving-the-isolation-claim).

## Project layout

```
.
├── gradlew, gradle/            Gradle Wrapper, version catalog, dependency verification
├── build.gradle.kts, settings.gradle.kts
├── src/main/java/com/example/shadowproxy/
│   ├── config/                 validated properties, RestClient beans, bounded executor
│   ├── inference/              DigitalOcean Serverless Inference client
│   ├── proxy/                  ChatController and the exception handler
│   ├── shadow/                 ShadowJob, ShadowDispatcher, ShadowTask
│   ├── eval/                   deterministic evaluator, pure and stateless
│   ├── metrics/                LongAdder registry, snapshot, MetricController
│   └── web/                    concurrency cap, request id, request size limit filters
├── src/test/java/...           unit, integration, and ArchUnit suites
├── perf/                       k6 latency-isolation load gate
├── docs/                       Objective, architecture diagrams, plan
├── .github/workflows/ci.yml
├── Dockerfile, docker-compose.yaml
└── README.md
```

## Limitations and what is not in v1

- **No inbound authentication.** The service ships with no client authentication
  and no per-key rate limiting on either route. Anyone who can reach the port can
  spend the inference budget through `POST /v1/chat` and read volume, error rates,
  and candidate match quality from `GET /metric`. **Until authentication ships,
  the service must not be exposed to an untrusted network** — put it behind a
  gateway or a private network boundary. The deferred design and the full argument
  are in
  [docs/plan/06-production-readiness.md](docs/plan/06-production-readiness.md#deferred-inbound-authentication-and-rate-limiting).
  Note that `MAX_CONCURRENT_REQUESTS` and its `503` are an inbound memory bound,
  not an access control, and should not be mistaken for one.
- **Streaming is rejected.** `"stream": true` returns `400`. Shadow comparison
  requires a complete JSON body from both models; supporting streaming would mean
  buffering and re-emitting full responses, which is out of scope for v1. See
  [docs/plan/02-api-contract.md](docs/plan/02-api-contract.md#status-codes).
- **Metrics are per-replica and reset on restart.** Counters are in-process
  `LongAdder` values, so `/metric` reports one replica's view — `scope` and
  `replica_id` say so in the payload — and history does not survive a restart.
  The Objective asks for a real-time summary, not durable history; cross-replica
  aggregation is Prometheus's job via the separate management port.
- **No persistence of mismatched payloads and no runtime `PUT /config`.** Both
  are listed under "Next" in [docs/Objective.md](docs/Objective.md) rather than in
  v1 scope.
- **Evaluation is deterministic, not semantic.** The heuristic compares one string
  field. It answers "does the candidate choose the same action" and nothing about
  response quality.

## Documentation

- [docs/Objective.md](docs/Objective.md) — the frozen specification. Do not edit.
- [docs/architecture.md](docs/architecture.md) — the architecture flow diagrams:
  system context, component map, request lifecycle, admission and load shedding.

**Build instructions**, one per component, plus the contract they share:

- [docs/implementation-contracts.md](docs/implementation-contracts.md) — the frozen cross-component types, the config surface, and the build order.
- [docs/implementation-api-proxy.md](docs/implementation-api-proxy.md) — `POST /v1/chat`, the upstream client, the servlet filters.
- [docs/implementation-eval-shadow.md](docs/implementation-eval-shadow.md) — admission, the bounded executor, the candidate call, the evaluator.
- [docs/implementation-metrics.md](docs/implementation-metrics.md) — the counter registry, the snapshot, `GET /metric`.

**Design reasoning**, kept as the record of how the above was arrived at:

- [docs/plan/README.md](docs/plan/README.md) — index, locked-in decisions, non-goals.
- [docs/plan/01-architecture.md](docs/plan/01-architecture.md) — components, concurrency, the memory model.
- [docs/plan/02-api-contract.md](docs/plan/02-api-contract.md) — endpoint contracts, payloads, evaluation rules.
- [docs/plan/03-implementation-plan.md](docs/plan/03-implementation-plan.md) — phased build plan and milestones.
- [docs/plan/04-operations.md](docs/plan/04-operations.md) — configuration, CI/CD, deployment, observability, runbook.
- [docs/plan/05-service-topology.md](docs/plan/05-service-topology.md) — one service vs three, frozen contracts, Docker topology.
- [docs/plan/06-production-readiness.md](docs/plan/06-production-readiness.md) — security, resilience, SLOs, supply chain, go-live checklist.
- [docs/plan/07-design-review.md](docs/plan/07-design-review.md) — independent review, what was adopted and what was rejected.

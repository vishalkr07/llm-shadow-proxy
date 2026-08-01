# Production Readiness

The Objective calls this "a production ready API proxy." The other documents
describe a service that is functionally correct; this one covers what has to be
true before it can carry real customer traffic and real spend.

Two things are worth stating plainly up front, because they are corrections to
earlier drafts of this plan rather than additions:

- **Connection pools must be separate per upstream.** An earlier draft said the
  two `RestClient` beans share one pool. That is a defect: eight shadow workers
  holding connections could starve the customer path of the very connection pool
  it needs, which breaks the isolation guarantee through a back door that no
  amount of queue bounding would catch.
- **The inbound side was unbounded.** The memory analysis in
  [01-architecture.md](01-architecture.md) bounds the *shadow* path rigorously
  and says nothing about inbound concurrency. With virtual threads, a slow
  primary means arriving requests accumulate without limit, each holding a body.
  The shadow path would stay within its ceiling while the process died anyway.

## Security

### Credential handling

The client's `Authorization` header is consumed by us and **never forwarded
upstream**. The upstream call carries our `DO_INFERENCE_API_KEY` and nothing
else. Neither key is ever logged: a logging filter redacts `Authorization` and
`Proxy-Authorization` unconditionally, and the startup configuration dump prints
the key as `****`.

Keys come from the orchestrator's secret store as environment variables. They are
never baked into the image, never written to disk, and never appear in
`docker-compose.yaml` outside the local mock profile.

**Use separate model access keys for the primary and the candidate.**
DigitalOcean model access keys are scoped to specific models, so issuing one per
role turns quota isolation from an in-process concern into an upstream-enforced
boundary: a runaway shadow path physically cannot consume the primary's quota,
even if our own throttle has a bug. Enforcing an invariant in the system that
owns the resource beats enforcing it in the system that consumes it.

### Prompt data

Request bodies contain customer prompts and are likely to contain personal data.
Bodies are never logged at `INFO` or below. Body logging exists only at `TRACE`
and is disabled in production by configuration, not by convention. Bodies live in
memory only for the lifetime of a request plus its shadow job, and are never
persisted anywhere.

Candidate calls carry `X-Shadow-Request: true` so mirrored traffic is
distinguishable in any upstream logging or billing breakdown.

### Deferred: inbound authentication and rate limiting

**Not in v1 scope.** The service ships with no inbound authentication and no
per-key rate limiter. The design is recorded here rather than dropped, so that
whoever picks it up does not have to rediscover the argument, and so the gap is
visible instead of implicit. **Until this ships, the service must not be exposed
to an untrusted network** — put it behind a gateway or a private network
boundary.

The reasoning is unchanged by the deferral. `POST /v1/chat` fronts a metered,
paid upstream, so anyone who can reach the port can spend the inference budget.
`GET /metric` exposes request volume, error rates, and candidate match quality —
competitive and operational signal that does not belong on an open port.

What we would build:

- **A bearer token on every route.** `PROXY_API_KEYS`, a comma-separated set of
  accepted keys, compared with `MessageDigest.isEqual` to avoid a timing side
  channel, enforced by an `ApiKeyAuthFilter` ordered ahead of any real work. A
  missing or unknown key returns `401` with no detail about why.
- **A per-key token bucket** via Bucket4j in a `RateLimitFilter`,
  `RATE_LIMIT_RPS` default `50` with a burst of `RATE_LIMIT_BURST` default
  `100`, returning `429` with `Retry-After`. It protects both our budget and the
  upstream quota from a single misbehaving client. Because the bucket is keyed by
  API key, it is meaningless without keys and therefore parks together with
  authentication rather than shipping alone.
- `/actuator/health` would stay unauthenticated so orchestrator probes keep
  working, exposing no detail beyond status.

The Objective's endpoint paths and payloads are unaffected either way: this was
always an additional requirement layered on top of them, never a change to them.

Two nearby mechanisms are **not** parked and should not be confused with this
one. `MAX_CONCURRENT_REQUESTS` and its `503` are an inbound memory bound rather
than an access control, and the handling of `429` responses *from DigitalOcean*
is about respecting an upstream quota, not about limiting our own callers.

## Reliability

### Bounded inbound concurrency

A semaphore filter caps concurrent in-flight requests at
`MAX_CONCURRENT_REQUESTS`, default `128`. Beyond that the service returns `503`
with `Retry-After` immediately rather than accumulating request state. This
closes the inbound half of the memory story:

```
peak heap ~= (MAX_CONCURRENT_REQUESTS x request bytes)          <- inbound, new
           + ((SHADOW_QUEUE_SIZE + SHADOW_WORKERS) x job bytes) <- shadow
```

Both terms are now products of configured constants. Shedding fast under
overload is the correct behaviour for a proxy; queueing indefinitely is how you
turn a slow upstream into an outage.

### Separate connection pools

Two `HttpClient` instances with independent pools:

- **Primary pool** — max connections sized to `MAX_CONCURRENT_REQUESTS`, with a
  connect timeout of `2s` distinct from the `PRIMARY_TIMEOUT` read timeout.
- **Candidate pool** — max connections sized to `SHADOW_WORKERS`, so the shadow
  path physically cannot consume more sockets than it has workers.

Shadow traffic cannot exhaust, block on, or evict connections belonging to the
customer path.

### Retries, and where they are forbidden

Exactly one retry on the primary path, and only when the connection failed before
any request bytes reached the server — a dial or TLS handshake failure, after a
short jittered backoff. **Never retry once the body has been sent.** Inference
calls are billed and non-idempotent, so a speculative retry on an ambiguous
timeout risks double-charging the customer and double-counting tokens, which is
worse than returning a `502`.

The shadow path never retries at all. Retrying a best-effort sample under
rate-limit pressure makes the pressure worse; the correct response is to shed.

On upstream `429`, follow DigitalOcean's documented guidance rather than a
generic backoff: read the soonest non-zero `x-ratelimit-reset-*` header to find
the binding limit, prefer `Retry-After` when present, wait at least one second
with 250–500 ms of jitter, and treat a `reset: 0` after rejection as ambiguous
rather than as permission to retry immediately.

### Circuit breaker on the candidate

Resilience4j breaker wrapping candidate calls only: opens at a 50% failure rate
over a rolling window of 20 calls, stays open for 60s, then half-opens with 3
trial calls. While open, jobs are drained and counted as `shadow_circuit_open`
without a network call.

When a candidate model is broken, continuing to call it for every request burns
money and upstream quota to learn something already known. Failing fast is both
cheaper and kinder to the provider's rate limits.

There is deliberately **no breaker on the primary path**. A breaker there would
mask upstream failures behind a synthetic error, and a transparent proxy should
relay what the upstream actually said.

### Probes that cannot cause an outage

- **Liveness** — process responsiveness only. It must never consider upstream
  health. A liveness probe that fails when DigitalOcean is degraded turns a
  partial upstream problem into a restart loop across every replica.
- **Readiness** — configuration validated, HTTP server accepting, not shutting
  down. Also independent of upstream health, because pulling every pod out of
  rotation during an upstream blip removes the ability to return the upstream's
  own error to the caller.

### Shutdown

On `SIGTERM`: stop accepting new connections, let in-flight primary requests
finish within `SHUTDOWN_GRACE`, then **discard the shadow queue** rather than
draining it. Shadow work is best-effort by definition, and holding a pod open to
finish background evaluations delays deploys for no user benefit. Discarded jobs
are counted as `shadow_dropped_shutdown` so the number is visible rather than
silently missing.

## Observability

`GET /metric` satisfies the Objective. Production needs more than a counter
snapshot, so Micrometer is added alongside it, exposed at
`/actuator/prometheus` on a **separate management port**
(`MANAGEMENT_SERVER_PORT`, default `9090`) that is not published externally.

Beyond the counters already specified:

- **Latency histograms** for primary and candidate calls, so p50/p95/p99 are
  real percentiles rather than averages. The central claim of this design — that
  shadowing does not affect primary latency — is unfalsifiable without them.
- **Token and cost accounting** from the upstream `usage` field:
  `primary_tokens_total` and `candidate_tokens_total`, split by prompt and
  completion. Shadowing doubles inference spend, so this is a first-class
  operational number, not a nice-to-have.
- **Correlation IDs.** Accept `X-Request-Id` or generate one, put it in the
  logging context, attach it to the `ShadowJob`, and return it on the response so
  a customer report can be traced to both model calls.

Logs are JSON via SLF4J, one line per request at `INFO` with request id, model,
upstream status, duration, and token counts. Shadow outcomes log at `DEBUG`. A
shed or sampled-out job is never logged as an error.

### SLOs and alerts

- Availability of `POST /v1/chat` (non-5xx originating from the proxy itself):
  **99.9%** over 30 days.
- Latency overhead added by the proxy over the upstream call: **p99 under 15ms**.
- Shadow coverage at normal load: **at least 90%** of requests evaluated.

Alert when the proxy's own 5xx rate exceeds 1% over 5 minutes, when
`shadow_shed / total_requests` exceeds 25% for 15 minutes at normal load, when
the candidate breaker is open for more than 10 minutes, and when
`exact_match_rate` drops more than 10 points week over week.

## Proving the isolation claim

Unit tests show the code is wired correctly. They do not prove the latency
guarantee, which is the entire premise of the design and therefore the one thing
worth measuring rather than asserting.

A k6 scenario in `perf/` runs a fixed load against the service twice: once with a
healthy candidate, once with the candidate stub injected at 30s latency and a
100% error rate. Three gates, all of which must hold:

1. **Distribution, not just a percentile.** A two-sample Kolmogorov–Smirnov test
   over the primary latency samples from both runs, failing if the distributions
   differ significantly *and* p99 moves by more than 5%. Shadow interference
   would most plausibly appear as a fattened tail, which comparing means or even
   a single percentile can miss entirely.
2. **Peak RSS in the degraded run** stays under the container limit.
3. **`shadow_shed_total > 0`.** This gate is not optional. Without it the test
   passes vacuously whenever the load generator is too weak to cause shedding,
   and you would be certifying a code path that never executed. Every load test
   needs an assertion that the thing under stress was actually stressed.

An honest limitation: this bounds the effect size, it does not prove zero. That
is why the structural guarantees come first — `ShadowJob` holding no reference to
the request, and the ArchUnit rules enforcing it, are proofs; this is
corroboration under realistic conditions.

Chaos cases covered in integration tests: candidate returns 500, candidate hangs
past timeout, candidate returns non-JSON, candidate returns `429`, and the
primary fails while the candidate would have succeeded.

## Supply chain and CI hardening

Beyond the format/test/build pipeline in [04-operations.md](04-operations.md):

- Base images pinned by **digest**, not tag.
- **Trivy** scan of the built image, failing the build on HIGH or CRITICAL
  vulnerabilities with a fix available.
- **CycloneDX SBOM** generated and attached to each release.
- **Dependabot** for Gradle and GitHub Actions, weekly.
- **Gradle dependency verification** via `gradle/verification-metadata.xml`, so a
  compromised or substituted artifact fails the build rather than shipping.
- Actions pinned to commit SHAs, with a read-only default `GITHUB_TOKEN`.
- Releases tagged semantically; images tagged with both the version and the git
  SHA so a running container is always traceable to a commit.

## Capacity and deployment

Sizing follows from the bounded terms above. At the defaults —
`MAX_CONCURRENT_REQUESTS=128`, `MAX_REQUEST_BYTES=128 KiB`,
`MAX_RESPONSE_BYTES=256 KiB`, `SHADOW_BUDGET_BYTES=8 MiB`, `SHADOW_WORKERS=32` —
application-controlled state peaks around 65 MB.

Be honest about what that does and does not tell you: on the JVM, baseline
footprint (heap reserve, metaspace, code cache, thread stacks, GC structures) is
typically 150–250 MB and therefore **dominates** the total. The formula bounds the
part of memory this design controls, which is the part that could otherwise grow
without limit; it does not make the container small. Set the limit to **768 MB**
with `-XX:MaxRAMPercentage=70`, and treat the bounded terms as the guarantee that
load cannot push you past it rather than as the sizing input.

Also validate at startup that `PRIMARY_MODEL != CANDIDATE_MODEL` unless an
explicit `ALLOW_AA_MODE` flag is set. Comparing a model against itself by
accident produces a beautiful, meaningless match rate near 100%, and nothing else
in the system would flag it.

Run **one process per container** and scale horizontally. Metrics are per
instance, which is consistent with the non-goal already recorded in
[README.md](README.md): scrape each replica and aggregate in Prometheus rather
than building cross-instance aggregation into the service.

Rollouts are rolling with readiness gating and an automatic rollback on error
budget burn. Because the service is stateless and holds only best-effort
in-flight work, a rollback costs nothing beyond a few dropped evaluations.

## Go-live checklist

- Network path confirmed: the listener is reachable only from trusted callers,
  because inbound authentication and rate limiting are
  [deferred](#deferred-inbound-authentication-and-rate-limiting).
- `DO_INFERENCE_API_KEY` rotated from any value used during development.
- `MAX_CONCURRENT_REQUESTS` tuned against measured traffic.
- Container memory limit set above the computed bounded-state ceiling.
- Prometheus scraping the management port; dashboards for latency, match rate,
  shed rate, and token spend.
- Alerts wired to on-call with the runbook in
  [04-operations.md](04-operations.md) linked from each alert.
- Load test passing its p99 gate with the candidate degraded.
- Trivy clean and SBOM published for the release tag.
- Runbook rehearsed for the two most likely pages: candidate degraded, and
  primary upstream degraded.

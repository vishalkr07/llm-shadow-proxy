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

### Inbound authentication

`POST /v1/chat` proxies to a metered, paid upstream. Left open, anyone who can
reach the port can spend your inference budget. The service requires a bearer
token on every route:

- `PROXY_API_KEYS` — comma-separated set of accepted keys, compared with
  `MessageDigest.isEqual` to avoid a timing side channel.
- Missing or unknown key returns `401` with no detail about why.
- `GET /metric` requires the same key. It exposes request volume, error rates,
  and candidate match quality — competitive and operational signal that does not
  belong on an open port. The Objective's endpoint and payload are unchanged;
  only the auth requirement is added.
- `/actuator/health` stays unauthenticated so orchestrator probes work, and
  exposes no detail beyond status.

### Credential handling

The client's `Authorization` header is consumed by us and **never forwarded
upstream**. The upstream call carries our `DO_INFERENCE_API_KEY` and nothing
else. Neither key is ever logged: a logging filter redacts `Authorization` and
`Proxy-Authorization` unconditionally, and the startup configuration dump prints
the key as `****`.

Keys come from the orchestrator's secret store as environment variables. They are
never baked into the image, never written to disk, and never appear in
`docker-compose.yaml` outside the local mock profile.

### Prompt data

Request bodies contain customer prompts and are likely to contain personal data.
Bodies are never logged at `INFO` or below. Body logging exists only at `TRACE`
and is disabled in production by configuration, not by convention. Bodies live in
memory only for the lifetime of a request plus its shadow job, and are never
persisted anywhere.

Candidate calls carry `X-Shadow-Request: true` so mirrored traffic is
distinguishable in any upstream logging or billing breakdown.

### Rate limiting

Per-key token bucket via Bucket4j, `RATE_LIMIT_RPS` default `50` with a burst of
`RATE_LIMIT_BURST` default `100`. Exceeding it returns `429` with `Retry-After`.
This protects both our budget and the upstream quota from a single misbehaving
client.

## Reliability

### Bounded inbound concurrency

A semaphore filter caps concurrent in-flight requests at
`MAX_CONCURRENT_REQUESTS`, default `200`. Beyond that the service returns `503`
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
100% error rate. The gate is that **primary p99 differs by less than 5% between
the two runs**, with `shadow_shed` demonstrating that shedding engaged. This runs
nightly in CI, not on every pull request.

Chaos cases covered in integration tests: candidate returns 500, candidate hangs
past timeout, candidate returns non-JSON, candidate returns `429`, and the
primary fails while the candidate would have succeeded.

## Supply chain and CI hardening

Beyond the format/test/build pipeline in [04-operations.md](04-operations.md):

- Base images pinned by **digest**, not tag.
- **Trivy** scan of the built image, failing the build on HIGH or CRITICAL
  vulnerabilities with a fix available.
- **CycloneDX SBOM** generated and attached to each release.
- **Dependabot** for Maven and GitHub Actions, weekly.
- Actions pinned to commit SHAs, with a read-only default `GITHUB_TOKEN`.
- Releases tagged semantically; images tagged with both the version and the git
  SHA so a running container is always traceable to a commit.

## Capacity and deployment

Sizing follows directly from the two bounded terms above. At the defaults —
`MAX_CONCURRENT_REQUESTS=200`, `SHADOW_QUEUE_SIZE=256`, `SHADOW_WORKERS=8`, 32 KB
per payload — bounded state is roughly 15 MB. A 512 MB container limit with
`-XX:MaxRAMPercentage=75` leaves generous room for JVM overhead and garbage
collection headroom.

Run **one process per container** and scale horizontally. Metrics are per
instance, which is consistent with the non-goal already recorded in
[README.md](README.md): scrape each replica and aggregate in Prometheus rather
than building cross-instance aggregation into the service.

Rollouts are rolling with readiness gating and an automatic rollback on error
budget burn. Because the service is stateless and holds only best-effort
in-flight work, a rollback costs nothing beyond a few dropped evaluations.

## Go-live checklist

- Inbound auth enabled with keys issued per client, and `PROXY_API_KEYS` sourced
  from the secret store.
- `DO_INFERENCE_API_KEY` rotated from any value used during development.
- Rate limits and `MAX_CONCURRENT_REQUESTS` tuned against measured traffic.
- Container memory limit set above the computed bounded-state ceiling.
- Prometheus scraping the management port; dashboards for latency, match rate,
  shed rate, and token spend.
- Alerts wired to on-call with the runbook in
  [04-operations.md](04-operations.md) linked from each alert.
- Load test passing its p99 gate with the candidate degraded.
- Trivy clean and SBOM published for the release tag.
- Runbook rehearsed for the two most likely pages: candidate degraded, and
  primary upstream degraded.

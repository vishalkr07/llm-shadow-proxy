# Build Plan: Shadow-Traffic LLM Proxy

This folder contains the architecture and implementation plan derived from
[`docs/Objective.md`](../Objective.md). The Objective is the frozen source of
truth and must not be edited; everything here is the design that satisfies it.

## Documents

- [01-architecture.md](01-architecture.md) — system architecture, request flow, concurrency and memory model
- [02-api-contract.md](02-api-contract.md) — endpoint contracts, payload shapes, evaluation rules
- [03-implementation-plan.md](03-implementation-plan.md) — phased build plan, package layout, milestones
- [04-operations.md](04-operations.md) — configuration, CI/CD, deployment, observability, runbook
- [05-service-topology.md](05-service-topology.md) — one service vs three, the three frozen components, Docker topology
- [06-production-readiness.md](06-production-readiness.md) — security, resilience, SLOs, supply chain, go-live checklist

## What we are building, in one paragraph

An HTTP proxy that accepts chat-completion traffic on `POST /v1/chat`, forwards
it synchronously to a **primary** model on DigitalOcean Serverless Inference and
returns that response to the caller unchanged. The same payload is handed to a
**bounded background pipeline** that replays it against a **candidate** model,
compares the two outputs with a deterministic heuristic (both parse as JSON, and
their `action` fields match exactly), and folds the result into counters served
by `GET /metric`. The background path is strictly best-effort: it is isolated
behind a fixed-size queue and a fixed worker pool, so candidate latency, errors,
or traffic bursts can never delay the primary response or grow the heap without
bound.

## Locked-in decisions

These are settled so implementation can start without further questions.

- **Topology: one service, one image, three frozen components** — API (proxy),
  EVAL (shadow), METRICS. The reasoning, the interfaces, and the conditions under
  which we would revisit are recorded in
  [05-service-topology.md](05-service-topology.md). The component boundaries and
  their contracts do not change during implementation.
- **Language / runtime: Java 21 (LTS).** Java 21.0.12 is already installed in the
  dev container. The whole shadow design maps directly onto
  `java.util.concurrent`: a `ThreadPoolExecutor` backed by a bounded
  `ArrayBlockingQueue` with a custom `RejectedExecutionHandler` *is* the
  load-shed mechanism the Objective asks for, with no extra machinery.
- **Framework: Spring Boot 4.1** (current stable; requires Java 17+). Gives us
  the HTTP layer, configuration binding with validation, graceful shutdown, and
  Actuator health checks out of the box.
- **HTTP client: Spring `RestClient`**, one bean per upstream so the primary and
  candidate can carry different timeouts.
- **Build: Maven via the Maven Wrapper (`./mvnw`).** No Maven or Gradle is
  installed in the container, and the wrapper bootstraps itself, so a clone can
  build with nothing but a JDK.
- **Concurrency model: virtual threads for the request path, a fixed platform
  thread pool for the shadow path.** `spring.threads.virtual.enabled=true` keeps
  the synchronous primary call from pinning platform threads, while the shadow
  executor stays a fixed pool on purpose — unbounded virtual threads would
  destroy the bounded-concurrency and memory guarantees.
- **Upstream: DigitalOcean Serverless Inference**, base URL
  `https://inference.do-ai.run`, endpoint `POST /v1/chat/completions`, Bearer
  token auth. The API is OpenAI-compatible, so primary and candidate differ only
  by the `model` field.
- **Metrics storage: in-process `LongAdder` counters.** The Objective asks for a
  real-time summary from `GET /metric`, not durable history. Counters reset on
  restart, and this is documented as intentional. `/metric` is hand-written
  rather than delegated to Actuator, because the Objective specifies that exact
  path and payload.
- **Load-shed policy: drop-newest.** When the shadow queue is full, the incoming
  job is discarded and a `shadow_shed` counter is incremented. Shedding is a
  normal, observable outcome, not an error.
- **The service is treated as production infrastructure, not a demo.** Inbound
  requests are authenticated and rate limited, inbound concurrency is bounded,
  each upstream gets its own connection pool, the candidate sits behind a circuit
  breaker, and probes never depend on upstream health. All of it is specified in
  [06-production-readiness.md](06-production-readiness.md).
- **Streaming (`"stream": true`): rejected in v1** with `400`. Shadow comparison
  requires a complete JSON body from both models; supporting streaming would mean
  buffering full responses and re-emitting them, which is out of scope.

## Non-goals

- Editing [`docs/Objective.md`](../Objective.md).
- Persisting evaluations to a database or aggregating metrics across replicas.
- Semantic or LLM-as-judge scoring. The Objective specifies a deterministic
  heuristic, and determinism is the point.
- Automatic traffic promotion or rollout of the candidate model.

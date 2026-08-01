# Design Review

This document records an independent review of the architecture and what came
out of it. It exists so that later readers can see which decisions were
contested, what the alternative was, and why the current design won — rather than
finding a set of choices with no visible reasoning behind them.

## How the review was run

A second architect was given **only** [`docs/Objective.md`](../Objective.md) and
the environment facts, and was explicitly prevented from reading this `plan/`
directory. The point was to get a genuinely unanchored design rather than a
review that agrees with the incumbent because it started from it. That architect
produced a complete end-to-end proposal in Go. The two designs were then compared
head to head.

The value of the exercise turned out to be less in the language question than in
the places where an independent designer, reasoning from the same specification,
reached a *different and better* answer on a specific mechanism.

## Independent convergence

These were reached separately by both designs, with different arguments. Where
two independent lines of reasoning arrive at the same place, that is the closest
thing to evidence a design review produces, and these should be treated as
settled:

- **One service with an in-process queue, not a broker.** The incumbent argued
  from precedent (Envoy and Istio implement mirroring in-process, and our shadow
  *target* is already an external service). The reviewer argued from the
  specification (it explicitly demands lossiness, which is the one property a
  broker exists to prevent) and from throughput (one replica saturates the whole
  account quota, so splitting buys nothing).
- **Separate connection pools per upstream.** Both flagged it, and the reviewer
  supplied the sharper reason: primary and candidate are the *same hostname*, so
  a shared pool lets slow candidate calls occupy every per-host connection and
  make a primary request block while acquiring one. It is a violation of the core
  guarantee produced by a line of code that looks entirely innocent.
- **A fixed worker pool rather than a task-per-request executor**, and **no
  retries on the shadow path**.
- **Skip the shadow entirely when the primary call failed** — there is nothing to
  compare against, and upstream quota is scarce.
- **Bounded inbound concurrency with immediate rejection** rather than queueing.
- **Reject `stream: true` in v1**, with a streaming tee deferred rather than
  half-implemented.
- **Circuit breaker on the candidate only, never the primary**, because a
  transparent proxy must relay what the upstream actually said.
- **Liveness and readiness independent of upstream health**, and token and cost
  accounting as first-class metrics.
- **Authentication on a proxy that fronts a billed endpoint.** Both designs
  called for it, and the argument still stands, but it has since been deferred
  out of v1 as a scope decision rather than a technical one. See
  [06-production-readiness.md](06-production-readiness.md#deferred-inbound-authentication-and-rate-limiting).

## Adopted from the review

### Uniform random admission instead of drop-when-full

The most valuable finding, and the one that changed the design rather than
adding to it.

A queue that drops on overflow admits jobs from the *start* of each burst and
rejects the *tail*. Bursts correlate with peak hours, retry storms, and
individual heavy customers, so the surviving sample is systematically skewed and
`exact_match_rate` becomes a biased estimator of the true rate — while still
looking perfectly precise. The service exists to produce that number, so
corrupting it silently is close to the worst available failure.

Admission is now uniform-random against an adaptive probability, with the
count gate, byte gate, and TTL behind it as hard bounds. Details in
[01-architecture.md](01-architecture.md#load-shedding-and-why-it-is-not-just-drop-when-full).

### Constants derived from the upstream rate limit

The incumbent had round numbers — 200 concurrent requests, 8 workers, a 256-deep
queue. The reviewer derived them from the documented DigitalOcean limit via
Little's law instead.

We verified the claim: **250 requests/minute and 5,000/hour** is DigitalOcean's
documented default. One correction, though — serverless inference also carries
*tier-dependent* limits (Tier 1 is 120 RPM, Tier 5 is 4,500) plus separate
tokens-per-minute quotas, so the number is right but not universal. It is now
`UPSTREAM_RPM_LIMIT` configuration, with the pool sizes derived from it in
[04-operations.md](04-operations.md#deriving-the-concurrency-constants).

The queue also got much shallower, 256 down to 64. At the real drain rate a
256-deep queue holds jobs for minutes — far past their TTL — so it was never
burst absorption, only a reservoir of samples that would be thrown away later.

### Byte budget and task TTL

A count-bounded queue permits a large swing in footprint depending on payload
size, and a bounded queue with *unbounded item lifetime* still leaks because slow
tasks pin their reservations indefinitely. Both gaps are now closed explicitly: a
compare-and-set byte reservation held from admission to completion, and a
staleness check at dequeue.

### Evaluation refinements

- **Truncation is checked before parsing.** A response cut off by `max_tokens`
  will not parse, and reporting it as malformed JSON blames the model for a
  configuration mistake. This was simply a bug in the incumbent rules.
- **Per-side failure reasons** are now distinct: `TRAILING_GARBAGE`,
  `NOT_OBJECT`, `ACTION_NOT_STRING`, and so on. Collapsing "emitted prose" and
  "chose a different action" into one bucket destroys the signal, because the
  first is a formatting failure and the second a semantic one.
- **Prose wrapped around valid JSON is rejected rather than salvaged**, because
  scanning for the first `{` is non-deterministic on adversarial output.
- **`near_match_total`** makes the cost of case-sensitivity visible instead of
  hidden, and **`eval_version`** stops a change in this logic from being
  misread as a model regression.

### Statistical honesty in `/metric`

Four additions, all cheap, all preventing a specific way the headline number
misleads: `coverage_pct` (a match rate over 3% of traffic and one over 100% look
identical without it), a **Wilson confidence interval** (89% from 50 samples is
noise, 89% from 50,000 is a fact), an explicit `exact_match_rate_basis` naming
the denominator, and a **1% A/A control arm** routing a slice of shadow traffic
to the primary model. That last one matters more than it looks: with
`temperature > 0` some disagreement is sampling noise, and without a noise floor
the A/B number cannot be interpreted at all.

### Structural isolation, enforced by tooling

The reviewer's strongest methodological point: the best test is one you do not
need. Rather than testing that background work never touches request state, make
it unrepresentable. In Go that was a type with no context field plus a lint rule;
in Java it is `ShadowJob` holding no servlet reference plus an **ArchUnit** test
failing the build if `shadow` or `eval` imports `jakarta.servlet` or
`org.springframework.web`. A reviewer can now verify the guarantee by reading a
record definition.

### Testing gates

Two upgrades to the load test. Comparing whole latency **distributions** with a
Kolmogorov–Smirnov test rather than a single percentile, because shadow
interference would most plausibly show up as a fattened tail. And a
**non-vacuity gate** asserting `shadow_shed_total > 0`, without which the test
passes trivially whenever the load generator is too weak — certifying a code path
that never ran.

### Smaller items

Submission ordering (hand off once the primary response is in hand but before the
socket write, so a slow client cannot delay enqueue past the TTL); one retry only
on pre-body connection failure, never after the body is sent, because inference
calls are billed and non-idempotent; separate model access keys per model so
quota isolation is enforced upstream rather than only by our own throttle;
refusing to boot when `PRIMARY_MODEL == CANDIDATE_MODEL`; tighter payload caps
(1 MB down to 128 KiB); and DigitalOcean's specific `429` guidance using the
`x-ratelimit-reset-*` headers with jitter.

## Rejected, and why

**Go as the runtime.** A product decision by the team, who will own this code.
Worth recording that the reviewer's technical case against Java was also its
weakest argument: it rejected Java largely because no Maven or Gradle is
installed, dismissing build wrappers as degrading reproducibility. That is
backwards. The Gradle Wrapper pins an exact distribution and verifies its
checksum, which is *more* reproducible than depending on whatever version happens
to be installed on the machine. The second objection — that JVM baseline
footprint dominates the memory formula — is entirely fair, and rather than argue
with it we now state it plainly in
[01-architecture.md](01-architecture.md#how-this-bounds-the-memory-footprint) and
size the container accordingly.

**Moving `/metric` to an internal-only admin port.** The reviewer wanted it off
the public listener because it leaks model names, cost data, and quality signal.
The concern is right, but the Objective names the endpoint and we are not going
to relocate a specified interface. Prometheus and Actuator go on the separate
management port instead. The answer to the leak itself was bearer authentication
on `/metric`, which is now
[deferred](06-production-readiness.md#deferred-inbound-authentication-and-rate-limiting) —
so until it ships the reviewer's concern is unmitigated, and the service has to
be kept off untrusted networks.

**A defect in the reviewer's own dispatch code.** Its `Drain()` sets a closed
flag and then closes the job channel, while `Offer()` checks that flag and then
sends. The check and the send are not atomic, so an `Offer` that passes the check
just before `Drain` closes the channel will send on a closed channel and panic —
crashing the process during shutdown, under load, which is exactly when it is
hardest to diagnose. It does not affect us (`ArrayBlockingQueue` has no close
operation and our shutdown discards the queue instead), but it is worth recording
that the independent design was not uniformly stronger.

## Accepted limitation

Go's `testing/synctest` gives a fake clock under which "the primary took exactly
300 ms while the candidate took ten simulated minutes" is a deterministic
assertion rather than a timing-sensitive one. **Java has no equivalent**, and
pretending otherwise would be dishonest. We compensate with an injected `Clock`,
Awaitility for asynchronous assertions, and the structural ArchUnit guarantees —
and we lean harder on the k6 and Kolmogorov–Smirnov gate to bound the effect size
under realistic load. This is a genuine capability we are giving up, and it is
the one place where the rejected proposal was better equipped than the design we
are building.

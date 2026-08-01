# API Contract

## `POST /v1/chat`

The customer-facing proxy. Accepts an OpenAI-compatible chat completion request
and returns the primary model's response unchanged.

### Request

Body is the standard chat-completions payload. The `model` field is optional and
ignored — routing is decided by server configuration, not by the caller, so that
primary and candidate are always compared on identical input.

```json
{
  "messages": [
    { "role": "system", "content": "Reply with JSON: {\"action\": \"...\"}" },
    { "role": "user", "content": "The user wants to cancel their order." }
  ],
  "temperature": 0,
  "max_completion_tokens": 256
}
```

Constraints:

- `Content-Type: application/json` required.
- Any `Authorization` header the caller sends is consumed by the proxy and never
  forwarded upstream; the upstream call carries our own credential instead.
- Body size capped at `MAX_REQUEST_BYTES` (default 128 KiB), enforced by a
  servlet filter. Exceeding it returns `413`.
- `"stream": true` returns `400`; see the streaming decision in
  [README.md](README.md).

### Response

The upstream response is relayed verbatim: same status code, same JSON body.
A successful call looks like a normal chat completion.

```json
{
  "id": "chatcmpl-...",
  "model": "llama3.3-70b-instruct",
  "choices": [
    {
      "index": 0,
      "message": { "role": "assistant", "content": "{\"action\": \"refund\"}" },
      "finish_reason": "stop"
    }
  ],
  "usage": { "prompt_tokens": 42, "completion_tokens": 9, "total_tokens": 51 }
}
```

Nothing about the shadow evaluation appears in the response — no extra fields, no
extra headers, no added latency.

### Status codes

- `200` and any other upstream status — relayed from the primary model as-is.
- `400` — malformed JSON body, or `stream: true`.
- `405` — method other than `POST`.
- `413` — body exceeds `MAX_REQUEST_BYTES`.
- `502` — primary model unreachable or returned an unreadable response.
- `503` — `MAX_CONCURRENT_REQUESTS` in flight; includes `Retry-After`.
- `504` — primary model exceeded the primary read timeout.

Error responses are produced by a `@RestControllerAdvice` so the proxy's own
failures are shaped consistently and are distinguishable from relayed upstream
error bodies.

## `GET /metric`

Real-time counters. Cheap, allocation-light, safe to scrape frequently. Served by
a hand-written controller rather than Actuator, because the Objective specifies
this exact path and summary. Volume, error rates, and match quality are
operational and competitive signal, which is the main reason authenticating this
endpoint is on the
[deferred list](06-production-readiness.md#deferred-inbound-authentication-and-rate-limiting)
rather than off it.

```json
{
  "schema_version": "1",
  "eval_version": "1",
  "scope": "replica_local",
  "replica_id": "shadow-proxy-7d4f9b6c8-x2mlq",
  "uptime_seconds": 3600,

  "total_requests": 1482,

  "shadow": {
    "eligible": 1470,
    "enqueued": 1301,
    "coverage_pct": 88.50,
    "current_admit_probability": 1.0,
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
    "in_flight": 7,
    "workers": 32,
    "breaker_state": "closed"
  },

  "evaluation": {
    "evaluated": 1288,
    "exact_matches": 1174,
    "mismatches": 90,
    "near_matches": 6,
    "primary_unusable": 3,
    "candidate_unusable": 21,
    "both_unusable": 0,
    "exact_match_rate": 92.88,
    "exact_match_rate_basis": "exact_matches / (exact_matches + mismatches)",
    "exact_match_rate_ci95": { "low": 91.30, "high": 94.19, "method": "wilson" },
    "unusable_by_reason": {
      "primary":   { "empty_content": 0, "content_truncated": 1, "not_json": 1,
                     "trailing_garbage": 1, "not_object": 0,
                     "action_absent": 0, "action_not_string": 0 },
      "candidate": { "empty_content": 0, "content_truncated": 6, "not_json": 9,
                     "trailing_garbage": 4, "not_object": 1,
                     "action_absent": 1, "action_not_string": 0 }
    },
    "control_arm_aa": {
      "evaluated": 12,
      "exact_match_rate": 100.0,
      "note": "primary-vs-primary noise floor; the A/B rate is only meaningful relative to this"
    }
  },

  "cost": {
    "primary_tokens": { "prompt": 412039, "completion": 82104 },
    "candidate_tokens": { "prompt": 391182, "completion": 69041 }
  },

  "window_60s": {
    "total_requests": 214,
    "evaluated": 198,
    "exact_match_rate": 91.12,
    "exact_match_rate_ci95": { "low": 86.31, "high": 94.36, "method": "wilson" }
  }
}
```

Field notes, covering only what is not self-evident:

- `exact_match_rate_basis` is stated in the payload because the single most
  common way this metric misleads is ambiguity about the denominator.
  Unparseable responses are **excluded** from it, not counted as mismatches: a
  candidate that emits prose has a *formatting* problem, and a candidate that
  emits a different action has a *semantic* one. Averaging them together
  produces a number that cannot drive either fix. Both populations are fully
  reported alongside, so the stricter reading — treating unusable output as
  non-matching — can be computed by the reader from the same payload.
- `coverage_pct` is `enqueued / eligible`. A match rate over 3% of traffic and
  one over 100% look identical without it, and only one of them supports a
  decision.
- `exact_match_rate_ci95` is a Wilson score interval. 89% from 50 samples is
  noise; 89% from 50,000 is a fact, and the bare percentage cannot tell them
  apart. Wilson is used rather than the normal approximation because it stays
  well-behaved at small `n` and extreme `p`, and it is a cheap closed form.
- `control_arm_aa` reports a small slice of shadow traffic sent to the **primary**
  model instead of the candidate. Customer requests may use `temperature > 0`, so
  some disagreement is sampling noise rather than model difference. Without this
  noise floor an A/B rate is uninterpretable: if A/A is 96% and A/B is 93%, the
  models are far closer than the raw gap suggests.
- `window_60s` exists because lifetime counters are useless for a "real-time"
  summary — a 30-point quality drop is invisible against a denominator of
  millions. It is a ring of one-second buckets, fixed size, advanced lazily.
- `shed_by_reason` separates deliberate sampling from overload from staleness,
  because each demands a different response from an operator.
- `scope` and `replica_id` are present so a reader can never mistake one
  replica's view for the fleet's. Cross-replica aggregation is Prometheus's job.
- `exact_match_rate` is exactly `0` when the denominator is zero, never `NaN`.

The response record is serialised with explicit `@JsonProperty` snake_case names
so the wire contract does not drift with Java field renames. Counters are
process-local and reset on restart, per the decision in [README.md](README.md).

## Deterministic evaluation rules

Implemented as pure methods on a stateless evaluator so they are exhaustively
unit-testable without Spring context. Each side is parsed independently into a
`SideResult` carrying either an action string or a specific failure reason, and
the two are then compared.

Per side, in this order:

1. **Truncation first.** If `finish_reason` is `length`, or the content hit
   `MAX_EVAL_CONTENT_BYTES`, the side is `CONTENT_TRUNCATED`. This is checked
   *before* parsing, because a cut-off payload will not parse and reporting it as
   malformed JSON would blame the model for what is actually a `max_tokens`
   misconfiguration.
2. **Extract content.** `choices[0].message.content`. Missing, empty, or
   whitespace-only is `EMPTY_CONTENT`.
3. **Unwrap fences.** A single surrounding Markdown code fence
   (```` ```json ... ``` ````) is stripped, then whitespace trimmed. Models emit
   these constantly; rejecting them would make the metric measure prompt
   formatting rather than model behaviour. Nothing else is normalised.
4. **Parse JSON**, and require the payload to be exactly one JSON value. Prose
   around the JSON (`Sure! Here you go: {"action":"refund"}`) is
   `TRAILING_GARBAGE`, not a match. We deliberately do not scan for the first
   `{`: that heuristic is non-deterministic on adversarial output and can lift
   JSON out of an example embedded in prose, and determinism is the entire point.
5. **Require an object.** A top-level array or scalar is `NOT_OBJECT`.
6. **Extract `action`.** Absent is `ACTION_ABSENT`; present but not a string is
   `ACTION_NOT_STRING`. No coercion — `1` and `"1"` are different values.

Then compare: `String.equals` on the two action values. No trimming, no case
folding, no whitespace collapsing, no unicode normalisation.

The outcome is exactly one value of an `EvalResult` enum: `MATCH`, `MISMATCH`,
`PRIMARY_UNUSABLE`, `CANDIDATE_UNUSABLE`, `BOTH_UNUSABLE`. Each maps to a counter
update in a single `switch`, and the per-side reason is recorded separately so
the numbers on `/metric` always reconcile.

Two deliberate refinements:

- **Near-match is reported, not accepted.** `"Refund"` versus `"refund"` is a
  mismatch, because a downstream consumer doing `switch (action)` would break on
  it. But strictness has a cost, so `near_match_total` counts pairs equal after
  case folding and trimming, making that cost visible instead of hidden.
- **`eval_version` is stamped into `/metric`.** When this logic changes the match
  rate moves for reasons unrelated to the models. Without a version stamp,
  somebody spends a week debugging a model regression that was a fence-stripping
  bug fix.

### Worked examples

- Primary `{"action":"refund"}` vs candidate `{"action":"refund"}` — match.
- Primary `{"action":"refund"}` vs candidate `{"action":"Refund"}` — mismatch,
  deliberately, because comparison is case-sensitive. Also counted in
  `near_match_total`.
- Primary `{"action":"refund"}` vs candidate ```` ```json\n{"action":"refund"}\n``` ````
  — match, after fence stripping.
- Primary `{"action":"refund","confidence":0.9}` vs candidate
  `{"action":"refund"}` — match; only `action` is compared.
- Primary `{"action":"refund"}` vs candidate `Sure! I'd refund that.` — candidate
  `NOT_JSON`, so `CANDIDATE_UNUSABLE`.
- Primary `{"action":"refund"}` vs candidate `Here you go: {"action":"refund"}` —
  candidate `TRAILING_GARBAGE`, not a match, by design.
- Candidate cut off mid-object with `finish_reason: "length"` —
  `CONTENT_TRUNCATED`, never reported as malformed JSON.
- Primary `{"action":1}` — `ACTION_NOT_STRING`; no coercion to `"1"`.

## Upstream contract (DigitalOcean Serverless Inference)

- Base URL: `https://inference.do-ai.run` (configurable via
  `DO_INFERENCE_BASE_URL`).
- Endpoint: `POST /v1/chat/completions`, OpenAI-compatible.
- Auth: `Authorization: Bearer $DO_INFERENCE_API_KEY`.
- Primary and candidate share the same client code and differ only in the `model`
  field, which we override with `PRIMARY_MODEL` / `CANDIDATE_MODEL`, and in their
  configured read timeouts.
- `429` responses from the candidate are counted as `shadow_errors`. We do not
  retry on the shadow path: retrying a best-effort sample under rate-limit
  pressure makes the pressure worse, and shedding is the correct response.
  Sustained candidate failures trip the circuit breaker, which stops the calls
  entirely for a cooldown rather than continuing to pay for a known-bad model.
- Candidate calls carry `X-Shadow-Request: true` so mirrored traffic is
  identifiable in upstream logging and billing.

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

- `Authorization: Bearer <proxy key>` required; see
  [06-production-readiness.md](06-production-readiness.md#inbound-authentication).
  This header is consumed by the proxy and never forwarded upstream.
- `Content-Type: application/json` required.
- Body size capped at `MAX_REQUEST_BYTES` (default 1 MB), enforced by a servlet
  filter. Exceeding it returns `413`.
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
- `401` — missing or unrecognised proxy API key.
- `405` — method other than `POST`.
- `413` — body exceeds `MAX_REQUEST_BYTES`.
- `429` — per-key rate limit exceeded; includes `Retry-After`.
- `502` — primary model unreachable or returned an unreadable response.
- `503` — `MAX_CONCURRENT_REQUESTS` in flight; includes `Retry-After`.
- `504` — primary model exceeded the primary read timeout.

Error responses are produced by a `@RestControllerAdvice` so the proxy's own
failures are shaped consistently and are distinguishable from relayed upstream
error bodies.

## `GET /metric`

Real-time counters. Cheap, allocation-light, safe to scrape frequently. Served by
a hand-written controller rather than Actuator, because the Objective specifies
this exact path and summary. Requires the same bearer key as `/v1/chat`, since
volume, error rates, and match quality are operational and competitive signal.

```json
{
  "total_requests": 1482,
  "shadow_enqueued": 1301,
  "shadow_sampled_out": 0,
  "shadow_shed": 181,
  "shadow_completed": 1288,
  "shadow_errors": 9,
  "shadow_timeouts": 4,
  "shadow_circuit_open": 0,
  "evaluated": 1288,
  "exact_matches": 1174,
  "exact_match_rate": 91.15,
  "invalid_json_primary": 3,
  "invalid_json_candidate": 21,
  "queue_depth": 12,
  "queue_capacity": 256,
  "uptime_seconds": 3600
}
```

Field definitions:

- `total_requests` — requests accepted on `POST /v1/chat`.
- `shadow_enqueued` — jobs accepted onto the shadow queue.
- `shadow_sampled_out` — requests not shadowed because of `SHADOW_SAMPLE_RATE`.
  Zero at the default rate of `1.0`. Counted separately from `shadow_shed` so a
  deliberate cost control is never mistaken for burst pressure.
- `shadow_shed` — jobs dropped because the queue was full (load shed under burst).
- `shadow_completed` — jobs a worker finished processing, success or failure.
- `shadow_errors` — candidate call failed: transport error, non-2xx status,
  unreadable body, or any other exception caught in the task.
- `shadow_timeouts` — candidate exceeded its read timeout. Counted separately
  from `shadow_errors`, not in addition to it.
- `shadow_circuit_open` — jobs dropped without a network call because the
  candidate circuit breaker was open.
- `evaluated` — jobs where both primary and candidate outputs were available and
  the heuristic actually ran. This is the denominator for the match rate.
- `exact_matches` — evaluations where both sides parsed as JSON and their
  `action` values were identical.
- `exact_match_rate` — `exact_matches / evaluated * 100`, rounded to two
  decimals, and exactly `0` when `evaluated == 0` (never `NaN`).
- `invalid_json_primary` / `invalid_json_candidate` — diagnostic breakdown of
  why evaluations failed to match.
- `queue_depth` / `queue_capacity` — live saturation signal, read from the
  executor's queue via `size()` and `size() + remainingCapacity()`.

The response record is serialised with explicit `@JsonProperty` snake_case names
so the wire contract does not drift with Java field renames. Counters are
process-local and reset on restart, per the decision in [README.md](README.md).

## Deterministic evaluation rules

Implemented as pure methods on a stateless evaluator so they are exhaustively
unit-testable without Spring context. Given the primary and candidate
completions:

1. **Extract content.** Take `choices[0].message.content` from each. A missing or
   blank content counts as invalid on that side.
2. **Unwrap fences.** If the content is wrapped in a Markdown code fence
   (```` ```json ... ``` ````), strip the fence before parsing, then trim
   whitespace. Models frequently wrap JSON this way and treating it as invalid
   would misreport the candidate. Nothing else is normalised.
3. **Parse JSON.** Each side must parse into a JSON object via Jackson. Failure
   sets the corresponding `invalid_json_*` counter, and the pair cannot match.
4. **Extract `action`.** Read the top-level `action` field from both objects. It
   must be present on both sides and must be a textual node.
5. **Compare.** `String.equals`. No trimming, no case folding, no whitespace
   collapsing, no unicode normalisation — determinism is the whole point of the
   heuristic.

The outcome of every evaluation is exactly one value of an `EvalResult` enum:
`MATCH`, `MISMATCH`, `INVALID_PRIMARY`, `INVALID_CANDIDATE`, `INVALID_BOTH`,
`MISSING_ACTION`. Each maps to a specific counter update in a single `switch`,
so the numbers on `/metric` always reconcile.

### Worked examples

- Primary `{"action":"refund"}` vs candidate `{"action":"refund"}` — match.
- Primary `{"action":"refund"}` vs candidate `{"action":"Refund"}` — mismatch,
  deliberately, because comparison is case-sensitive.
- Primary `{"action":"refund"}` vs candidate ```` ```json\n{"action":"refund"}\n``` ````
  — match, after fence stripping.
- Primary `{"action":"refund","confidence":0.9}` vs candidate
  `{"action":"refund"}` — match; only `action` is compared.
- Primary `{"action":"refund"}` vs candidate `Sure! I'd refund that.` — no match,
  `invalid_json_candidate` increments.

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

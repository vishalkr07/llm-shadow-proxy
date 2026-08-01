
# DO NOT CHANGE THIS

# Summary
This is a production ready API proxy that serves customer traffic using a primary LLM endpoint, while asynchronously routing the exact same traffic to a candidate LLM in the background. The service must compare the two respinses using a determistic heuristic rile and expose real-time metrics on how the candidate model performs compares to the primary.

## Functional
1.  Sync primary proxy
```
POST /v1/chat
```
When a request comes in -> route the payload to the primary LLM using the DO serverless inference API and return the response to the user immediately

2. Async shadow execution. In background concurrentlty route the exact same request to the cnadidate LLM endpoint. The latency, error or failure of the candidate model must never delay or impact primary response returned ti the user.

3. Deterministic eval: After candidate model responds -> evaluate and compare both outputs. Implement heuristic rule checking.
  a. did both models return valid, parsable JSON payload.
  b. extract `{'action': '...'}` key from both models and assert whether they match exactly.
4. Expose `GET /metric` endpoint returning a real time summary of total request processed, shadow execution errors or timeouts, and the exact match rate % between primary and candidate output.


## TO build
- Architecture flow diagram: diageam mapping API layer, sycn + immediate path, decoupled background shadow eval poll.
- Bounded concurrency [load shed] -> background evals should be tightly managed. In heacvy burst traffic, shadow queue should shed laod and drop background evals to prevent memopry leak. Primary app should be protected from burst.
- CI/CD github actions.
Doc: README - setup
- section - how this bounds memory footprint

---

## Next
- Persistance traces: aycn stream mismatched payload to local sqlite DB file for debugging and visualization
- Dynamic config: PUT /config API allowing runtime updates to the shadown routing % (dynamically throttle from 100% down to 50% traffic mirror)
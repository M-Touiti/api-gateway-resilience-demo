# ADR-0006: Shed load with a concurrency limit per route

- **Status:** Accepted, limit untuned
- **Decided:** 2026-11-01 (recorded 2026-11-01)

## Context

Rate limits apply per user, so many users within their own limits can still send more than the gateway can handle. The first load tests showed latency rising sharply past capacity, with nothing to stop requests queuing.

A hung dependency adds to the problem, because its requests hold their place until they time out. A single limit across the gateway would let one hung route use up the allowance of the others.

The alternatives were a limit on requests per second, which needs a capacity figure that changes with the hardware and the downstream services, and an adaptive limit, which is more to build and harder to reason about.

## Decision

Add **`ConcurrencyLimitFilter`**, a global filter that runs first and counts requests in flight per route. Above the limit, a new request gets a 503 with `Retry-After: 1`, and no JWT validation, Redis call or downstream call is made. Shed requests are counted in the `gateway.requests.shed` metric.

The limit is 512 per route by default, set with `MAX_CONCURRENT_REQUESTS_PER_ROUTE`. A value of 0 turns shedding off.

## Consequences

**Benefits**

- *Measured* on one gateway at 3,000 to 5,000 req/s: successful requests had a p50 of 83 to 310 ms, against 813 to 1,008 ms without shedding. The slowest request fell from about 3.5 s to about 1.2 s. [Results](../../performance/README.md#overload-and-load-shedding)
- The test without shedding understates the benefit, because k6 reached its cap of 3,000 concurrent requests and didn't send up to 47% of the offered load.
- Clients get a clear answer that they can retry later. Every 503 in the capacity and overload runs was a shed request, not a breaker fallback.
- A count of requests in flight needs no capacity figure, because it reacts to slowdowns however they're caused.

**Costs**

- **Fewer requests succeed under overload.** *Measured:* 4% fewer successes per second at 3,000 req/s and 21% fewer at 5,000 req/s. The cause wasn't established.
- **The limit is untuned.** At about 2,100 successes per second, 512 requests in flight means about 240 ms per request, which is close to what was measured. A lower limit should reduce latency, but a limit that's too low would shed traffic the gateway could serve.
- **A sudden jump can trigger it below sustained capacity.** *Measured:* going straight from 300 to 2,000 req/s shed up to 5,510 requests in a minute, at a rate one gateway can carry when traffic rises in stages.
- **Shed responses aren't instant at saturation.** *Measured:* at 5,000 req/s, at least 1 in 10 took about 200 ms or more, probably waiting for the event loop before the filter could run.
- The limit applies per instance and per route, so the total allowed grows with the number of instances and routes.
- The per-route protection for a hung dependency hasn't been shown directly. In the frozen-service test the other route served every request, but that test doesn't record the shed metric, so it can't show how much the limit contributed.
- All requests on a route count the same, so a payment has no priority over a cheap read on the same route.

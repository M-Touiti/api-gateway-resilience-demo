# ADR-0003: Give each dependency its own circuit breaker and fallback

- **Status:** Accepted
- **Decided:** 2026-05-14 (recorded 2026-10-31)

## Context

When a downstream service fails, callers shouldn't wait on it, and it shouldn't take other routes down with it. The dependencies also carry different risk. A failed profile lookup is an inconvenience, while uncertainty around a payment is more serious and calls for quicker protection and a slower return to normal.

Clients also need a clear, consistent answer during an outage rather than a raw connection error.

## Decision

Use **Resilience4j through Spring Cloud CircuitBreaker**, with a separate named breaker per route and a `FallbackController` that returns a structured 503.

| Breaker | Opens at | Wait in OPEN | Trial calls | Time limit |
|---|---|---|---|---|
| `user-service-auth-cb`, `user-service-cb` | 50% failures | 10 s | 3 | 5 s |
| `payment-service-cb` | 30% failures | 30 s | 2 | 8 s |

Breakers use a 10-call count-based window. `ResilienceConfig` is the source of truth for breaker state settings. `FallbackController` uses `@RequestMapping` because forwarding keeps the original HTTP method, so a POST would get a 405 from a GET-only handler.

## Consequences

**Benefits**

- *Measured,* with user-service killed at 200 req/s: the breaker opened about 5 s later, then answered with a 503 fallback at about 1.3 ms median instead of a 5 s wait. [Results](../../performance/README.md#downstream-outage)
- *Measured:* failure stayed contained. The payments route served all of its requests with a 200 throughout, and its typical per-second p95 stayed about the same (4.9 ms during the outage, 5.3 ms before). With user-service frozen rather than killed: all 3,000 payments requests during the freeze returned 200.
- *Measured:* recovery needed no intervention. Traffic returned to 200s about 11 s after the service restarted.
- Breaker state is visible in Actuator and in the Grafana dashboard.

**Costs**

- **HTTP error responses don't count as failures.** Only exceptions and timeouts are recorded, because no `statusCodes` are configured, so a service returning nothing but 500s never opens its breaker. Configure `statusCodes` if that protection is needed.
- Requests already in flight when a service dies still wait for the full time limit. *Measured:* the p99 across the outage run was 5.0 s, from requests that were in flight until the breaker opened or were trial calls during the outage, which wait up to the 5 s limit.
- Containment isn't total. *Measured:* payments p95 spiked to about 650 ms for about 3 s as the users breaker opened, although no payments request failed.
- A short window reacts quickly but is noisy at low traffic, where a few failures can open the breaker.
- Settings appear in both `ResilienceConfig` and `application.yml`, so they can drift apart. Only one place should own them.
- The stricter payment settings weren't exercised by the load tests, which only killed user-service. The Postman "Resilience Demos" folder covers that breaker manually.
- The fallback body always says `retryAfter: 30s`, which overstates the 10 s wait on user-service routes.

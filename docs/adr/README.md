# Architecture Decision Records

These records explain why the gateway is built the way it is, including what each choice costs. They use the [Michael Nygard format](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions): Context, Decision, Consequences.

They were written after the decisions were made. The decision dates come from the git history, and consequences marked *measured* link to the [load-test report](../../performance/README.md). Measurements are from the fixed code unless marked otherwise.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-reactive-spring-cloud-gateway.md) | Use reactive Spring Cloud Gateway as the single entry point | Accepted |
| [0002](0002-redis-token-bucket-rate-limiting.md) | Rate limit with a Redis token bucket that fails open | Accepted |
| [0003](0003-per-dependency-circuit-breakers.md) | Give each dependency its own circuit breaker and fallback | Accepted |
| [0004](0004-retry-only-idempotent-requests.md) | Retry only safe requests, inside the circuit breaker | Accepted, follow-up open |
| [0005](0005-jwt-validation-at-the-edge.md) | Validate JWTs at the gateway and pass identity in headers | Accepted, for a trusted network only |
| [0006](0006-per-route-load-shedding.md) | Shed load with a concurrency limit per route | Accepted, limit untuned |

To add a record, copy the structure of an existing one and use the next number. Don't rewrite an accepted record when a decision changes: add a new one and mark the old one as superseded.

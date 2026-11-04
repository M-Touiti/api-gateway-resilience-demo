# ADR-0001: Use reactive Spring Cloud Gateway as the single entry point

- **Status:** Accepted
- **Decided:** 2026-05-14 (recorded 2026-10-31)

## Context

Authentication, rate limiting and failure handling would otherwise have to be repeated in every downstream service. Putting them in one component means that component sits on the path of every request, so it must:

- hold many concurrent connections open while downstream services respond
- add very little latency
- let each route have its own policy (a payment route needs stricter limits than a profile route)
- suit a team that already works in Java and Spring

The alternatives were Spring Cloud Gateway MVC (thread per request), and NGINX or Kong (extended through configuration, Lua or plugins rather than Java).

## Decision

Use **Spring Cloud Gateway on WebFlux and Netty** as the only public entry point. Routes and their filter chains are declared in `application.yml`, and custom behaviour is added as `GatewayFilterFactory` classes.

Every protected route runs in the same order: load shedding → logging → JWT → rate limit → circuit breaker → retry → downstream. Rejected requests stop as early as possible, so an invalid token never uses a Redis call or a downstream connection.

## Consequences

**Benefits**

- A slow downstream service holds a connection, not a thread. *Measured:* requests continued to be served while another route's dependency was down and its calls were waiting on 5 s timeouts.
- Overhead is small. *Measured:* about 0.4 ms added at the median and 1.1 ms at p95 at 500 req/s, including JWT validation and a Redis call.
- It scales out. *Measured:* one gateway limited to 2 CPUs carried about 2,000 req/s and two carried about 4,000 req/s, with no failed requests. At 2,000 req/s per gateway, p95 was 6.6 ms with one and 41 ms with two, on a single shared laptop. [Results](../../performance/README.md#capacity-and-scale-out)
- Routing and resilience policy can be reviewed in a single YAML file.

**Costs**

- Blocking on an event-loop thread stalls every connection that thread serves. *Measured:* two per-request INFO log lines cost enough that setting the log level to WARN and tuning the JVM carried about 1,490 req/s where the original settings managed about 1,130 in a single probe run, so those lines are now at DEBUG. Gateway CPU at the same rates then fell by 40% to 60%, though parsing each JWT once also changed in that round. Opening the first Redis connection on these threads made 99.97% of requests time out when Redis was absent at startup, so it now happens on a background thread.
- Reactive code is harder to debug. Stack traces are less useful, and filter ordering has subtle effects: `AddResponseHeader` inside a circuit breaker fails on a committed response and triggers the fallback.
- The JVM needs time to warm up. *Measured:* p95 was 2 to 3 s during the first minute after a restart, against about 2.5 ms in the second minute, so a new instance should get traffic gradually.
- The gateway is a single point of failure unless several instances run behind a load balancer. This repository doesn't include one.

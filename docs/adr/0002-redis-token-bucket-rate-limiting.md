# ADR-0002: Rate limit with a Redis token bucket that fails open

- **Status:** Accepted
- **Decided:** 2026-05-14 (recorded 2026-10-31)

## Context

The gateway has to protect downstream services from overload and stop one client from starving the others. Limits must hold across gateway instances: with in-memory counters, N instances would allow N times the intended rate.

Different routes need different keys and limits. Authenticated routes should be limited per user, and public auth routes can only be limited per client address. Real traffic also arrives in bursts, which a limiter should allow within reason.

## Decision

Use Spring Cloud Gateway's **`RedisRateLimiter`**, a token bucket applied atomically by a Lua script in Redis.

| Route | Key | Refill | Burst |
|---|---|---|---|
| `/api/v1/auth/**` | client IP | 20/s | 40 |
| `/api/v1/users/**` | JWT subject | 50/s | 100 |
| `/api/v1/payments/**` | JWT subject | 10/s | 20 |

If Redis is unavailable, the limiter **fails open** and allows the request. Availability of the API is valued over strict limiting during an outage.

Failing open must also be cheap, so the Redis client fails fast: it rejects commands while disconnected, caps unanswered commands at 1,024, and uses a 200 ms command timeout. The first connection is opened on a background thread.

The IP key uses the connection's address. `X-Forwarded-For` is read only when `TRUSTED_PROXY_HOPS` says how many trusted proxies add to it.

## Consequences

**Benefits**

- *Measured:* limits are shared across instances. One user was admitted 3,103 requests through one gateway and 3,092 through two, against a theoretical 3,100. [Results](../../performance/README.md#rate-limiting)
- *Measured:* limits are accurate on the auth route, with 1,241 of an expected 1,240 admitted. Payments is the exception: one run admitted 669 of an expected 620, for a reason not yet established.
- Rejections are cheap, with a median of about 1.3 ms.
- *Measured:* with Redis killed, frozen or absent at startup, a gateway with a 256 MB heap had no failed requests at 250 req/s (200 req/s when absent), and limits applied again once Redis returned. [Results](../../performance/README.md#redis-outage)

**Costs**

- Every admitted request makes a Redis round trip, and Redis becomes a dependency to operate.
- **The default client settings weren't enough.** *Measured* with them, at 250 req/s: every request waited for the 2 s Redis timeout, and a 256 MB heap ran out and the gateway stayed down after Redis returned. These settings must stay in place.
- If Redis freezes rather than dies, requests wait up to 200 ms each until 1,024 commands are pending. A brief slow spell in Redis can also let a few requests through unlimited, and after a Redis restart about 2 s of traffic got through unlimited.
- Limits aren't enforced during an outage, so downstream services lose this protection exactly when things are already going wrong.
- `/actuator/health` reports DOWN during an outage while the gateway is serving normally. Load balancers must use `/actuator/health/readiness`.
- **`TRUSTED_PROXY_HOPS` must match the deployment.** *Measured* when the header was always trusted: changing it on each request gave 11,339 admitted and 0 rejected, against an expected 1,240. With it ignored, the same test admitted 1,240. If it's left at 0 behind a load balancer, all clients share one limit.

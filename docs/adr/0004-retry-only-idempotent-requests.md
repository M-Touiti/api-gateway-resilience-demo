# ADR-0004: Retry only safe requests, inside the circuit breaker

- **Status:** Accepted, follow-up open
- **Decided:** 2026-05-14 (recorded 2026-10-31)

## Context

Many failures are brief, such as a restart or a dropped connection, and a quick retry hides them from the client. Retries also have risks:

- Retrying a non-idempotent request can repeat its effect. For a payment, that means charging a customer twice.
- Retrying without a delay adds load to a service that is already struggling.
- Retrying outside a circuit breaker can multiply traffic and distort the breaker's failure count.

## Decision

Use the Spring Cloud Gateway **`Retry` filter with exponential backoff**, set per route according to what is safe to repeat.

| Route | Methods retried | Statuses | Retries | Backoff |
|---|---|---|---|---|
| `/api/v1/payments/**` | GET only | 502, 504 | 1 | 500 ms |
| `/api/v1/users/**` | GET only | 502, 503, 504 | 3 | 200 ms → 1 s |
| `/api/v1/auth/**` | GET, POST | 502, 503 | 2 | 100 ms → 500 ms |

**`POST /payments` is never retried.** The client decides what to do after a failure, because the gateway can't tell whether the charge went through.

`Retry` is placed **inside** `CircuitBreaker`. The breaker records one result per client request, and all attempts share that request's time limit.

## Consequences

**Benefits**

- Double charges can't be caused by the gateway. This holds by configuration rather than relying on downstream behaviour.
- Brief failures on read routes can be hidden from clients.
- Retries are limited by the time limit, and an open breaker stops them altogether, which limits retry storms.

**Costs**

- **Payment POST failures reach the client.** Safe automatic retries would need idempotency keys, which aren't implemented.
- Retries add latency before a failure is reported. With the backoff above, a failing users GET can spend up to 1.4 s waiting between attempts alone.
- Because the breaker counts one result per client request, it sees underlying failures later than it would with retries outside it.
- Backoff has no jitter configured, so clients failing at the same moment retry at the same moment.
- The load tests didn't measure retry behaviour. The `POST /auth/flaky` endpoint exists for checking it by hand.

**Follow-up**

The auth route retries POST, which is reasonable for login but not clearly safe for `POST /auth/register`: a retry could attempt to create the same account twice. Limit POST retries to login, or make registration idempotent. This route breaks the rule in this ADR's title, so the exception should be resolved rather than left in place.

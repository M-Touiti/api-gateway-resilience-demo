# api-gateway-resilience-demo

A production-grade API Gateway built with Spring Cloud Gateway, implementing Circuit Breaker, Rate Limiting, Retry, and JWT authentication — demonstrating resilience patterns for distributed microservices architectures.

Built as a project to showcase enterprise-grade distributed systems design applicable to fintech, e-commerce, and cloud-native platforms.

---

## Architecture

```
                        ┌─────────────────────────────────────────────┐
                        │              API GATEWAY :8080               │
                        │                                              │
  Client Request        │  ┌──────────────────────────────────────┐   │
  ──────────────►       │  │ 1. RequestLoggingFilter (Global)      │   │
                        │  │    → logs method, path, latency       │   │
                        │  └──────────────┬───────────────────────┘   │
                        │                 │                            │
                        │  ┌──────────────▼───────────────────────┐   │
                        │  │ 2. JwtAuthenticationFilter (per-route)│   │
                        │  │    → validates JWT                    │   │
                        │  │    → injects X-User-Email, X-User-Role│   │
                        │  └──────────────┬───────────────────────┘   │
                        │                 │                            │
                        │  ┌──────────────▼───────────────────────┐   │
                        │  │ 3. RedisRateLimiter (per-route)       │   │
                        │  │    → per-user: 50 req/s (users)       │   │
                        │  │    → per-user: 10 req/s (payments)    │   │
                        │  │    → per-IP:   20 req/s (auth)        │   │
                        │  └──────────────┬───────────────────────┘   │
                        │                 │                            │
                        │  ┌──────────────▼───────────────────────┐   │
                        │  │ 4. CircuitBreaker (Resilience4j)      │   │
                        │  │    → CLOSED → OPEN at 50% failure     │   │
                        │  │    → OPEN → HALF_OPEN after 10s       │   │
                        │  │    → fallback: /fallback/{service}    │   │
                        │  └──────────────┬───────────────────────┘   │
                        │                 │                            │
                        │  ┌──────────────▼───────────────────────┐   │
                        │  │ 5. Retry (on 502/503/504)             │   │
                        │  │    → exponential backoff              │   │
                        │  └──────────────┬───────────────────────┘   │
                        └─────────────────│───────────────────────────┘
                                          │
              ┌───────────────────────────┼───────────────────────────┐
              │                           │                           │
              ▼                           ▼                           │
   ┌──────────────────┐      ┌──────────────────────┐                │
   │  user-service    │      │  payment-service      │                │
   │  :8081           │      │  :8082                │                │
   │                  │      │                       │                │
   │  GET  /users/me  │      │  GET  /payments       │                │
   │  GET  /users     │      │  POST /payments       │                │
   │  POST /auth/login│      │  GET  /payments/slow  │                │
   └──────────────────┘      └──────────────────────┘                │
              │                                                        │
              │              ┌──────────────────────┐                 │
              │              │  Redis :6379           │                │
              └──────────────►  Rate limit counters  │◄───────────────┘
                             └──────────────────────┘
```

---

## Resilience Patterns

### 1. Circuit Breaker (Resilience4j)

| Service | Failure threshold | Wait in OPEN | Half-open calls |
|---|---|---|---|
| user-service | 50% | 10s | 3 |
| payment-service | 30% (stricter) | 30s | 2 |

**States:**
- `CLOSED` → normal operation, requests pass through
- `OPEN` → circuit open, all requests return fallback immediately (no downstream call)
- `HALF_OPEN` → test calls allowed, closes if successful

### 2. Rate Limiting (Redis Token Bucket)

| Route | Key | Replenish rate | Burst |
|---|---|---|---|
| `/api/v1/auth/**` | per IP | 20 req/s | 40 |
| `/api/v1/users/**` | per JWT user | 50 req/s | 100 |
| `/api/v1/payments/**` | per JWT user | 10 req/s | 20 |

Returns `429 Too Many Requests` with `X-RateLimit-*` headers when exceeded.

### 3. Retry with Exponential Backoff

```
Attempt 1 ─── fail ─► wait 200ms ─► Attempt 2 ─── fail ─► wait 400ms ─► Attempt 3
```

- Only retries on `502 Bad Gateway`, `503 Service Unavailable`, `504 Gateway Timeout`
- `POST /payments` is **never retried** (idempotency — avoid double charges)

### 4. JWT Authentication Filter

- Validates Bearer token signature and expiration
- Extracts `email` and `role` from claims
- Forwards to downstream as `X-User-Email` and `X-User-Role` headers
- Removes `Authorization` header (JWT not forwarded downstream)
- Sets `userEmail` attribute for rate limiter key resolver

---

## Tech Stack

- **Java 21** — Records, modern idioms
- **Spring Cloud Gateway 4.x** — Reactive (WebFlux) API gateway
- **Spring Cloud Circuit Breaker + Resilience4j** — Reactive circuit breaker, retry, bulkhead
- **Spring Data Redis Reactive** — Distributed rate limiting
- **JJWT 0.12** — JWT validation in gateway filter
- **Micrometer + Prometheus** — Circuit breaker metrics, latency histograms
- **WireMock** — HTTP stub for integration tests
- **Docker / Docker Compose** — Full local environment

---

## Getting Started

### Prerequisites
- Java 21+
- Docker & Docker Compose

### Run the full stack

```bash
# 1. Clone the repo
git clone https://github.com/your-username/api-gateway-resilience-demo.git
cd api-gateway-resilience-demo

# 2. Build all services
mvn clean package -DskipTests

# 3. Start everything (Redis + 2 services + gateway)
docker-compose up -d

# 4. Check gateway health
curl http://localhost:8080/actuator/health
```

**UIs available:**
- Gateway: http://localhost:8080
- Redis Commander: http://localhost:8090
- Actuator circuit breakers: http://localhost:8080/actuator/circuitbreakers

### Run tests

```bash
# Unit tests (JwtUtil — no infrastructure)
mvn test -pl gateway-service -Dtest="**/unit/**"

# Integration tests (WireMock — no real services needed)
mvn test -pl gateway-service -Dtest="**/integration/**"
```

---

## API Routes

All routes are proxied through the gateway on port **8080**.

### Public (no JWT required)
```
POST  /api/v1/auth/register     → user-service
POST  /api/v1/auth/login        → user-service
```

### Protected (JWT required)
```
GET   /api/v1/users/me          → user-service  (50 req/s per user)
GET   /api/v1/users             → user-service  (ADMIN only)
GET   /api/v1/payments          → payment-service (10 req/s per user)
POST  /api/v1/payments          → payment-service (no retry)
```

---

## Testing Resilience Patterns

### Test the Circuit Breaker (payment-service)

```bash
# 1. Generate a JWT (use the login endpoint)
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@test.com","password":"pass"}' | jq -r .accessToken)

# 2. Trigger failures to open the circuit (30% threshold = 3/10 calls)
for i in {1..5}; do
  curl -s http://localhost:8080/api/v1/payments/error \
    -H "Authorization: Bearer $TOKEN"
done

# 3. Observe circuit is now OPEN — fallback is returned immediately
curl http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN"
# → {"status":503,"message":"Payment service is temporarily unavailable..."}

# 4. Check circuit state via Actuator
curl http://localhost:8080/actuator/circuitbreakers | jq .
```

### Test Rate Limiting

```bash
# Hammer the auth endpoint — observe 429 after 40 requests
for i in {1..50}; do
  curl -o /dev/null -s -w "%{http_code}\n" \
    -X POST http://localhost:8080/api/v1/auth/login \
    -H "Content-Type: application/json" \
    -d '{"email":"test@test.com","password":"pass"}'
done
# → 200 200 ... 429 429 429
```

### Test Timeout + Fallback

```bash
# user-service /slow takes 6s, gateway timeout is 5s
curl http://localhost:8080/api/v1/users/slow \
  -H "Authorization: Bearer $TOKEN"
# → fallback response after 5s (not 6s)
```

---

## Actuator Endpoints

```
GET /actuator/health              → overall health + circuit breaker states
GET /actuator/circuitbreakers     → all CB states (CLOSED/OPEN/HALF_OPEN)
GET /actuator/metrics             → all metrics
GET /actuator/prometheus          → Prometheus scrape endpoint
GET /actuator/gateway/routes      → all configured gateway routes
```

---

## Design Decisions

**Why WebFlux (reactive) for the gateway?**
Spring Cloud Gateway is built on WebFlux and Netty. Reactive I/O allows the gateway to handle thousands of concurrent connections with a small thread pool, essential for a component that proxies every request in the system.

**Why Redis for rate limiting instead of in-memory?**
In-memory rate limiters don't work in multi-instance deployments — each instance has its own counter. Redis provides a shared, atomic token bucket across all gateway instances, making rate limits accurate at scale.

**Why different thresholds for payment-service?**
Payment failures are more critical than user-service failures — a partial failure might indicate fraud, infrastructure issues, or data corruption. Opening the circuit faster (30% vs 50%) and keeping it open longer (30s vs 10s) prevents cascading damage.

**Why not retry POST /payments?**
Retrying a payment POST risks double charges. Only idempotent methods (GET) are retried. For payments, the client must handle failures explicitly.

---

## License

MIT

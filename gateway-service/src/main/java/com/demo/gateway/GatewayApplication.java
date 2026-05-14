package com.demo.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API Gateway entry point.
 *
 * This gateway handles:
 * - Dynamic routing to downstream microservices
 * - JWT authentication validation (before forwarding requests)
 * - Rate limiting per route and per client (Redis-backed)
 * - Circuit breaker with automatic fallback (Resilience4j)
 * - Retry on transient failures
 * - Centralized request/response logging with latency tracking
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}

package com.demo.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j configuration for the reactive circuit breakers used in the gateway.
 *
 * Circuit Breaker states:
 * ┌─────────┐  failure rate ≥ 50%   ┌──────┐
 * │ CLOSED  │ ───────────────────► │ OPEN │
 * └─────────┘                       └──────┘
 *      ▲                                │
 *      │         after 10s wait         │
 *      │   ┌────────────┐              │
 *      └── │ HALF_OPEN  │ ◄────────────┘
 *          └────────────┘
 *          (3 test calls)
 */
@Configuration
public class ResilienceConfig {

    /**
     * Default circuit breaker: applies to all routes unless overridden.
     * - Opens after 50% failure rate over a 10-call sliding window
     * - Waits 10s before transitioning to HALF_OPEN
     * - Closes again after 3 successful calls in HALF_OPEN
     */
    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCircuitBreakerCustomizer() {
        return factory -> factory.configureDefault(id ->
                new Resilience4JConfigBuilder(id)
                        .timeLimiterConfig(TimeLimiterConfig.custom()
                                .timeoutDuration(Duration.ofSeconds(5))
                                .build())
                        .circuitBreakerConfig(CircuitBreakerConfig.custom()
                                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                                .slidingWindowSize(10)
                                .failureRateThreshold(50)
                                .waitDurationInOpenState(Duration.ofSeconds(10))
                                .permittedNumberOfCallsInHalfOpenState(3)
                                .slowCallDurationThreshold(Duration.ofSeconds(3))
                                .slowCallRateThreshold(80)
                                .recordExceptions(
                                        java.io.IOException.class,
                                        java.util.concurrent.TimeoutException.class,
                                        org.springframework.web.server.ResponseStatusException.class
                                )
                                .build())
                        .build());
    }

    /**
     * Stricter circuit breaker for the payment service:
     * - Tighter failure rate (30%) — payment failures are more critical
     * - Longer wait in OPEN state (30s) — give payment service more recovery time
     */
    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> paymentCircuitBreakerCustomizer() {
        return factory -> factory.configure(builder ->
                builder.timeLimiterConfig(TimeLimiterConfig.custom()
                                .timeoutDuration(Duration.ofSeconds(8))
                                .build())
                        .circuitBreakerConfig(CircuitBreakerConfig.custom()
                                .slidingWindowSize(10)
                                .failureRateThreshold(30)
                                .waitDurationInOpenState(Duration.ofSeconds(30))
                                .permittedNumberOfCallsInHalfOpenState(2)
                                .build()),
                "payment-service-cb");
    }
}

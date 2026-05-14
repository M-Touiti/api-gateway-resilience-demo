package com.demo.gateway.fallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Fallback controller invoked by the circuit breaker when a downstream
 * service is unavailable (circuit OPEN) or has timed out.
 *
 * Each route in application.yml maps to a specific fallback URI:
 *   uri: forward:/fallback/user-service
 *   uri: forward:/fallback/payment-service
 *
 * Returns a structured error body instead of a raw 503.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    @GetMapping("/user-service")
    public Mono<ResponseEntity<Map<String, Object>>> userServiceFallback(ServerWebExchange exchange) {
        return buildFallback(exchange, "user-service",
                "User service is temporarily unavailable. Please try again in a moment.");
    }

    @GetMapping("/payment-service")
    public Mono<ResponseEntity<Map<String, Object>>> paymentServiceFallback(ServerWebExchange exchange) {
        return buildFallback(exchange, "payment-service",
                "Payment service is temporarily unavailable. Your request has not been processed. " +
                        "Please try again or contact support.");
    }

    @GetMapping("/default")
    public Mono<ResponseEntity<Map<String, Object>>> defaultFallback(ServerWebExchange exchange) {
        return buildFallback(exchange, "unknown",
                "The requested service is temporarily unavailable. Please try again later.");
    }

    private Mono<ResponseEntity<Map<String, Object>>> buildFallback(
            ServerWebExchange exchange, String service, String message) {

        String path = exchange.getRequest().getPath().value();
        log.warn("Circuit breaker fallback triggered for service={} path={}", service, path);

        Map<String, Object> body = Map.of(
                "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "error", "Service Unavailable",
                "message", message,
                "service", service,
                "path", path,
                "timestamp", Instant.now().toString(),
                "retryAfter", "30s"
        );

        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body));
    }
}

package com.demo.paymentservice.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Payment service REST controller.
 *
 * Includes endpoints to manually trigger failures for circuit breaker demos:
 * - GET /api/v1/payments/error     → always returns 500 (opens the circuit)
 * - GET /api/v1/payments/slow      → sleeps 10s (triggers gateway timeout)
 * - POST /api/v1/payments/reset    → resets the failure counter
 *
 * In a real system this would connect to a payment processor (Stripe, Adyen, etc.).
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    // Simulates a failure mode toggle — controlled via /payments/toggle-failure
    private static final AtomicInteger failureMode = new AtomicInteger(0);

    @GetMapping
    public ResponseEntity<Map<String, Object>> listPayments(
            @RequestHeader(value = "X-User-Email", defaultValue = "unknown") String userEmail) {

        return ResponseEntity.ok(Map.of(
                "payments", java.util.List.of(
                        buildPayment("TXN-001", "COMPLETED", BigDecimal.valueOf(150.00)),
                        buildPayment("TXN-002", "COMPLETED", BigDecimal.valueOf(89.99)),
                        buildPayment("TXN-003", "PENDING",   BigDecimal.valueOf(250.00))
                ),
                "owner", userEmail,
                "service", "payment-service"
        ));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createPayment(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-Email", defaultValue = "unknown") String userEmail) {

        if (failureMode.get() > 0) {
            failureMode.decrementAndGet();
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Payment processor unavailable (simulated failure)"));
        }

        return ResponseEntity.status(201).body(Map.of(
                "id", UUID.randomUUID().toString(),
                "status", "COMPLETED",
                "amount", body.getOrDefault("amount", 0),
                "currency", body.getOrDefault("currency", "EUR"),
                "owner", userEmail,
                "createdAt", LocalDateTime.now().toString()
        ));
    }

    /**
     * Simulates permanent failure — used to trigger circuit breaker OPEN state.
     * Call this endpoint 3-4 times to observe the circuit opening.
     */
    @GetMapping("/error")
    public ResponseEntity<Map<String, Object>> simulateError() {
        return ResponseEntity.status(500)
                .body(Map.of("error", "Payment service internal error (circuit breaker test)"));
    }

    /**
     * Simulates a slow response — triggers the gateway's time limiter (8s timeout).
     */
    @GetMapping("/slow")
    public ResponseEntity<Map<String, Object>> simulateSlow() throws InterruptedException {
        Thread.sleep(10_000); // 10s > gateway timeout (8s)
        return ResponseEntity.ok(Map.of("message", "Too slow — gateway timed out before this"));
    }

    /**
     * Enables failure mode for N subsequent POST /payments calls.
     * Used to demonstrate circuit breaker opening under load.
     */
    @PostMapping("/enable-failure")
    public ResponseEntity<Map<String, Object>> enableFailure(@RequestParam(defaultValue = "5") int count) {
        failureMode.set(count);
        return ResponseEntity.ok(Map.of(
                "message", "Failure mode enabled for next " + count + " requests",
                "remainingFailures", failureMode.get()
        ));
    }

    private Map<String, Object> buildPayment(String id, String status, BigDecimal amount) {
        return Map.of(
                "id", id,
                "status", status,
                "amount", amount,
                "currency", "EUR",
                "createdAt", LocalDateTime.now().minusDays(1).toString()
        );
    }
}

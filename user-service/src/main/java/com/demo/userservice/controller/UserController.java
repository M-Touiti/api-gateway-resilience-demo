package com.demo.userservice.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * User service REST controller.
 *
 * Demonstrates how downstream services receive enriched headers from the gateway:
 * - X-User-Email: the authenticated user's email (set by JwtAuthenticationFilter)
 * - X-User-Role:  the user's role
 * - X-Gateway-Source: "api-gateway" (set by AddRequestHeader filter)
 *
 * No JWT validation here — authentication is fully handled at the gateway level.
 */
@RestController
@RequestMapping("/api/v1")
public class UserController {

    /**
     * GET /api/v1/users/me
     * Returns the current authenticated user profile.
     * User identity comes from gateway-injected headers.
     */
    @GetMapping("/users/me")
    public ResponseEntity<Map<String, Object>> getMyProfile(
            @RequestHeader(value = "X-User-Email", defaultValue = "unknown") String userEmail,
            @RequestHeader(value = "X-User-Role", defaultValue = "USER") String userRole,
            @RequestHeader(value = "X-Gateway-Source", defaultValue = "direct") String gatewaySource) {

        return ResponseEntity.ok(Map.of(
                "email", userEmail,
                "role", userRole,
                "gatewaySource", gatewaySource,
                "service", "user-service",
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * GET /api/v1/users
     * Lists all users — ADMIN only (enforced by role header from gateway).
     */
    @GetMapping("/users")
    public ResponseEntity<?> listUsers(
            @RequestHeader(value = "X-User-Role", defaultValue = "USER") String userRole) {

        if (!"ADMIN".equals(userRole)) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Admin access required"));
        }

        return ResponseEntity.ok(List.of(
                Map.of("id", "1", "email", "alice@example.com", "role", "USER"),
                Map.of("id", "2", "email", "bob@example.com", "role", "USER"),
                Map.of("id", "3", "email", "admin@example.com", "role", "ADMIN")
        ));
    }

    /**
     * POST /api/v1/auth/login — public endpoint (no JWT required).
     * In a real system this would validate credentials and issue a JWT.
     */
    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(Map.of(
                "accessToken", "demo-access-token-" + System.currentTimeMillis(),
                "refreshToken", "demo-refresh-token",
                "tokenType", "Bearer",
                "expiresIn", 900000
        ));
    }

    /**
     * POST /api/v1/auth/register — public endpoint.
     */
    @PostMapping("/auth/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        return ResponseEntity.status(201).body(Map.of(
                "id", "new-user-id",
                "email", body.getOrDefault("email", ""),
                "role", "USER",
                "createdAt", LocalDateTime.now().toString()
        ));
    }

    /**
     * GET /api/v1/users/slow — simulates a slow endpoint (for circuit breaker testing).
     */
    @GetMapping("/users/slow")
    public ResponseEntity<Map<String, Object>> slowEndpoint() throws InterruptedException {
        Thread.sleep(6000); // exceeds gateway timeout (5s) → triggers circuit breaker
        return ResponseEntity.ok(Map.of("message", "This response is too slow"));
    }

    /**
     * GET /api/v1/users/error — simulates a failing endpoint (for circuit breaker testing).
     */
    @GetMapping("/users/error")
    public ResponseEntity<Map<String, Object>> errorEndpoint() {
        return ResponseEntity.status(500)
                .body(Map.of("error", "Simulated internal server error"));
    }
}

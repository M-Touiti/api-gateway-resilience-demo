package com.demo.gateway.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Stateless JWT utility used by the gateway to validate tokens
 * and extract claims — without Spring Security (WebFlux-compatible).
 */
@Component
public class JwtUtil {

    private final JwtParser parser;

    public JwtUtil(@Value("${app.jwt.secret}") String secret) {
        this.parser = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    /**
     * Verifies the token and returns its claims, or empty if it is invalid or expired.
     * Runs on an event-loop thread, so callers should reuse the result rather than parse again.
     */
    public Optional<Claims> parseClaims(String token) {
        try {
            return Optional.of(parser.parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

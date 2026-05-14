package com.demo.gateway.unit;

import com.demo.gateway.config.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private static final String SECRET = "my-very-secret-key-that-is-at-least-256-bits-long-for-hs256";
    private JwtUtil jwtUtil;
    private SecretKey secretKey;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET);
        secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private String buildToken(String email, String role, long expirationMs) {
        return Jwts.builder()
                .subject(email)
                .claims(Map.of("role", role))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(secretKey)
                .compact();
    }

    @Test
    void shouldValidateCorrectToken() {
        String token = buildToken("user@test.com", "USER", 900_000);
        assertThat(jwtUtil.isValid(token)).isTrue();
    }

    @Test
    void shouldExtractEmailFromToken() {
        String token = buildToken("user@test.com", "USER", 900_000);
        assertThat(jwtUtil.extractEmail(token)).isEqualTo("user@test.com");
    }

    @Test
    void shouldExtractRoleFromToken() {
        String token = buildToken("admin@test.com", "ADMIN", 900_000);
        assertThat(jwtUtil.extractRole(token)).isEqualTo("ADMIN");
    }

    @Test
    void shouldRejectExpiredToken() {
        String token = buildToken("user@test.com", "USER", -1000); // already expired
        assertThat(jwtUtil.isValid(token)).isFalse();
    }

    @Test
    void shouldRejectTamperedToken() {
        String token = buildToken("user@test.com", "USER", 900_000);
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertThat(jwtUtil.isValid(tampered)).isFalse();
    }

    @Test
    void shouldRejectEmptyToken() {
        assertThat(jwtUtil.isValid("")).isFalse();
    }

    @Test
    void shouldRejectTokenWithWrongSecret() {
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "another-completely-different-secret-key-256-bits!!".getBytes(StandardCharsets.UTF_8));
        String tokenWithWrongSecret = Jwts.builder()
                .subject("user@test.com")
                .expiration(new Date(System.currentTimeMillis() + 900_000))
                .signWith(wrongKey)
                .compact();
        assertThat(jwtUtil.isValid(tokenWithWrongSecret)).isFalse();
    }
}

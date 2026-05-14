package com.demo.gateway.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Integration tests for the API Gateway.
 *
 * Tests:
 * - JWT filter rejects requests without a token (401)
 * - JWT filter rejects requests with invalid tokens (401)
 * - Valid JWT allows routing to the downstream service
 * - Gateway correctly forwards X-User-Email header to downstream
 * - Public routes (/auth/**) do not require JWT
 *
 * Uses WireMock to stub downstream services — no real services needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.data.redis.host=localhost")
class GatewayRoutingIntegrationTest {

    private static final String SECRET =
            "my-very-secret-key-that-is-at-least-256-bits-long-for-hs256";

    static WireMockServer wireMock;

    @Autowired
    private WebTestClient webTestClient;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().port(8081));
        wireMock.start();

        // Stub: GET /api/v1/users/me → 200 OK
        wireMock.stubFor(get(urlEqualTo("/api/v1/users/me"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"email\":\"user@test.com\",\"role\":\"USER\"}")));

        // Stub: POST /api/v1/auth/login → 200 OK
        wireMock.stubFor(post(urlEqualTo("/api/v1/auth/login"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accessToken\":\"dummy-token\"}")));
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("USER_SERVICE_URL", () -> "http://localhost:8081");
        registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:8082");
        registry.add("app.jwt.secret", () -> SECRET);
    }

    @Test
    void shouldReturn401WhenNoJwtProvided() {
        webTestClient.get()
                .uri("/api/v1/users/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldReturn401WhenJwtIsInvalid() {
        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldForwardRequestWithValidJwt() {
        String token = generateValidToken("user@test.com", "USER");

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .expectBody()
                .jsonPath("$.email").isEqualTo("user@test.com");

        // Verify the gateway stripped the JWT and added X-User-Email header
        wireMock.verify(getRequestedFor(urlEqualTo("/api/v1/users/me"))
                .withHeader("X-User-Email", equalTo("user@test.com"))
                .withHeader("X-Gateway-Source", equalTo("api-gateway"))
                .withoutHeader("Authorization")); // JWT must NOT be forwarded
    }

    @Test
    void publicAuthRouteShouldNotRequireJwt() {
        webTestClient.post()
                .uri("/api/v1/auth/login")
                .header("Content-Type", "application/json")
                .bodyValue("{\"email\":\"u@test.com\",\"password\":\"pwd\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);
    }

    private String generateValidToken(String email, String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(email)
                .claims(Map.of("role", role))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 900_000))
                .signWith(key)
                .compact();
    }
}

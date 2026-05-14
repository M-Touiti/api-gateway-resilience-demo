package com.demo.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * Rate limiter key resolvers.
 *
 * Two strategies:
 * - Per JWT subject (authenticated user) → fair usage per user
 * - Per client IP → fallback for unauthenticated routes
 *
 * Each route in application.yml references one of these beans.
 */
@Configuration
public class RateLimiterConfig {

    /**
     * Resolves rate limit key from the JWT subject (email).
     * Extracted by the JwtAuthenticationFilter and set as a request attribute.
     * Falls back to "anonymous" if no user info is present.
     */
    @Primary
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userEmail = exchange.getAttribute("userEmail");
            return Mono.just(userEmail != null ? userEmail : "anonymous");
        };
    }

    /**
     * Resolves rate limit key from the client IP address.
     * Used for public routes (auth endpoints).
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String ip = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (ip == null || ip.isBlank()) {
                ip = exchange.getRequest().getRemoteAddress() != null
                        ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                        : "unknown";
            }
            return Mono.just(ip);
        };
    }
}

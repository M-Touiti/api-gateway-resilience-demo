package com.demo.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

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
     * Resolves rate limit key from the client IP address. Used for public routes (auth endpoints).
     *
     * Any client can set X-Forwarded-For, so it is only read when a trusted proxy adds to it:
     * - 0 hops (default): the header is ignored and the connection's address is used.
     * - N hops: the Nth entry from the right is used, which is the address seen by the outermost
     *   trusted proxy. Anything further left was supplied by the client.
     */
    @Bean
    public KeyResolver ipKeyResolver(@Value("${app.rate-limit.trusted-proxy-hops:0}") int trustedProxyHops) {
        RemoteAddressResolver resolver = trustedProxyHops > 0
                ? XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyHops)
                : new RemoteAddressResolver() { };
        return exchange -> {
            InetSocketAddress address = resolver.resolve(exchange);
            return Mono.just(address != null && address.getAddress() != null
                    ? address.getAddress().getHostAddress()
                    : "unknown");
        };
    }
}

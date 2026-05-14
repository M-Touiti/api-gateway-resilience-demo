package com.demo.gateway.filter;

import com.demo.gateway.config.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway filter that validates JWT Bearer tokens on protected routes.
 *
 * On success:
 * - Extracts user email and role from the token
 * - Forwards them as X-User-Email and X-User-Role headers to downstream services
 * - Sets userEmail as a request attribute for the rate limiter key resolver
 *
 * On failure:
 * - Returns 401 Unauthorized immediately, without forwarding the request
 *
 * Usage in application.yml:
 *   filters:
 *     - name: JwtAuthentication
 */
@Component
public class JwtAuthenticationFilter
        extends AbstractGatewayFilterFactory<JwtAuthenticationFilter.Config> {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        super(Config.class);
        this.jwtUtil = jwtUtil;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
                log.warn("Missing or invalid Authorization header for path={}",
                        exchange.getRequest().getPath());
                return unauthorized(exchange);
            }

            String token = authHeader.substring(BEARER_PREFIX.length());

            if (!jwtUtil.isValid(token)) {
                log.warn("Invalid or expired JWT token for path={}",
                        exchange.getRequest().getPath());
                return unauthorized(exchange);
            }

            String email = jwtUtil.extractEmail(token);
            String role  = jwtUtil.extractRole(token);

            log.debug("JWT valid for email={} role={} path={}", email, role,
                    exchange.getRequest().getPath());

            // Propagate user info to downstream services via headers
            ServerWebExchange mutatedExchange = exchange.mutate()
                    .request(r -> r.headers(headers -> {
                        headers.set("X-User-Email", email);
                        headers.set("X-User-Role",  role != null ? role : "USER");
                        headers.remove(HttpHeaders.AUTHORIZATION); // don't forward raw JWT downstream
                    }))
                    .build();

            // Also set as attribute for the UserKeyResolver
            mutatedExchange.getAttributes().put("userEmail", email);

            return chain.filter(mutatedExchange);
        };
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"gateway\"");
        return exchange.getResponse().setComplete();
    }

    public static class Config {
        // Extendable with per-route config (e.g., required roles)
    }
}

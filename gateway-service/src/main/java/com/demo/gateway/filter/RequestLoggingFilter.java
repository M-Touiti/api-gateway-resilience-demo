package com.demo.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Global filter applied to ALL gateway routes (no configuration needed).
 *
 * Logs for every request:
 * - Method, path, query params
 * - Downstream service (via X-User-Email if authenticated)
 * - Response status code
 * - Total latency in milliseconds
 *
 * Runs first in the filter chain (ORDER = Ordered.HIGHEST_PRECEDENCE).
 */
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = Instant.now().toEpochMilli();
        String method  = exchange.getRequest().getMethod().name();
        String path    = exchange.getRequest().getPath().value();
        String query   = exchange.getRequest().getQueryParams().toString();

        log.info("→ GATEWAY REQUEST  method={} path={} query={}", method, path, query);

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            long latencyMs = Instant.now().toEpochMilli() - startTime;
            HttpStatus status = (HttpStatus) exchange.getResponse().getStatusCode();
            String userEmail = exchange.getAttribute("userEmail");

            if (status != null && status.is5xxServerError()) {
                log.error("← GATEWAY RESPONSE method={} path={} status={} latency={}ms user={}",
                        method, path, status.value(), latencyMs, userEmail);
            } else {
                log.info("← GATEWAY RESPONSE method={} path={} status={} latency={}ms user={}",
                        method, path,
                        status != null ? status.value() : "unknown",
                        latencyMs, userEmail);
            }
        }));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

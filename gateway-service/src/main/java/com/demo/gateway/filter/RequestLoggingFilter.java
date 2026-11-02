package com.demo.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global filter applied to ALL gateway routes (no configuration needed).
 *
 * Logs for every request, at DEBUG:
 * - Method, path, query params
 * - Downstream service (via X-User-Email if authenticated)
 * - Response status code
 * - Total latency in milliseconds
 *
 * Responses with a 5xx status are always logged, at ERROR.
 *
 * This runs on event-loop threads, where console logging takes CPU from every connection. Load
 * tests showed per-request INFO lines costing about a third of the gateway's CPU at 800 req/s, so
 * enable DEBUG only for troubleshooting. Request rates and latency are available as metrics.
 *
 * Runs after ConcurrencyLimitFilter, so shed requests aren't logged.
 */
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.nanoTime();
        String method  = exchange.getRequest().getMethod().name();
        String path    = exchange.getRequest().getPath().value();

        if (log.isDebugEnabled()) {
            log.debug("→ GATEWAY REQUEST  method={} path={} query={}", method, path,
                    exchange.getRequest().getQueryParams());
        }

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            boolean serverError = status != null && status.is5xxServerError();
            if (!serverError && !log.isDebugEnabled()) {
                return;
            }

            long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
            String userEmail = exchange.getAttribute("userEmail");

            if (serverError) {
                log.error("← GATEWAY RESPONSE method={} path={} status={} latency={}ms user={}",
                        method, path, status.value(), latencyMs, userEmail);
            } else {
                log.debug("← GATEWAY RESPONSE method={} path={} status={} latency={}ms user={}",
                        method, path,
                        status != null ? status.value() : "unknown",
                        latencyMs, userEmail);
            }
        }));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}

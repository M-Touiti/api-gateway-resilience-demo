package com.demo.gateway.filter;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sheds load: once a route has too many requests in flight, new ones get an immediate 503.
 *
 * Without this, traffic beyond capacity queues inside the gateway and every caller waits seconds.
 * The limit is per route so that a hung dependency, which holds its requests until they time out,
 * uses up only its own route's allowance and other routes keep working.
 *
 * Runs first, so a shed request costs no JWT validation, Redis call or log line. Shed requests are
 * counted in the gateway.requests.shed metric. A limit of 0 or less turns shedding off.
 */
@Component
public class ConcurrencyLimitFilter implements GlobalFilter, Ordered {

    private static final byte[] BODY = ("{\"status\":503,\"error\":\"Service Unavailable\","
            + "\"message\":\"The gateway is at capacity. Please try again shortly.\"}")
            .getBytes(StandardCharsets.UTF_8);

    private final Map<String, AtomicInteger> inFlightByRoute = new ConcurrentHashMap<>();
    private final int maxConcurrentRequests;
    private final MeterRegistry meterRegistry;

    public ConcurrencyLimitFilter(
            @Value("${app.load-shedding.max-concurrent-requests-per-route:512}") int maxConcurrentRequests,
            MeterRegistry meterRegistry) {
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (maxConcurrentRequests <= 0) {
            return chain.filter(exchange);
        }
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = route != null ? route.getId() : "unknown";
        AtomicInteger inFlight = inFlightByRoute.computeIfAbsent(routeId, id -> new AtomicInteger());

        if (inFlight.incrementAndGet() > maxConcurrentRequests) {
            inFlight.decrementAndGet();
            meterRegistry.counter("gateway.requests.shed", "route", routeId).increment();
            return shed(exchange.getResponse());
        }
        return chain.filter(exchange).doFinally(signal -> inFlight.decrementAndGet());
    }

    private Mono<Void> shed(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        return response.writeWith(Mono.just(response.bufferFactory().wrap(BODY)));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

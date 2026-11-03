package com.demo.gateway.unit;

import com.demo.gateway.filter.ConcurrencyLimitFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyLimitFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    /** A downstream call that stays in flight until the sink is completed. */
    private final Sinks.Empty<Void> downstream = Sinks.empty();
    private final GatewayFilterChain hangingChain = exchange -> downstream.asMono();

    private static MockServerWebExchange exchangeFor(String routeId) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id(routeId).uri(URI.create("http://localhost")).predicate(e -> true).build());
        return exchange;
    }

    private static void start(ConcurrencyLimitFilter filter, MockServerWebExchange exchange, GatewayFilterChain chain) {
        filter.filter(exchange, chain).subscribe();
    }

    @Test
    void shouldShedRequestsAboveTheLimitWithRetryAfterAndCountThem() {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(2, registry);
        start(filter, exchangeFor("a"), hangingChain);
        start(filter, exchangeFor("a"), hangingChain);

        MockServerWebExchange third = exchangeFor("a");
        start(filter, third, hangingChain);

        assertThat(third.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(third.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(registry.counter("gateway.requests.shed", "route", "a").count()).isEqualTo(1);
    }

    @Test
    void shouldNotLetOneRouteUseAnotherRoutesAllowance() {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(1, registry);
        start(filter, exchangeFor("hung"), hangingChain);

        MockServerWebExchange other = exchangeFor("healthy");
        start(filter, other, hangingChain);

        assertThat(other.getResponse().getStatusCode()).isNull();
    }

    @Test
    void shouldReleaseCapacityWhenRequestCompletes() {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(1, registry);
        start(filter, exchangeFor("a"), hangingChain);
        downstream.tryEmitEmpty();

        MockServerWebExchange next = exchangeFor("a");
        start(filter, next, exchange -> Mono.empty());

        assertThat(next.getResponse().getStatusCode()).isNull();
    }

    @Test
    void shouldReleaseCapacityWhenClientDisconnects() {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(1, registry);
        filter.filter(exchangeFor("a"), hangingChain).subscribe().dispose();

        MockServerWebExchange next = exchangeFor("a");
        start(filter, next, exchange -> Mono.empty());

        assertThat(next.getResponse().getStatusCode()).isNull();
    }

    @Test
    void shouldNotShedWhenLimitIsDisabled() {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(0, registry);
        start(filter, exchangeFor("a"), hangingChain);

        MockServerWebExchange next = exchangeFor("a");
        start(filter, next, hangingChain);

        assertThat(next.getResponse().getStatusCode()).isNull();
    }
}

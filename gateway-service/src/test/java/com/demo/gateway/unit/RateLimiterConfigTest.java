package com.demo.gateway.unit;

import com.demo.gateway.config.RateLimiterConfig;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterConfigTest {

    private static final InetSocketAddress CLIENT = new InetSocketAddress("192.0.2.10", 40000);

    private String resolve(int trustedProxyHops, String... forwardedFor) {
        KeyResolver resolver = new RateLimiterConfig().ipKeyResolver(trustedProxyHops);
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/api/v1/auth/login")
                .remoteAddress(CLIENT);
        if (forwardedFor.length > 0) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return resolver.resolve(MockServerWebExchange.from(request.build())).block();
    }

    @Test
    void shouldIgnoreForwardedForWhenNoProxyIsTrusted() {
        assertThat(resolve(0, "10.0.0.1")).isEqualTo("192.0.2.10");
        assertThat(resolve(0, "10.0.0.2")).isEqualTo("192.0.2.10");
    }

    @Test
    void shouldUseConnectionAddressWhenHeaderIsAbsent() {
        assertThat(resolve(0)).isEqualTo("192.0.2.10");
        assertThat(resolve(1)).isEqualTo("192.0.2.10");
    }

    @Test
    void shouldUseEntryAddedByTrustedProxyAndIgnoreClientSuppliedEntries() {
        assertThat(resolve(1, "10.0.0.1, 203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(resolve(1, "10.0.0.2, 203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void shouldCountHopsFromTheRightWithTwoTrustedProxies() {
        assertThat(resolve(2, "10.0.0.1, 203.0.113.7, 198.51.100.1")).isEqualTo("203.0.113.7");
    }
}

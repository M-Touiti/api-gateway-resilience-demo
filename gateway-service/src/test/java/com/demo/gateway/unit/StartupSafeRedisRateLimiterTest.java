package com.demo.gateway.unit;

import com.demo.gateway.config.StartupSafeRedisRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StartupSafeRedisRateLimiterTest {

    private final ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
    private final ReactiveRedisConnectionFactory factory = mock(ReactiveRedisConnectionFactory.class);

    @SuppressWarnings("unchecked")
    private final StartupSafeRedisRateLimiter limiter = new StartupSafeRedisRateLimiter(
            template, mock(RedisScript.class), mock(ConfigurationService.class), factory);

    @Test
    void shouldAllowRequestsWithoutTouchingRedisBeforeFirstConnection() {
        assertThat(limiter.isAllowed("route", "user").block().isAllowed()).isTrue();

        verifyNoInteractions(template, factory);
    }

    @Test
    void shouldKeepRetryingUntilRedisAnswers() {
        ReactiveRedisConnection connection = mock(ReactiveRedisConnection.class);
        when(connection.ping()).thenReturn(Mono.just("PONG"));
        when(factory.getReactiveConnection())
                .thenThrow(new RedisConnectionFailureException("refused"))
                .thenThrow(new RedisConnectionFailureException("refused"))
                .thenReturn(connection);

        limiter.connectWhenAvailable(Duration.ZERO);

        assertThat(limiter.isConnected()).isTrue();
        verify(factory, times(3)).getReactiveConnection();
    }
}

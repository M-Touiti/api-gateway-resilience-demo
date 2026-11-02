package com.demo.gateway.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

/**
 * Makes the rate limiter's Redis client fail fast, so that failing open is cheap.
 *
 * By default Lettuce queues commands without limit while disconnected. Load tests showed the heap
 * filling during a Redis outage until the gateway stopped responding, even with a short command
 * timeout. With these options:
 * - A command sent while disconnected fails at once, and RedisRateLimiter allows the request.
 * - If Redis hangs without closing the connection, unanswered commands are capped, so they can't
 *   exhaust the heap. Commands beyond the cap fail at once too.
 */
@Configuration
public class RedisClientConfig {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(1);

    @Bean
    public LettuceClientConfigurationBuilderCustomizer failFastRedisClient(
            RedisProperties properties,
            @Value("${app.redis.max-pending-commands:1024}") int maxPendingCommands) {
        Duration connectTimeout = properties.getConnectTimeout() != null
                ? properties.getConnectTimeout()
                : DEFAULT_CONNECT_TIMEOUT;

        // This replaces the options Spring Boot would build, so it repeats the ones that matter.
        return builder -> builder.clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .requestQueueSize(maxPendingCommands)
                .timeoutOptions(TimeoutOptions.enabled())
                .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                .build());
    }

    /** Replaces the gateway's default limiter, and is still configured by redis-rate-limiter.* args. */
    @Bean
    public StartupSafeRedisRateLimiter redisRateLimiter(
            ReactiveStringRedisTemplate redisTemplate,
            @Qualifier(RedisRateLimiter.REDIS_SCRIPT_NAME) RedisScript<List<Long>> script,
            ConfigurationService configurationService,
            ReactiveRedisConnectionFactory connectionFactory) {
        return new StartupSafeRedisRateLimiter(redisTemplate, script, configurationService, connectionFactory);
    }
}

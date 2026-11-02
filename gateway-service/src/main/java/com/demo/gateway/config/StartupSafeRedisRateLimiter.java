package com.demo.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Keeps the gateway serving when it starts before Redis is available.
 *
 * The first use of the Redis connection opens it, and that step blocks. Until it succeeds, every
 * request would repeat it on an event-loop thread: load tests showed 99.97% of requests timing out.
 * So a background thread opens the connection, and requests fail open without touching Redis until
 * it has. After that, Lettuce handles reconnecting and {@link RedisClientConfig} handles failures.
 */
public class StartupSafeRedisRateLimiter extends RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(StartupSafeRedisRateLimiter.class);
    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(1);

    private final ReactiveRedisConnectionFactory connectionFactory;
    private volatile boolean connected;

    public StartupSafeRedisRateLimiter(ReactiveStringRedisTemplate redisTemplate,
                                       RedisScript<List<Long>> script,
                                       ConfigurationService configurationService,
                                       ReactiveRedisConnectionFactory connectionFactory) {
        super(redisTemplate, script, configurationService);
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        if (!connected) {
            return Mono.just(new Response(true, Map.of()));
        }
        return super.isAllowed(routeId, id);
    }

    public boolean isConnected() {
        return connected;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void connectInBackground() {
        Thread thread = new Thread(() -> connectWhenAvailable(RETRY_INTERVAL), "redis-first-connect");
        thread.setDaemon(true);
        thread.start();
    }

    /** Blocks until Redis answers, so it must not run on an event-loop thread. */
    public void connectWhenAvailable(Duration retryInterval) {
        boolean warned = false;
        while (!connected) {
            try (ReactiveRedisConnection connection = connectionFactory.getReactiveConnection()) {
                connection.ping().block(Duration.ofSeconds(2));
                connected = true;
                log.info("Connected to Redis. Rate limits are being enforced.");
            } catch (Exception e) {
                if (!warned) {
                    log.warn("Redis is unavailable, so rate limits aren't being enforced: {}", e.getMessage());
                    warned = true;
                }
                try {
                    Thread.sleep(retryInterval.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}

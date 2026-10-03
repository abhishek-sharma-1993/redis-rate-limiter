package io.github.abhisheksharma.ratelimiter.redis;

import io.github.abhisheksharma.ratelimiter.core.BucketConfig;
import io.github.abhisheksharma.ratelimiter.core.DecisionSource;
import io.github.abhisheksharma.ratelimiter.core.FallbackStrategy;
import io.github.abhisheksharma.ratelimiter.core.RateLimitDecision;
import io.github.abhisheksharma.ratelimiter.resilience.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@Testcontainers
class RedisTokenBucketRateLimiterIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;

    @BeforeAll
    static void setUp() {
        factory = factory(REDIS.getHost(), REDIS.getMappedPort(6379), Duration.ofMillis(500));
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void tearDown() {
        factory.destroy();
    }

    static LettuceConnectionFactory factory(String host, int port, Duration timeout) {
        LettuceConnectionFactory f = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(host, port),
                LettuceClientConfiguration.builder().commandTimeout(timeout).build());
        f.afterPropertiesSet();
        f.start();
        return f;
    }

    private static String key() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    void allowsUpToCapacityThenDenies() {
        RedisTokenBucketRateLimiter limiter = RedisTokenBucketRateLimiter.builder(redis).build();
        BucketConfig cfg = BucketConfig.of(5, 1);
        String k = key();

        for (int i = 4; i >= 0; i--) {
            RateLimitDecision d = limiter.tryAcquire(k, cfg);
            assertThat(d.allowed()).isTrue();
            assertThat(d.remaining()).isEqualTo(i);
            assertThat(d.source()).isEqualTo(DecisionSource.REDIS);
        }
        RateLimitDecision denied = limiter.tryAcquire(k, cfg);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMillis()).isBetween(1L, 1000L);
    }

    @Test
    void neverOverAdmitsUnderConcurrencyAcrossInstances() throws Exception {
        // 4 limiter "instances" (as if 4 pods) hammering one bucket from 64 threads
        List<RedisTokenBucketRateLimiter> pods = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            pods.add(RedisTokenBucketRateLimiter.builder(redis).denyCache(null).build());
        }
        BucketConfig cfg = BucketConfig.of(500, 0.0001); // effectively no refill during the test
        String k = key();
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(64);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 64; t++) {
            RedisTokenBucketRateLimiter pod = pods.get(t % 4);
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 50; i++) {
                    if (pod.tryAcquire(k, cfg).allowed()) allowed.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertThat(allowed.get()).isEqualTo(500); // 3200 attempts, exactly capacity admitted
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        RedisTokenBucketRateLimiter limiter = RedisTokenBucketRateLimiter.builder(redis).denyCache(null).build();
        BucketConfig cfg = BucketConfig.of(1, 10); // 1 token every 100ms
        String k = key();
        assertThat(limiter.tryAcquire(k, cfg).allowed()).isTrue();
        assertThat(limiter.tryAcquire(k, cfg).allowed()).isFalse();
        Thread.sleep(150);
        assertThat(limiter.tryAcquire(k, cfg).allowed()).isTrue();
    }

    @Test
    void denyCacheAnswersWithoutRedis() {
        RedisTokenBucketRateLimiter limiter = RedisTokenBucketRateLimiter.builder(redis).build();
        BucketConfig cfg = BucketConfig.of(1, 0.01);
        String k = key();
        limiter.tryAcquire(k, cfg);
        assertThat(limiter.tryAcquire(k, cfg).source()).isEqualTo(DecisionSource.REDIS);
        assertThat(limiter.tryAcquire(k, cfg).source()).isEqualTo(DecisionSource.DENY_CACHE);
    }

    @Test
    void asyncApiMatchesSyncApi() throws Exception {
        RedisTokenBucketRateLimiter limiter = RedisTokenBucketRateLimiter.builder(redis)
                .reactiveRedis(new ReactiveStringRedisTemplate(factory))
                .asyncTimeout(Duration.ofSeconds(1))
                .build();
        BucketConfig cfg = BucketConfig.of(2, 0.01);
        String k = key();
        assertThat(limiter.tryAcquireAsync(k, cfg).get().allowed()).isTrue();
        assertThat(limiter.tryAcquireAsync(k, cfg).get().allowed()).isTrue();
        RateLimitDecision third = limiter.tryAcquireAsync(k, cfg).get();
        assertThat(third.allowed()).isFalse();
        assertThat(third.source()).isEqualTo(DecisionSource.REDIS);
    }

    @Test
    void redisDownNeverThrowsAndFallsBackLocally() {
        // nothing listens on port 1
        LettuceConnectionFactory dead = factory("127.0.0.1", 1, Duration.ofMillis(50));
        RedisTokenBucketRateLimiter limiter = RedisTokenBucketRateLimiter.builder(new StringRedisTemplate(dead))
                .reactiveRedis(new ReactiveStringRedisTemplate(dead))
                .fallbackStrategy(FallbackStrategy.LOCAL)
                .circuitBreaker(new CircuitBreaker(2, Duration.ofSeconds(30)))
                .build();
        BucketConfig cfg = BucketConfig.of(3, 0.01);

        assertThatCode(() -> {
            for (int i = 0; i < 10; i++) {
                RateLimitDecision d = limiter.tryAcquire("k", cfg);
                assertThat(d.source()).isEqualTo(DecisionSource.LOCAL_FALLBACK);
                limiter.tryAcquireAsync("k2", cfg).get();
            }
        }).doesNotThrowAnyException();

        assertThat(limiter.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        dead.destroy();
    }
}

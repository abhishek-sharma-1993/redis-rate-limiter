package io.github.abhisheksharma.ratelimiter.redis;

import io.github.abhisheksharma.ratelimiter.core.BucketConfig;
import io.github.abhisheksharma.ratelimiter.core.DecisionSource;
import io.github.abhisheksharma.ratelimiter.core.FallbackStrategy;
import io.github.abhisheksharma.ratelimiter.core.RateLimitDecision;
import io.github.abhisheksharma.ratelimiter.core.RateLimiter;
import io.github.abhisheksharma.ratelimiter.local.LocalRateLimiter;
import io.github.abhisheksharma.ratelimiter.metrics.RateLimiterMetrics;
import io.github.abhisheksharma.ratelimiter.resilience.CircuitBreaker;
import io.github.abhisheksharma.ratelimiter.resilience.DenyCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Distributed token bucket backed by a single atomic Lua script per call.
 *
 * <h2>Request path</h2>
 * <ol>
 *   <li>Deny cache hit? answer locally (0 network hops).</li>
 *   <li>Circuit open? answer from fallback (0 network hops).</li>
 *   <li>EVALSHA token_bucket.lua (1 round trip, O(1) work in Redis).</li>
 *   <li>Any exception or timeout: record failure, answer from fallback.</li>
 * </ol>
 *
 * <p>Never throws. The only things allowed to escape are {@link VirtualMachineError}s
 * (OutOfMemoryError etc.), which no library should swallow.
 */
public final class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    static final RedisScript<List<Object>> SCRIPT =
            (RedisScript) RedisScript.of(new ClassPathResource("ratelimiter/token_bucket.lua"), List.class);

    private final StringRedisTemplate redis;
    private final ReactiveStringRedisTemplate reactiveRedis; // nullable
    private final String keyPrefix;
    private final FallbackStrategy fallbackStrategy;
    private final RateLimiter localFallback;
    private final CircuitBreaker circuitBreaker;
    private final DenyCache denyCache;                         // nullable = disabled
    private final RateLimiterMetrics metrics;
    private final Duration asyncTimeout;

    private RedisTokenBucketRateLimiter(Builder b) {
        this.redis = Objects.requireNonNull(b.redis, "StringRedisTemplate is required");
        this.reactiveRedis = b.reactiveRedis;
        this.keyPrefix = b.keyPrefix;
        this.fallbackStrategy = b.fallbackStrategy;
        this.localFallback = b.localFallback != null ? b.localFallback
                : new LocalRateLimiter(100_000, Duration.ofMinutes(10), 1.0);
        this.circuitBreaker = b.circuitBreaker != null ? b.circuitBreaker
                : new CircuitBreaker(5, Duration.ofSeconds(5));
        this.denyCache = b.denyCache;
        this.metrics = b.metrics != null ? b.metrics : new RateLimiterMetrics(new SimpleMeterRegistry());
        this.asyncTimeout = b.asyncTimeout;
        this.metrics.circuitStateGauge(() -> circuitBreaker.state().ordinal() == 0 ? 0
                : circuitBreaker.state() == CircuitBreaker.State.HALF_OPEN ? 1 : 2);
    }

    public static Builder builder(StringRedisTemplate redis) {
        return new Builder(redis);
    }

    // ------------------------------------------------------------------ sync

    @Override
    public RateLimitDecision tryAcquire(String key, BucketConfig config, int permits) {
        final int p = Math.max(1, permits);
        try {
            String redisKey = redisKey(key);

            RateLimitDecision cached = denyCacheCheck(redisKey, p, config);
            if (cached != null) {
                return recorded(cached);
            }
            if (!circuitBreaker.allowRequest()) {
                return fallback(key, config, p);
            }

            long start = System.nanoTime();
            List<Object> raw = redis.execute(SCRIPT, List.of(redisKey), args(config, p));
            metrics.redisLatency(System.nanoTime() - start);
            circuitBreaker.onSuccess();

            return onRedisResult(redisKey, p, config, raw);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            return onRedisFailure(key, config, p, t);
        }
    }

    // ----------------------------------------------------------------- async

    @Override
    public CompletableFuture<RateLimitDecision> tryAcquireAsync(String key, BucketConfig config, int permits) {
        final int p = Math.max(1, permits);
        try {
            if (reactiveRedis == null) {
                return CompletableFuture.completedFuture(tryAcquire(key, config, p));
            }
            String redisKey = redisKey(key);

            RateLimitDecision cached = denyCacheCheck(redisKey, p, config);
            if (cached != null) {
                return CompletableFuture.completedFuture(recorded(cached));
            }
            if (!circuitBreaker.allowRequest()) {
                return CompletableFuture.completedFuture(fallback(key, config, p));
            }

            long start = System.nanoTime();
            @SuppressWarnings({"unchecked", "rawtypes"})
            Flux<Object> flux = (Flux) reactiveRedis.execute(SCRIPT, List.of(redisKey), List.of(args(config, p)));

            return flux.collectList()
                    .timeout(asyncTimeout)
                    .toFuture()
                    .handle((items, err) -> {
                        if (err != null) {
                            return onRedisFailure(key, config, p, err);
                        }
                        try {
                            metrics.redisLatency(System.nanoTime() - start);
                            circuitBreaker.onSuccess();
                            // Depending on driver version, a multi-bulk reply arrives either as one List or as N elements.
                            List<?> raw = items.size() == 1 && items.get(0) instanceof List<?> l ? l : items;
                            return onRedisResult(redisKey, p, config, raw);
                        } catch (Throwable t) {
                            return onRedisFailure(key, config, p, t);
                        }
                    });
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(onRedisFailure(key, config, p, t));
        }
    }

    // --------------------------------------------------------------- helpers

    private RateLimitDecision onRedisResult(String redisKey, int permits, BucketConfig config, List<?> raw) {
        RateLimitDecision d = parse(raw, config.capacity());
        if (denyCache != null) {
            denyCache.record(redisKey, permits, d);
        }
        return recorded(d);
    }

    private RateLimitDecision onRedisFailure(String key, BucketConfig config, int permits, Throwable t) {
        circuitBreaker.onFailure();
        metrics.redisError();
        if (log.isDebugEnabled()) {
            log.debug("Rate limiter falling back ({}) for key={}: {}", fallbackStrategy, key, t.toString());
        }
        return fallback(key, config, permits);
    }

    private RateLimitDecision denyCacheCheck(String redisKey, int permits, BucketConfig config) {
        return denyCache == null ? null : denyCache.check(redisKey, permits, config.capacity());
    }

    /** Fallback must itself be infallible: last line of defence is fail-open. */
    private RateLimitDecision fallback(String key, BucketConfig config, int permits) {
        long limit = config == null ? 0 : config.capacity();
        RateLimitDecision d;
        try {
            d = switch (fallbackStrategy) {
                case LOCAL -> localFallback.tryAcquire(key, config, permits);
                case ALLOW -> RateLimitDecision.allow(limit, limit, DecisionSource.FAIL_OPEN);
                case DENY -> RateLimitDecision.deny(1000, limit, DecisionSource.FAIL_CLOSED);
            };
        } catch (Throwable t) {
            d = RateLimitDecision.allow(0, limit, DecisionSource.FAIL_OPEN);
        }
        return recorded(d);
    }

    private RateLimitDecision recorded(RateLimitDecision d) {
        try {
            metrics.record(d);
        } catch (RuntimeException ignored) {
            // metrics must never break the request path
        }
        return d;
    }

    private String redisKey(String key) {
        // One key per bucket => cluster slot is derived from the whole key, buckets spread evenly.
        return keyPrefix + ':' + (key == null ? "null" : key);
    }

    private static String[] args(BucketConfig config, int permits) {
        return new String[] {
                Long.toString(config.capacity()),
                Double.toString(config.refillTokensPerSecond()),
                Integer.toString(permits)
        };
    }

    static RateLimitDecision parse(List<?> raw, long limit) {
        if (raw == null || raw.size() < 3) {
            throw new IllegalStateException("Unexpected Lua reply: " + raw);
        }
        boolean allowed = "1".equals(String.valueOf(raw.get(0)));
        long remaining = Long.parseLong(String.valueOf(raw.get(1)));
        long retryAfter = Long.parseLong(String.valueOf(raw.get(2)));
        return new RateLimitDecision(allowed, remaining, retryAfter, limit, DecisionSource.REDIS);
    }

    public CircuitBreaker.State circuitState() {
        return circuitBreaker.state();
    }

    // --------------------------------------------------------------- builder

    public static final class Builder {
        private final StringRedisTemplate redis;
        private ReactiveStringRedisTemplate reactiveRedis;
        private String keyPrefix = "rl";
        private FallbackStrategy fallbackStrategy = FallbackStrategy.LOCAL;
        private RateLimiter localFallback;
        private CircuitBreaker circuitBreaker;
        private DenyCache denyCache = new DenyCache(100_000, Duration.ofMinutes(1));
        private RateLimiterMetrics metrics;
        private Duration asyncTimeout = Duration.ofMillis(50);

        private Builder(StringRedisTemplate redis) { this.redis = redis; }

        public Builder reactiveRedis(ReactiveStringRedisTemplate t) { this.reactiveRedis = t; return this; }
        public Builder keyPrefix(String p) { this.keyPrefix = p; return this; }
        public Builder fallbackStrategy(FallbackStrategy s) { this.fallbackStrategy = s; return this; }
        public Builder localFallback(RateLimiter l) { this.localFallback = l; return this; }
        public Builder circuitBreaker(CircuitBreaker cb) { this.circuitBreaker = cb; return this; }
        /** Pass {@code null} to disable the deny cache. */
        public Builder denyCache(DenyCache c) { this.denyCache = c; return this; }
        public Builder metrics(RateLimiterMetrics m) { this.metrics = m; return this; }
        public Builder asyncTimeout(Duration d) { this.asyncTimeout = d; return this; }

        public RedisTokenBucketRateLimiter build() {
            return new RedisTokenBucketRateLimiter(this);
        }
    }
}

package io.github.abhisheksharma.ratelimiter.local;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.abhisheksharma.ratelimiter.core.BucketConfig;
import io.github.abhisheksharma.ratelimiter.core.DecisionSource;
import io.github.abhisheksharma.ratelimiter.core.RateLimitDecision;
import io.github.abhisheksharma.ratelimiter.core.RateLimiter;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Per-instance limiter used as the degraded mode when Redis is unavailable.
 *
 * <p>Each instance enforces {@code ratio × limit}. Set {@code ratio ≈ 1 / instanceCount} so the
 * fleet as a whole stays close to the global limit during a Redis outage. Bounded memory:
 * at most {@code maxKeys} buckets, idle ones are evicted.
 */
public final class LocalRateLimiter implements RateLimiter {

    private final Cache<String, LocalTokenBucket> buckets;
    private final double ratio;

    public LocalRateLimiter(long maxKeys, Duration idleExpiry, double ratio) {
        this.buckets = Caffeine.newBuilder().maximumSize(maxKeys).expireAfterAccess(idleExpiry).build();
        this.ratio = ratio <= 0 ? 1.0 : Math.min(1.0, ratio);
    }

    @Override
    public RateLimitDecision tryAcquire(String key, BucketConfig config, int permits) {
        try {
            BucketConfig effective = config.scaled(ratio);
            String bucketKey = key + '|' + effective.capacity() + '|' + effective.refillTokensPerSecond();
            long now = System.nanoTime();
            LocalTokenBucket bucket = buckets.get(bucketKey, k -> new LocalTokenBucket(effective, now));
            return bucket.tryConsume(Math.max(1, permits), System.nanoTime());
        } catch (RuntimeException e) {
            long limit = config == null ? 0 : config.capacity();
            return RateLimitDecision.allow(0, limit, DecisionSource.FAIL_OPEN);
        }
    }

    @Override
    public CompletableFuture<RateLimitDecision> tryAcquireAsync(String key, BucketConfig config, int permits) {
        return CompletableFuture.completedFuture(tryAcquire(key, config, permits));
    }
}

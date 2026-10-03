package io.github.abhisheksharma.ratelimiter.core;

import java.util.concurrent.CompletableFuture;

/** Allows everything. Used when {@code ratelimiter.enabled=false}. */
public final class NoopRateLimiter implements RateLimiter {

    @Override
    public RateLimitDecision tryAcquire(String key, BucketConfig config, int permits) {
        long limit = config == null ? 0 : config.capacity();
        return RateLimitDecision.allow(limit, limit, DecisionSource.DISABLED);
    }

    @Override
    public CompletableFuture<RateLimitDecision> tryAcquireAsync(String key, BucketConfig config, int permits) {
        return CompletableFuture.completedFuture(tryAcquire(key, config, permits));
    }
}

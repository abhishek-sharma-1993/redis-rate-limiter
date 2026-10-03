package io.github.abhisheksharma.ratelimiter.core;

import java.util.concurrent.CompletableFuture;

/**
 * Entry point for rate limiting.
 *
 * <p><b>Contract:</b> implementations never throw and never return {@code null}. If the backing
 * store is slow or unavailable, the configured {@link FallbackStrategy} decides the outcome and
 * the returned {@link RateLimitDecision#source()} says how the decision was made.
 */
public interface RateLimiter {

    /** Try to take {@code permits} tokens from the bucket identified by {@code key}. */
    RateLimitDecision tryAcquire(String key, BucketConfig config, int permits);

    default RateLimitDecision tryAcquire(String key, BucketConfig config) {
        return tryAcquire(key, config, 1);
    }

    /** Non-blocking variant. The future always completes normally. */
    CompletableFuture<RateLimitDecision> tryAcquireAsync(String key, BucketConfig config, int permits);

    default CompletableFuture<RateLimitDecision> tryAcquireAsync(String key, BucketConfig config) {
        return tryAcquireAsync(key, config, 1);
    }
}

package io.github.abhisheksharma.ratelimiter.local;

import io.github.abhisheksharma.ratelimiter.core.BucketConfig;
import io.github.abhisheksharma.ratelimiter.core.DecisionSource;
import io.github.abhisheksharma.ratelimiter.core.RateLimitDecision;

/** In-memory token bucket. Same algorithm as the Lua script, guarded by the bucket's monitor. */
final class LocalTokenBucket {

    private final long capacity;
    private final double tokensPerNano;
    private double tokens;
    private long lastRefillNanos;

    LocalTokenBucket(BucketConfig config, long nowNanos) {
        this.capacity = config.capacity();
        this.tokensPerNano = config.refillTokensPerSecond() / 1_000_000_000.0;
        this.tokens = capacity;
        this.lastRefillNanos = nowNanos;
    }

    synchronized RateLimitDecision tryConsume(int permits, long nowNanos) {
        long elapsed = Math.max(0, nowNanos - lastRefillNanos);
        tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
        lastRefillNanos = nowNanos;

        if (permits <= tokens) {
            tokens -= permits;
            return RateLimitDecision.allow((long) tokens, capacity, DecisionSource.LOCAL_FALLBACK);
        }
        if (permits > capacity) {
            return RateLimitDecision.deny(-1, capacity, DecisionSource.LOCAL_FALLBACK);
        }
        long retryMs = (long) Math.ceil((permits - tokens) / tokensPerNano / 1_000_000.0);
        return RateLimitDecision.deny(retryMs, capacity, DecisionSource.LOCAL_FALLBACK);
    }
}

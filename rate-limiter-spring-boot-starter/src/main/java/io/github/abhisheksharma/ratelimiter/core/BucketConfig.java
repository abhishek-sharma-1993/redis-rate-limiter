package io.github.abhisheksharma.ratelimiter.core;

/**
 * Token bucket shape.
 *
 * @param capacity              maximum tokens in the bucket (burst size)
 * @param refillTokensPerSecond steady-state rate; may be fractional (e.g. 0.5 = 1 token / 2s)
 */
public record BucketConfig(long capacity, double refillTokensPerSecond) {

    public BucketConfig {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, was " + capacity);
        }
        if (!(refillTokensPerSecond > 0) || Double.isInfinite(refillTokensPerSecond)) {
            throw new IllegalArgumentException("refillTokensPerSecond must be a finite value > 0, was " + refillTokensPerSecond);
        }
    }

    public static BucketConfig of(long capacity, double refillTokensPerSecond) {
        return new BucketConfig(capacity, refillTokensPerSecond);
    }

    /** {@code n} requests per second with a burst of {@code n}. */
    public static BucketConfig perSecond(long n) {
        return new BucketConfig(n, n);
    }

    /** {@code n} requests per minute with a burst of {@code n}. */
    public static BucketConfig perMinute(long n) {
        return new BucketConfig(n, n / 60.0);
    }

    /** Same bucket scaled by {@code ratio} (used for per-instance local fallback). */
    public BucketConfig scaled(double ratio) {
        if (ratio >= 1.0) {
            return this;
        }
        return new BucketConfig(Math.max(1, Math.round(capacity * ratio)), Math.max(1e-6, refillTokensPerSecond * ratio));
    }
}

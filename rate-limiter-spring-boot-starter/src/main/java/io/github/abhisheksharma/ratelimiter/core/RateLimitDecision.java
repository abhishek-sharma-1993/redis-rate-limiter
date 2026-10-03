package io.github.abhisheksharma.ratelimiter.core;

/**
 * Result of a rate limit check.
 *
 * @param allowed          whether the request may proceed
 * @param remaining        whole tokens left after this call (best effort for fallback sources)
 * @param retryAfterMillis when denied: ms until enough tokens exist; -1 if the request can never
 *                         succeed (permits > capacity); 0 when allowed
 * @param limit            bucket capacity, handy for X-RateLimit-Limit headers
 * @param source           how the decision was made
 */
public record RateLimitDecision(boolean allowed, long remaining, long retryAfterMillis, long limit, DecisionSource source) {

    public static RateLimitDecision allow(long remaining, long limit, DecisionSource source) {
        return new RateLimitDecision(true, remaining, 0, limit, source);
    }

    public static RateLimitDecision deny(long retryAfterMillis, long limit, DecisionSource source) {
        return new RateLimitDecision(false, 0, retryAfterMillis, limit, source);
    }

    /** True when the decision came from Redis or the deny cache (i.e. globally consistent). */
    public boolean authoritative() {
        return source == DecisionSource.REDIS || source == DecisionSource.DENY_CACHE;
    }
}

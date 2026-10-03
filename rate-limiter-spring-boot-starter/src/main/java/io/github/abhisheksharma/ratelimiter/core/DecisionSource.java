package io.github.abhisheksharma.ratelimiter.core;

/** Where a {@link RateLimitDecision} came from. Exposed as a metric tag. */
public enum DecisionSource {
    /** Authoritative, cluster-wide decision from Redis. */
    REDIS,
    /** Short-circuited locally: Redis recently said "denied until T" and T has not passed. */
    DENY_CACHE,
    /** Redis unavailable: decided by a per-instance in-memory bucket. */
    LOCAL_FALLBACK,
    /** Redis unavailable: allowed by policy. */
    FAIL_OPEN,
    /** Redis unavailable: denied by policy. */
    FAIL_CLOSED,
    /** Rate limiting disabled by configuration. */
    DISABLED
}

package io.github.abhisheksharma.ratelimiter.core;

/** What to do when Redis is unreachable, slow, or the circuit breaker is open. */
public enum FallbackStrategy {
    /** Enforce limits with a per-instance in-memory bucket (recommended). */
    LOCAL,
    /** Allow everything. Availability over protection. */
    ALLOW,
    /** Deny everything. Protection over availability. */
    DENY
}

package io.github.abhisheksharma.ratelimiter.resilience;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.abhisheksharma.ratelimiter.core.DecisionSource;
import io.github.abhisheksharma.ratelimiter.core.RateLimitDecision;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Local "negative cache" of denials.
 *
 * <p>When Redis says <i>"bucket K cannot serve P permits for another R ms"</i>, that is a hard
 * lower bound: tokens refill at a fixed rate and other instances can only consume them. So any
 * request for &ge; P permits on K during the next R ms is guaranteed to be denied, and we can
 * answer locally without a network hop. This never over-admits, and it shields Redis from
 * abusive clients hammering a hot key.
 */
public final class DenyCache {

    private record Entry(long denyUntilNanos, int permits) { }

    private final Cache<String, Entry> cache;

    public DenyCache(long maxKeys, Duration maxTtl) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxKeys)
                .expireAfterWrite(maxTtl)
                .build();
    }

    /** @return a denial if one is known to still hold, otherwise {@code null}. */
    public RateLimitDecision check(String key, int permits, long limit) {
        Entry e = cache.getIfPresent(key);
        if (e == null) {
            return null;
        }
        long remainingNanos = e.denyUntilNanos() - System.nanoTime();
        if (remainingNanos <= 0) {
            cache.invalidate(key);
            return null;
        }
        if (permits < e.permits()) {
            return null; // a smaller request might still fit
        }
        long retryMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1;
        return RateLimitDecision.deny(retryMs, limit, DecisionSource.DENY_CACHE);
    }

    public void record(String key, int permits, RateLimitDecision d) {
        if (!d.allowed() && d.retryAfterMillis() > 0) {
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(d.retryAfterMillis());
            cache.put(key, new Entry(until, permits));
        }
    }
}

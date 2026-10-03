package io.github.abhisheksharma.ratelimiter.local;

import io.github.abhisheksharma.ratelimiter.core.BucketConfig;
import io.github.abhisheksharma.ratelimiter.core.DecisionSource;
import io.github.abhisheksharma.ratelimiter.core.RateLimitDecision;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LocalRateLimiterTest {

    @Test
    void bucketRefillsAtConfiguredRate() {
        LocalTokenBucket bucket = new LocalTokenBucket(BucketConfig.of(2, 1), 0);
        assertThat(bucket.tryConsume(1, 0).allowed()).isTrue();
        assertThat(bucket.tryConsume(1, 0).allowed()).isTrue();

        RateLimitDecision denied = bucket.tryConsume(1, 0);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMillis()).isBetween(999L, 1001L);

        // 500ms later: half a token -> still denied, ~500ms to wait
        RateLimitDecision half = bucket.tryConsume(1, 500_000_000L);
        assertThat(half.allowed()).isFalse();
        assertThat(half.retryAfterMillis()).isBetween(499L, 501L);

        assertThat(bucket.tryConsume(1, 1_000_000_000L).allowed()).isTrue();
    }

    @Test
    void neverExceedsCapacityAfterLongIdle() {
        LocalTokenBucket bucket = new LocalTokenBucket(BucketConfig.of(3, 100), 0);
        long later = Duration.ofHours(1).toNanos();
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (bucket.tryConsume(1, later).allowed()) allowed++;
        }
        assertThat(allowed).isEqualTo(3);
    }

    @Test
    void permitsLargerThanCapacityCanNeverSucceed() {
        LocalTokenBucket bucket = new LocalTokenBucket(BucketConfig.of(5, 1), 0);
        assertThat(bucket.tryConsume(6, 0).retryAfterMillis()).isEqualTo(-1);
    }

    @Test
    void ratioScalesThePerInstanceLimit() {
        LocalRateLimiter limiter = new LocalRateLimiter(1000, Duration.ofMinutes(1), 0.25);
        BucketConfig global = BucketConfig.of(100, 0.0001);
        long allowed = 0;
        for (int i = 0; i < 200; i++) {
            RateLimitDecision d = limiter.tryAcquire("k", global, 1);
            assertThat(d.source()).isEqualTo(DecisionSource.LOCAL_FALLBACK);
            if (d.allowed()) allowed++;
        }
        assertThat(allowed).isEqualTo(25);
    }
}

package io.github.abhisheksharma.ratelimiter.resilience;

import io.github.abhisheksharma.ratelimiter.core.DecisionSource;
import io.github.abhisheksharma.ratelimiter.core.RateLimitDecision;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    @Test
    void opensAfterThresholdAndRecoversThroughSingleProbe() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(3, Duration.ofMillis(50));
        cb.onFailure();
        cb.onFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        cb.onFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse();

        Thread.sleep(60);
        assertThat(cb.allowRequest()).isTrue();   // the probe
        assertThat(cb.allowRequest()).isFalse();  // everyone else waits
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        cb.onSuccess();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.allowRequest()).isTrue();
    }

    @Test
    void failedProbeReopens() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofMillis(20));
        cb.onFailure();
        Thread.sleep(30);
        assertThat(cb.allowRequest()).isTrue();
        cb.onFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void denyCacheOnlyShortCircuitsEqualOrLargerRequests() {
        DenyCache cache = new DenyCache(100, Duration.ofMinutes(1));
        cache.record("k", 5, RateLimitDecision.deny(10_000, 10, DecisionSource.REDIS));

        assertThat(cache.check("k", 5, 10)).isNotNull();
        assertThat(cache.check("k", 7, 10)).isNotNull();
        assertThat(cache.check("k", 1, 10)).isNull();      // smaller request may fit
        assertThat(cache.check("other", 5, 10)).isNull();
    }
}

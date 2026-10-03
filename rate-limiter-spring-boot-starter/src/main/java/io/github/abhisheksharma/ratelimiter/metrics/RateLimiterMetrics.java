package io.github.abhisheksharma.ratelimiter.metrics;

import io.github.abhisheksharma.ratelimiter.core.DecisionSource;
import io.github.abhisheksharma.ratelimiter.core.RateLimitDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Micrometer instrumentation. Meters are pre-registered so the hot path is a map lookup + increment.
 *
 * <ul>
 *   <li>{@code ratelimiter.decisions{result=allowed|denied, source=...}}</li>
 *   <li>{@code ratelimiter.redis.latency} (with p50/p99)</li>
 *   <li>{@code ratelimiter.redis.errors}</li>
 *   <li>{@code ratelimiter.circuit.state} (0=closed, 1=half-open, 2=open)</li>
 * </ul>
 */
public final class RateLimiterMetrics {

    private final MeterRegistry registry;
    private final Map<DecisionSource, Counter> allowed = new EnumMap<>(DecisionSource.class);
    private final Map<DecisionSource, Counter> denied = new EnumMap<>(DecisionSource.class);
    private final Timer redisLatency;
    private final Counter redisErrors;

    public RateLimiterMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (DecisionSource s : DecisionSource.values()) {
            allowed.put(s, Counter.builder("ratelimiter.decisions").tag("result", "allowed").tag("source", s.name()).register(registry));
            denied.put(s, Counter.builder("ratelimiter.decisions").tag("result", "denied").tag("source", s.name()).register(registry));
        }
        this.redisLatency = Timer.builder("ratelimiter.redis.latency")
                .publishPercentiles(0.5, 0.99)
                .register(registry);
        this.redisErrors = Counter.builder("ratelimiter.redis.errors").register(registry);
    }

    public void record(RateLimitDecision d) {
        (d.allowed() ? allowed : denied).get(d.source()).increment();
    }

    public void redisLatency(long nanos) {
        redisLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void redisError() {
        redisErrors.increment();
    }

    public void circuitStateGauge(Supplier<Number> state) {
        // Gauge.builder(name, Supplier) keeps a strong reference, so the lambda is not GC'd
        Gauge.builder("ratelimiter.circuit.state", state).register(registry);
    }
}

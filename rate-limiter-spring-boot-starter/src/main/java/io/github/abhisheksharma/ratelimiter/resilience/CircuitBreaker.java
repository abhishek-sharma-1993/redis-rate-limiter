package io.github.abhisheksharma.ratelimiter.resilience;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal lock-free circuit breaker guarding Redis calls.
 *
 * <pre>
 * CLOSED --(N consecutive failures)--> OPEN --(openDuration elapsed)--> HALF_OPEN
 * HALF_OPEN --(probe ok)--> CLOSED          HALF_OPEN --(probe fails)--> OPEN
 * </pre>
 *
 * While OPEN, callers skip Redis entirely, so a dead Redis costs ~0 latency instead of a
 * command timeout on every request. Only one probe is let through in HALF_OPEN.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openNanos;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openedAtNanos;

    public CircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openNanos = openDuration.toNanos();
    }

    /** @return true if the caller may hit Redis now. */
    public boolean allowRequest() {
        State s = state.get();
        if (s == State.CLOSED) {
            return true;
        }
        if (s == State.OPEN && System.nanoTime() - openedAtNanos >= openNanos) {
            // exactly one thread wins the CAS and becomes the probe
            return state.compareAndSet(State.OPEN, State.HALF_OPEN);
        }
        return false;
    }

    public void onSuccess() {
        consecutiveFailures.set(0);
        if (state.get() != State.CLOSED) {
            state.set(State.CLOSED);
        }
    }

    public void onFailure() {
        if (state.get() == State.HALF_OPEN || consecutiveFailures.incrementAndGet() >= failureThreshold) {
            trip();
        }
    }

    private void trip() {
        openedAtNanos = System.nanoTime();
        state.set(State.OPEN);
        consecutiveFailures.set(0);
    }

    public State state() {
        return state.get();
    }
}

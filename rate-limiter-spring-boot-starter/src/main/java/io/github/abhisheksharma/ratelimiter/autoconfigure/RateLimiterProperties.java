package io.github.abhisheksharma.ratelimiter.autoconfigure;

import io.github.abhisheksharma.ratelimiter.core.FallbackStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** All settings live under {@code ratelimiter.*}. */
@ConfigurationProperties(prefix = "ratelimiter")
public class RateLimiterProperties {

    /** Master switch. When false a no-op limiter that allows everything is registered. */
    private boolean enabled = true;

    /** Prefix for every Redis key, e.g. {@code rl:orders-service:...}. */
    private String keyPrefix = "rl";

    /** Behaviour when Redis is down/slow or the circuit is open. */
    private FallbackStrategy fallback = FallbackStrategy.LOCAL;

    /** Timeout for the async API. The sync API uses spring.data.redis.timeout. */
    private Duration asyncTimeout = Duration.ofMillis(50);

    private final Local local = new Local();
    private final DenyCacheProps denyCache = new DenyCacheProps();
    private final CircuitBreakerProps circuitBreaker = new CircuitBreakerProps();
    private final Web web = new Web();

    public static class Local {
        /** Fraction of the global limit each instance enforces while in LOCAL fallback (~1/instances). */
        private double ratio = 1.0;
        /** Max buckets kept in memory. */
        private long maxKeys = 100_000;
        /** Evict local buckets not used for this long. */
        private Duration idleExpiry = Duration.ofMinutes(10);

        public double getRatio() { return ratio; }
        public void setRatio(double ratio) { this.ratio = ratio; }
        public long getMaxKeys() { return maxKeys; }
        public void setMaxKeys(long maxKeys) { this.maxKeys = maxKeys; }
        public Duration getIdleExpiry() { return idleExpiry; }
        public void setIdleExpiry(Duration idleExpiry) { this.idleExpiry = idleExpiry; }
    }

    public static class DenyCacheProps {
        /** Short-circuit requests that Redis already proved will be denied. */
        private boolean enabled = true;
        private long maxKeys = 100_000;
        /** Upper bound on how long a single denial is cached. */
        private Duration maxTtl = Duration.ofMinutes(1);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getMaxKeys() { return maxKeys; }
        public void setMaxKeys(long maxKeys) { this.maxKeys = maxKeys; }
        public Duration getMaxTtl() { return maxTtl; }
        public void setMaxTtl(Duration maxTtl) { this.maxTtl = maxTtl; }
    }

    public static class CircuitBreakerProps {
        /** Consecutive Redis failures before the circuit opens. */
        private int failureThreshold = 5;
        /** How long to stay open before sending a probe. */
        private Duration openDuration = Duration.ofSeconds(5);

        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public Duration getOpenDuration() { return openDuration; }
        public void setOpenDuration(Duration openDuration) { this.openDuration = openDuration; }
    }

    public static class Web {
        /** Register the MVC interceptor that enforces @RateLimit on controllers. */
        private boolean enabled = true;
        /** Emit X-RateLimit-* headers. */
        private boolean headers = true;
        /** Trust X-Forwarded-For for #ip. Only enable behind a proxy you control. */
        private boolean trustForwardedFor = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isHeaders() { return headers; }
        public void setHeaders(boolean headers) { this.headers = headers; }
        public boolean isTrustForwardedFor() { return trustForwardedFor; }
        public void setTrustForwardedFor(boolean trustForwardedFor) { this.trustForwardedFor = trustForwardedFor; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public FallbackStrategy getFallback() { return fallback; }
    public void setFallback(FallbackStrategy fallback) { this.fallback = fallback; }
    public Duration getAsyncTimeout() { return asyncTimeout; }
    public void setAsyncTimeout(Duration asyncTimeout) { this.asyncTimeout = asyncTimeout; }
    public Local getLocal() { return local; }
    public DenyCacheProps getDenyCache() { return denyCache; }
    public CircuitBreakerProps getCircuitBreaker() { return circuitBreaker; }
    public Web getWeb() { return web; }
}

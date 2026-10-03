package io.github.abhisheksharma.sample;

import io.github.abhisheksharma.ratelimiter.core.BucketConfig;
import io.github.abhisheksharma.ratelimiter.core.RateLimitDecision;
import io.github.abhisheksharma.ratelimiter.core.RateLimiter;
import io.github.abhisheksharma.ratelimiter.web.RateLimit;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DemoController {

    private static final BucketConfig EXPORT_LIMIT = BucketConfig.perMinute(30);

    private final RateLimiter rateLimiter;

    public DemoController(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /** 1) Declarative, per client IP: burst 10, then 5 req/s. */
    @RateLimit(capacity = 10, refillPerSecond = 5)
    @GetMapping("/api/hello")
    public Map<String, String> hello() {
        return Map.of("message", "hello");
    }

    /** 2) Declarative, per path variable (e.g. per merchant). */
    @RateLimit(name = "merchant-orders", capacity = 100, refillPerSecond = 50, key = "#pathVars['merchantId']")
    @GetMapping("/api/merchants/{merchantId}/orders")
    public Map<String, String> orders(@PathVariable String merchantId) {
        return Map.of("merchantId", merchantId, "orders", "[]");
    }

    /** 3) Weighted: an expensive call costs 5 tokens, keyed per user header. */
    @RateLimit(capacity = 50, refillPerSecond = 10, permits = 5, key = "#request.getHeader('X-User-Id') ?: #ip")
    @PostMapping("/api/reports")
    public Map<String, String> report() {
        return Map.of("status", "queued");
    }

    /** 4) Programmatic: works anywhere (Kafka consumers, gRPC, schedulers). */
    @GetMapping("/api/export")
    public ResponseEntity<?> export(@RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        RateLimitDecision d = rateLimiter.tryAcquire("export:" + userId, EXPORT_LIMIT);
        if (!d.allowed()) {
            return ResponseEntity.status(429)
                    .header("Retry-After", Long.toString(Math.max(1, d.retryAfterMillis() / 1000)))
                    .body(Map.of("error", "slow down", "source", d.source()));
        }
        return ResponseEntity.ok(Map.of("export", "started", "remaining", d.remaining(), "source", d.source()));
    }
}

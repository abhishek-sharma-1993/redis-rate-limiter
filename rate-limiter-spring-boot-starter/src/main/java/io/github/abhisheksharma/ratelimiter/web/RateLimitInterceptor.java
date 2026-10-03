package io.github.abhisheksharma.ratelimiter.web;

import io.github.abhisheksharma.ratelimiter.core.BucketConfig;
import io.github.abhisheksharma.ratelimiter.core.RateLimitDecision;
import io.github.abhisheksharma.ratelimiter.core.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Enforces {@link RateLimit} on MVC handlers. Denied requests get HTTP 429 with a JSON body and
 * {@code Retry-After}; the handler is not invoked and no exception is thrown.
 * Any internal problem (bad SpEL, etc.) is logged and the request is allowed (fail-open).
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private record Rule(String name, String keyExpression, BucketConfig config, int permits) { }

    private final RateLimiter limiter;
    private final SpelKeyResolver keyResolver;
    private final boolean emitHeaders;
    private final ConcurrentMap<Method, Optional<Rule>> rules = new ConcurrentHashMap<>();

    public RateLimitInterceptor(RateLimiter limiter, SpelKeyResolver keyResolver, boolean emitHeaders) {
        this.limiter = limiter;
        this.keyResolver = keyResolver;
        this.emitHeaders = emitHeaders;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        try {
            Optional<Rule> rule = rules.computeIfAbsent(hm.getMethod(), m -> resolveRule(hm));
            if (rule.isEmpty()) {
                return true;
            }
            Rule r = rule.get();
            String caller = keyResolver.resolve(r.keyExpression(), request, hm.getMethod().getName());
            RateLimitDecision d = limiter.tryAcquire(r.name() + ':' + caller, r.config(), r.permits());

            if (emitHeaders) {
                response.setHeader("X-RateLimit-Limit", Long.toString(d.limit()));
                response.setHeader("X-RateLimit-Remaining", Long.toString(d.remaining()));
            }
            if (d.allowed()) {
                return true;
            }
            writeTooManyRequests(response, d);
            return false;
        } catch (Exception e) {
            log.warn("Rate limit check skipped (fail-open) for {}: {}", hm.getShortLogMessage(), e.toString());
            return true;
        }
    }

    private Optional<Rule> resolveRule(HandlerMethod hm) {
        RateLimit ann = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RateLimit.class);
        if (ann == null) {
            ann = AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RateLimit.class);
        }
        if (ann == null) {
            return Optional.empty();
        }
        String name = ann.name().isBlank()
                ? hm.getBeanType().getSimpleName() + '.' + hm.getMethod().getName()
                : ann.name();
        return Optional.of(new Rule(name, ann.key(), BucketConfig.of(ann.capacity(), ann.refillPerSecond()), ann.permits()));
    }

    private static void writeTooManyRequests(HttpServletResponse response, RateLimitDecision d) throws java.io.IOException {
        long retrySeconds = d.retryAfterMillis() < 0 ? 60 : Math.max(1, (d.retryAfterMillis() + 999) / 1000);
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retrySeconds));
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\",\"retryAfterMs\":"
                + d.retryAfterMillis() + "}");
    }
}

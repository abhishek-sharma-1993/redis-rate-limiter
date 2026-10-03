package io.github.abhisheksharma.ratelimiter.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rate-limit a Spring MVC handler (or every handler of a controller).
 *
 * <pre>{@code
 * @RateLimit(capacity = 20, refillPerSecond = 10, key = "#request.getHeader('X-User-Id')")
 * @GetMapping("/orders")
 * public List<Order> orders() { ... }
 * }</pre>
 *
 * <p>SpEL variables available in {@link #key()}:
 * {@code #request} (HttpServletRequest), {@code #ip} (client IP),
 * {@code #pathVars} (Map of URI template variables), {@code #method} (handler method name).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** Logical bucket name. Defaults to {@code SimpleClassName.method}. */
    String name() default "";

    /** SpEL that identifies the caller. Default: per client IP. */
    String key() default "#ip";

    /** Bucket size (max burst). */
    long capacity();

    /** Steady-state tokens per second. */
    double refillPerSecond();

    /** Tokens consumed per request (weight). */
    int permits() default 1;
}

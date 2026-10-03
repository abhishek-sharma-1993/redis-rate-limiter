package io.github.abhisheksharma.ratelimiter.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates {@link RateLimit#key()} expressions. Expressions are parsed once and cached.
 * Uses {@link SimpleEvaluationContext} (no type references / constructors / bean access),
 * so a key expression cannot be abused to run arbitrary code.
 */
public class SpelKeyResolver {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();
    private final boolean trustForwardedFor;

    public SpelKeyResolver(boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    public String resolve(String expression, HttpServletRequest request, String methodName) {
        Expression expr = cache.computeIfAbsent(expression, parser::parseExpression);
        EvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding().withInstanceMethods().build();
        ctx.setVariable("request", request);
        ctx.setVariable("ip", clientIp(request));
        ctx.setVariable("method", methodName);
        Object pathVars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        ctx.setVariable("pathVars", pathVars instanceof Map<?, ?> m ? m : Map.of());
        Object value = expr.getValue(ctx);
        return value == null ? "anonymous" : value.toString();
    }

    String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                return (comma > 0 ? xff.substring(0, comma) : xff).trim();
            }
        }
        return request.getRemoteAddr();
    }
}

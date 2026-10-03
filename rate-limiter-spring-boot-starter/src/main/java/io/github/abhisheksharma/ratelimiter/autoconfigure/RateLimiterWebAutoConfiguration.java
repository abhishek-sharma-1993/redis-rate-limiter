package io.github.abhisheksharma.ratelimiter.autoconfigure;

import io.github.abhisheksharma.ratelimiter.core.RateLimiter;
import io.github.abhisheksharma.ratelimiter.web.RateLimitInterceptor;
import io.github.abhisheksharma.ratelimiter.web.SpelKeyResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration(after = RateLimiterAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(WebMvcConfigurer.class)
@ConditionalOnBean(RateLimiter.class)
@ConditionalOnProperty(prefix = "ratelimiter.web", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimiterWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SpelKeyResolver rateLimitKeyResolver(RateLimiterProperties props) {
        return new SpelKeyResolver(props.getWeb().isTrustForwardedFor());
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitInterceptor rateLimitInterceptor(RateLimiter limiter, SpelKeyResolver resolver, RateLimiterProperties props) {
        return new RateLimitInterceptor(limiter, resolver, props.getWeb().isHeaders());
    }

    @Bean
    public WebMvcConfigurer rateLimitWebMvcConfigurer(RateLimitInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}

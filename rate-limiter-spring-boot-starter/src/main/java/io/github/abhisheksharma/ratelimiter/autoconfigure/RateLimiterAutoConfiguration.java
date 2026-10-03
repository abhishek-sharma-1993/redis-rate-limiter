package io.github.abhisheksharma.ratelimiter.autoconfigure;

import io.github.abhisheksharma.ratelimiter.core.NoopRateLimiter;
import io.github.abhisheksharma.ratelimiter.core.RateLimiter;
import io.github.abhisheksharma.ratelimiter.local.LocalRateLimiter;
import io.github.abhisheksharma.ratelimiter.metrics.RateLimiterMetrics;
import io.github.abhisheksharma.ratelimiter.redis.RedisTokenBucketRateLimiter;
import io.github.abhisheksharma.ratelimiter.resilience.CircuitBreaker;
import io.github.abhisheksharma.ratelimiter.resilience.DenyCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration(
        after = {RedisAutoConfiguration.class, RedisReactiveAutoConfiguration.class},
        afterName = "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration")
@EnableConfigurationProperties(RateLimiterProperties.class)
public class RateLimiterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RateLimiterMetrics rateLimiterMetrics(ObjectProvider<MeterRegistry> registry) {
        return new RateLimiterMetrics(registry.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ratelimiter", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(StringRedisTemplate.class)
    static class RedisLimiterConfiguration {

        @Bean
        @ConditionalOnMissingBean(RateLimiter.class)
        public RedisTokenBucketRateLimiter rateLimiter(StringRedisTemplate redis,
                                                       ObjectProvider<ReactiveStringRedisTemplate> reactiveRedis,
                                                       RateLimiterProperties props,
                                                       RateLimiterMetrics metrics) {
            RateLimiterProperties.Local local = props.getLocal();
            RateLimiterProperties.DenyCacheProps dc = props.getDenyCache();
            RateLimiterProperties.CircuitBreakerProps cb = props.getCircuitBreaker();

            return RedisTokenBucketRateLimiter.builder(redis)
                    .reactiveRedis(reactiveRedis.getIfAvailable())
                    .keyPrefix(props.getKeyPrefix())
                    .fallbackStrategy(props.getFallback())
                    .localFallback(new LocalRateLimiter(local.getMaxKeys(), local.getIdleExpiry(), local.getRatio()))
                    .circuitBreaker(new CircuitBreaker(cb.getFailureThreshold(), cb.getOpenDuration()))
                    .denyCache(dc.isEnabled() ? new DenyCache(dc.getMaxKeys(), dc.getMaxTtl()) : null)
                    .metrics(metrics)
                    .asyncTimeout(props.getAsyncTimeout())
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ratelimiter", name = "enabled", havingValue = "false")
    static class DisabledConfiguration {

        @Bean
        @ConditionalOnMissingBean(RateLimiter.class)
        public RateLimiter rateLimiter() {
            return new NoopRateLimiter();
        }
    }
}

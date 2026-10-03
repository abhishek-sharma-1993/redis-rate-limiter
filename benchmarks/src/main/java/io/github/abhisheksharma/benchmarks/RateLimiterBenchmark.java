package io.github.abhisheksharma.benchmarks;

import io.github.abhisheksharma.ratelimiter.core.BucketConfig;
import io.github.abhisheksharma.ratelimiter.core.RateLimitDecision;
import io.github.abhisheksharma.ratelimiter.local.LocalRateLimiter;
import io.github.abhisheksharma.ratelimiter.redis.RedisTokenBucketRateLimiter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Run:  java -jar benchmarks/target/benchmarks.jar -rf json -rff benchmarks/results/jmh.json
 * Env:  REDIS_HOST / REDIS_PORT (default localhost:6379)
 *
 * Scenarios
 *  - hotKey           : every thread hits one bucket (worst case for Redis: single slot, single key)
 *  - spreadKeys       : uniformly random over N keys (typical per-user / per-IP limiting)
 *  - hotKeyDenyCache  : abusive client already over limit -> served by the local deny cache
 *  - localOnly        : in-memory fallback path, lower bound of library overhead
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 5)
@Measurement(iterations = 3, time = 10)
@Fork(1)
@Threads(32)
public class RateLimiterBenchmark {

    @Param({"100000"})
    public int keySpace;

    private LettuceConnectionFactory factory;
    private RedisTokenBucketRateLimiter redisLimiter;
    private RedisTokenBucketRateLimiter redisLimiterNoDenyCache;
    private LocalRateLimiter localLimiter;

    // generous bucket so most calls are "allowed" and actually write to Redis
    private final BucketConfig generous = BucketConfig.of(1_000_000, 1_000_000);
    // tiny bucket that is exhausted immediately
    private final BucketConfig tiny = BucketConfig.of(1, 0.001);

    @Setup(Level.Trial)
    public void setUp() {
        String host = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).build());
        factory.afterPropertiesSet();
        factory.start();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        try (var conn = factory.getConnection()) {
            conn.serverCommands().flushDb();
        }

        redisLimiter = RedisTokenBucketRateLimiter.builder(redis).keyPrefix("bench").build();
        redisLimiterNoDenyCache = RedisTokenBucketRateLimiter.builder(redis).keyPrefix("bench").denyCache(null).build();
        localLimiter = new LocalRateLimiter(keySpace * 2L, Duration.ofMinutes(5), 1.0);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        factory.destroy();
    }

    @Benchmark
    public RateLimitDecision hotKey() {
        return redisLimiterNoDenyCache.tryAcquire("hot", generous);
    }

    @Benchmark
    public RateLimitDecision spreadKeys() {
        return redisLimiterNoDenyCache.tryAcquire("user:" + ThreadLocalRandom.current().nextInt(keySpace), generous);
    }

    @Benchmark
    public RateLimitDecision hotKeyDenyCache() {
        return redisLimiter.tryAcquire("abuser", tiny);
    }

    @Benchmark
    public RateLimitDecision localOnly() {
        return localLimiter.tryAcquire("user:" + ThreadLocalRandom.current().nextInt(keySpace), generous);
    }
}

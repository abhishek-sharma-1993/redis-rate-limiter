# Design notes & interview talking points

## 1. Requirements

**Functional**
- Limit requests per arbitrary key (user, IP, tenant, API key, merchant) with configurable burst and rate.
- Weighted requests (an expensive call can cost N tokens).
- Usable declaratively (annotation) and programmatically (any code path).
- Return enough info for clients to back off (`remaining`, `Retry-After`).

**Non-functional**
- Correct under concurrency across many app instances.
- p99 overhead in low single-digit ms; zero added latency when Redis is unhealthy.
- No single point of failure; never throws into the caller.
- Bounded memory in both Redis and the JVM.

## 2. Algorithm choice

| Algorithm | Burst control | Memory / key | Precision | Notes |
|---|---|---|---|---|
| Fixed window counter | poor (2x burst at window edges) | 1 int | low | simplest |
| Sliding window log | exact | O(requests) | exact | expensive at high RPS |
| Sliding window counter | good | 2 ints | approx | cheap, approximate |
| **Token bucket** | **explicit (capacity)** | **2 numbers** | **exact** | burst + steady rate are separate knobs |
| GCRA | explicit | 1 number | exact | elegant, harder to explain |

Token bucket was chosen because burst and sustained rate are separate, intuitive parameters and state is O(1).

## 3. Why Lua

Read → compute → write must be atomic. Options:
- `WATCH/MULTI/EXEC`: optimistic locking; retries explode on hot keys.
- Distributed lock: two extra round trips, lock-holder failure modes.
- **Lua script**: Redis runs it atomically on a single thread, one round trip, `EVALSHA` sends only a 40-byte hash.

Cost: scripts block Redis while running, so the script must be O(1). It is: one `HMGET`, one `HSET`, one `PEXPIRE`.

## 4. Scaling

- App tier: stateless, scale horizontally.
- Redis tier: Redis Cluster. One key per bucket means buckets hash evenly to 16,384 slots. Throughput grows ~linearly with primaries.
- Capacity estimate: ~100 bytes/bucket → 10M active buckets ≈ 1 GB. At ~50–100k script calls/s per primary (hardware dependent), 1M checks/s needs on the order of 10–20 primaries — fewer once the deny cache absorbs abusive traffic.
- Hot key (one tenant at huge RPS) is bound to one shard. Mitigations: deny cache (already in place), token leasing (roadmap), or splitting a tenant's bucket into K sub-buckets with `capacity/K` each.

## 5. Availability

- Redis Sentinel or Cluster with replicas and automatic failover.
- Tight timeouts (50 ms command, 200 ms connect) cap the cost of a sick Redis.
- Circuit breaker removes even that cost during an outage.
- Fallback strategy per deployment: `LOCAL` (default), `ALLOW`, or `DENY`.
- Async replication means a failover can lose the last few writes → buckets look fuller → brief over-admission. For a rate limiter this is the right side to err on.

## 6. Consistency trade-offs to mention

- Decisions from Redis are linearizable per bucket (single-threaded execution on the owning primary).
- Local fallback is only approximately global: each pod enforces `ratio × limit`. If the pod count changes during an outage the aggregate drifts.
- The deny cache is safe (can only under-serve a request that would also be denied by Redis).

## 7. Likely follow-up questions

- *What if Redis clock jumps?* Elapsed time is clamped at 0, so a backwards jump never mints tokens.
- *Why not rate limit at the API gateway?* You should for coarse edge limits. This library handles fine-grained, business-aware limits (per merchant, per weighted operation) inside services, and non-HTTP traffic like Kafka consumers.
- *How would you support multiple limits on one request (per-second AND per-day)?* Run two buckets; or extend the script to take N bucket keys and consume from all only if all have tokens (keys must share a hash tag in Cluster).
- *How do you change limits at runtime?* `BucketConfig` is passed per call, so limits can come from a config service; the script clamps existing tokens to the new capacity.
- *Memory leak risk with millions of keys?* Redis keys expire after full refill; JVM caches are Caffeine with size bounds and idle expiry.
- *Why Lettuce without a pool?* Lettuce multiplexes many concurrent commands over one connection; for short commands a pool adds contention without throughput.

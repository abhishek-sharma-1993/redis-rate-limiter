# Lua script baseline (server-side only)

Produced with `redis-benchmark` on a **1 vCPU Linux sandbox**, Redis 7 running on the same core as
the load generator (so pessimistic). 50 clients, no pipelining. Repeated runs on this machine varied
by up to ~30%; compare rows with each other rather than reading them as absolute capacity.

| Scenario                                        | Throughput  | p50     | p99     |
|-------------------------------------------------|-------------|---------|---------|
| Plain `GET` (baseline)                          | ~166k ops/s | 0.18 ms | 0.42 ms |
| `EVALSHA`, 100k distinct buckets, allowed       | ~62k ops/s  | 0.75 ms | 1.46 ms |
| `EVALSHA`, one hot bucket, allowed (writes)     | ~62k ops/s  | 0.75 ms | 1.40 ms |
| `EVALSHA`, one hot bucket, denied (no write)    | ~75k ops/s  | 0.58 ms | 1.20 ms |

Commands (SHA from `SCRIPT LOAD`):

```
redis-benchmark -n 200000 -c 50 -r 100000 EVALSHA $SHA 1 k:__rand_int__ 100 50 1
redis-benchmark -n 200000 -c 50 EVALSHA $SHA 1 hotA 1000000000 1000000000 1
redis-benchmark -n 200000 -c 50 EVALSHA $SHA 1 hotD 1 0.001 1
```

Correctness check run alongside: 40 threads × 50 requests against one bucket with capacity 1000
and near-zero refill → exactly **1000** admitted.

Memory: one bucket ≈ **104 bytes** (`MEMORY USAGE`), i.e. ~10 MB per 100k active keys.

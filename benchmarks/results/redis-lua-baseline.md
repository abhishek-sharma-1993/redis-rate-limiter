# Lua script baseline (server-side only)

Produced by `benchmarks/redis-lua-benchmark.sh` on a **1 vCPU Linux sandbox**, Redis 7 running
on the same core as `redis-benchmark` (so these numbers are pessimistic). 50 clients, no pipelining.

| Scenario                         | Throughput     | p50     | p95     | p99     |
|----------------------------------|----------------|---------|---------|---------|
| Plain `GET` (baseline)           | ~113k ops/s    | 0.29 ms | –       | –       |
| `EVALSHA` hot key (1 bucket)     | ~60k ops/s     | 0.73 ms | 1.26 ms | 1.48 ms |
| `EVALSHA` 100k distinct buckets  | ~50k ops/s     | 0.95 ms | 1.46 ms | 1.84 ms |
| 100k buckets, pipeline depth 16  | ~91k ops/s     | –       | –       | –       |

Correctness check run alongside: 40 threads × 50 requests against one bucket with capacity 1000
and near-zero refill → exactly **1000** admitted.

Memory: one bucket ≈ **104 bytes** (`MEMORY USAGE`), i.e. ~10 MB per 100k active keys.

Re-run on real hardware with `./benchmarks/redis-lua-benchmark.sh <host> <port>` and the JMH
suite (`java -jar benchmarks/target/benchmarks.jar`) and replace this table with your numbers.

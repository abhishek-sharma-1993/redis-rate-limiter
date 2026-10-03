#!/usr/bin/env bash
# Benchmarks the Lua script in isolation (server-side cost, no JVM), using redis-benchmark.
# Usage: ./benchmarks/redis-lua-benchmark.sh [host] [port]
set -euo pipefail
HOST=${1:-localhost}; PORT=${2:-6379}
SCRIPT="$(dirname "$0")/../rate-limiter-spring-boot-starter/src/main/resources/ratelimiter/token_bucket.lua"
SHA=$(redis-cli -h "$HOST" -p "$PORT" SCRIPT LOAD "$(cat "$SCRIPT")")
echo "script sha: $SHA"
echo; echo "== baseline: plain GET =="
redis-benchmark -h "$HOST" -p "$PORT" -n 200000 -c 50 -q GET foo
echo; echo "== hot key (all clients, one bucket) =="
redis-benchmark -h "$HOST" -p "$PORT" -n 200000 -c 50 EVALSHA "$SHA" 1 bench:hot 100 50 1 | grep -A2 "latency summary\|throughput summary"
echo; echo "== 100k distinct buckets =="
redis-benchmark -h "$HOST" -p "$PORT" -n 200000 -c 50 -r 100000 EVALSHA "$SHA" 1 bench:__rand_int__ 100 50 1 | grep -A2 "latency summary\|throughput summary"
echo; echo "== 100k distinct buckets, pipeline depth 16 =="
redis-benchmark -h "$HOST" -p "$PORT" -n 500000 -c 50 -P 16 -r 100000 -q EVALSHA "$SHA" 1 bench:__rand_int__ 100 50 1
echo; echo "memory per bucket (bytes): $(redis-cli -h "$HOST" -p "$PORT" --scan --pattern 'bench:0*' | head -1 | xargs -I{} redis-cli -h "$HOST" -p "$PORT" MEMORY USAGE {})"

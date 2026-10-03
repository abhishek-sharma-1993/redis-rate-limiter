--[[
  Atomic token-bucket rate limiter.

  KEYS[1]  bucket key (one hash per bucket -> safe for Redis Cluster)
  ARGV[1]  capacity                (max tokens / burst size)
  ARGV[2]  refill rate             (tokens per second, may be fractional)
  ARGV[3]  permits requested

  Returns { allowed(0|1), remaining_tokens, retry_after_ms }
    retry_after_ms = -1 when permits > capacity (can never succeed)

  Design notes
  - Clock comes from Redis TIME: every app instance agrees on "now" (no clock skew).
  - State is only written when tokens are consumed. A denied request leaves
    (tokens, ts) untouched, which projects to exactly the same refill later,
    so floods of rejected traffic generate zero writes / zero replication.
  - Keys get a PEXPIRE of "time to refill fully" + 1s, so idle buckets self-clean.
]]
if redis.replicate_commands then pcall(redis.replicate_commands) end -- Redis < 5 compatibility

local key       = KEYS[1]
local capacity  = tonumber(ARGV[1])
local rate      = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000000 + tonumber(t[2])   -- microseconds

local state  = redis.call('HMGET', key, 't', 'ts')
local tokens = tonumber(state[1])
local ts     = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

local elapsed = now - ts
if elapsed < 0 then elapsed = 0 end
tokens = math.min(capacity, tokens + (elapsed * rate) / 1000000)

local allowed = 0
local retry_after = 0
if requested <= tokens then
  tokens  = tokens - requested
  allowed = 1
  redis.call('HSET', key, 't', tokens, 'ts', now)
  redis.call('PEXPIRE', key, math.ceil((capacity * 1000) / rate) + 1000)
elseif requested > capacity then
  retry_after = -1
else
  retry_after = math.ceil(((requested - tokens) * 1000) / rate)
end

return { tostring(allowed), tostring(math.floor(tokens)), tostring(retry_after) }

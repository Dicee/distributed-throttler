local key = KEYS[1]
local requested = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now_ms = tonumber(ARGV[3])
local max_capacity = tonumber(ARGV[4])

local token_bucket = redis.call('HMGET', key, 'remaining', 'last_granted_ms')
local remaining = tonumber(token_bucket[1]) or max_capacity
local last_granted_ms = tonumber(token_bucket[2]) or now_ms

local elapsed = math.max(now_ms - last_granted_ms, 0)
remaining = math.min(max_capacity, elapsed * refill_rate + remaining)

if requested <= remaining then
	redis.call('HMSET', key, 'remaining', remaining - requested, 'last_granted_ms', now_ms)
	return 1
else
	return 0
end
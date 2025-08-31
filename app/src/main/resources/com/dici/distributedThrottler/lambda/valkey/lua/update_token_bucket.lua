local key = KEYS[1]
local requested = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local max_capacity = tonumber(ARGV[3])
local now_nanos = tonumber(ARGV[4]) -- injected for testing, but -1 in prod mode to use more accurate server time (eliminates clock drift)

local now_ms
if now_nanos == -1 then
    local time = redis.call('TIME')
    now_ms = tonumber(time[1]) * 1000 + tonumber(time[2]) / 1000
else
    now_ms = math.floor(now_nanos / 1000 / 1000)
end

local token_bucket = redis.call('HMGET', key, 'remaining', 'last_granted_ms')
local remaining = tonumber(token_bucket[1]) or max_capacity
local last_granted_ms = tonumber(token_bucket[2]) or now_ms

local elapsed = math.max(now_ms - last_granted_ms, 0)
remaining = math.min(max_capacity, elapsed * refill_rate + remaining)

if requested <= remaining then
	redis.call('HMSET', key, 'remaining', remaining - requested, 'last_granted_ms', now_ms)

    local ttl = math.ceil(2 * max_capacity / refill_rate) -- twice the amount of time it takes to refill the tokens entirely
    redis.call('PEXPIRE', key, ttl)

	return 1
else
	return 0
end
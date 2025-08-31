local timestamps_key = KEYS[1]
local counts_key = KEYS[2]

local requested = tonumber(ARGV[1])
local window_duration_ms = tonumber(ARGV[2])
local window_threshold = tonumber(ARGV[3])
local bucket_duration_ms = tonumber(ARGV[4])
local now_nanos = tonumber(ARGV[5]) -- injected for testing, but -1 in prod mode to use more accurate server time (eliminates clock drift)

local now_ms
if now_nanos == -1 then
    local time = redis.call('TIME')
    now_ms = tonumber(time[1]) * 1000 + tonumber(time[2]) / 1000
else
    now_ms = math.floor(now_nanos / 1000 / 1000)
end

-- We only expire a count that doesn't intersect the window's duration at all, which is why we have to subtract the bucket duration, since we store
-- the start timestamp of the bucket: if the start is outside of the window it doesn't mean the entire bucket is
local expired_timestamps = redis.call('ZRANGEBYSCORE', timestamps_key, 0, '(' .. (now_ms - window_duration_ms - bucket_duration_ms))
local counts = redis.call('HMGET', counts_key, 'total_count', unpack(expired_timestamps))

local window_count = tonumber(counts[1]) or 0
table.remove(counts, 1)

if table.getn(counts) > 0 then
    for i, expired_count in ipairs(counts) do
        window_count = window_count - expired_count
    end
    redis.call('ZREM', timestamps_key, unpack(expired_timestamps))
    redis.call('HDEL', counts_key, unpack(expired_timestamps))
end

local current_bucket_start = now_ms - (now_ms % bucket_duration_ms)
local granted = 0

if window_count + requested <= window_threshold then
    redis.call('HSET', counts_key, 'total_count', window_count + requested)
    redis.call('HINCRBY', counts_key, current_bucket_start, requested)
    redis.call('ZADD', timestamps_key, current_bucket_start, current_bucket_start)

    granted = 1
else
    redis.call('HSET', counts_key, 'total_count', window_count)
end

-- We should not suffer from race conditions with one key being expired and the other not because Valkey both has active and lazy expiry.
-- In other words, the ttl is checked before reading the value, so even if the key is still present it will be considered missing. This
-- behaviour is documented here: https://valkey.io/commands/expire/
local ttl = math.ceil(window_duration_ms * 2)
redis.call('PEXPIRE', timestamps_key, ttl)
redis.call('PEXPIRE', counts_key, ttl)

return granted

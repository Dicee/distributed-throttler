local timestamps_key = KEYS[1]
local counts_key = KEYS[2]

local requested = tonumber(ARGV[1])
local window_duration_ms = tonumber(ARGV[2])
local window_threshold = tonumber(ARGV[3])
local bucket_duration_ms = tonumber(ARGV[4])

local time = redis.call('TIME')
local now_ms = tonumber(time[1]) * 1000 + tonumber(time[2]) / 1000

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
    if window_count + requested < 0 then
        error('Total count should never be negative. ')
    end
    redis.call('ZREM', timestamps_key, unpack(expired_timestamps))
    redis.call('HDEL', counts_key, unpack(expired_timestamps))
end

local current_bucket_start = now_ms - (now_ms % bucket_duration_ms)

if window_count + requested <= window_threshold then
    if window_count + requested < 0 then
        error('Total count should never be negative. ')
    end
    redis.call('HSET', counts_key, 'total_count', window_count + requested)
    redis.call('HINCRBY', counts_key, current_bucket_start, requested)
    redis.call('ZADD', timestamps_key, current_bucket_start, current_bucket_start)
    return 1
else
    redis.call('HSET', counts_key, 'total_count', window_count)
    return 0
end
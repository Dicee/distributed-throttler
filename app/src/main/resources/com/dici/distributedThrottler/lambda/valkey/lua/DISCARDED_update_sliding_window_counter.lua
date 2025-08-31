--[[
Before I got the idea of using both a ZSET and a HASH, I experimented with LIST because it looks close to how I would implement a local throttler using a
sliding window, with a double-ended queue of which each element is the count of requests in a certain time span. However after more research and reflection,
I found this implementation to have some flaws:
- it is highly sensitive to failures in the middle of the script, since I used the first element of the list to contain the total sum. If the script fails before
  it can re-insert the total sum after having dropped it, the data is now corrupted. To be honest I just wanted to avoid using a hash slot, but we could do that
  and store the sum separately. We can also imagine checking the first element and if it doesn't have the right format we re-calculate the whole sum, but this
  adds complexity. It seems like in a real system though, we might have to do such things at least periodically because with any implementation that performs
  several writes, we can end up in states with inconsistent data in case of failure in the middle of the script. That said, in most implementations we only get
  inaccurate data while with this one, it's corrupted and not usable with the nominal code path.
- it requires a lot of accesses to the list. It should still be relatively fast since the Valkey commands are all running locally, but it is not as
  fast as using batch commands. I don't know how impactful it would be in real life, but I might have tried to use range commands to improve that

All in all, I decided to look for another implementation and got a better idea. I still wanted to keep this code for the record.
]]

local SEPARATOR = ':'

local key = KEYS[1]
local requested = tonumber(ARGV[1])
local window_duration_ms = tonumber(ARGV[2])
local window_threshold = tonumber(ARGV[3])
local bucket_duration_ms = tonumber(ARGV[4])

local function split_nums(str, sep)
  local result = {}
  for part in string.gmatch(str, "([^" .. sep .. "]+)") do
    table.insert(result, tonumber(part))
  end
  return result
end

local function pop_expired_buckets(key, now_ms, window_duration_ms)
    local expired_count = 0
    repeat
        local timestamp_and_count = redis.call('LPOP', key)
        if timestamp_and_count then
            local timestamp, count = unpack(split_nums(timestamp_and_count, SEPARATOR))
            if tonumber(timestamp) >= now_ms - window_duration_ms then
                -- put the item back at the top of the list because it's still within the window
                redis.call('LPUSH', key, timestamp_and_count)
               return expired_count
            end

            expired_count = expired_count + tonumber(count)
        else
            return expired_count
        end
    until false
end

local function pop_current_bucket(key, now_ms, bucket_duration_ms)
    local should_insert_current_bucket = false
    local timestamp_and_count = redis.call('RPOP', key)
    local current_bucket_start = now_ms - (now_ms % bucket_duration_ms)

    if not timestamp_and_count then
        return current_bucket_start, 0
    end

    local timestamp, count = unpack(split_nums(timestamp_and_count, SEPARATOR))
    if timestamp < current_bucket_start then
        -- re-insert the previous last entry as it's not the current bucket, which will soon be inserted
        redis.call('RPUSH', key, timestamp_and_count)
        return current_bucket_start, 0
    end

    return timestamp, count
end

local time = redis.call('TIME')
local now_ms = tonumber(time[1]) * 1000 + tonumber(time[2]) / 1000
local window_count = tonumber(redis.call('LPOP', key)) or 0

local expired_count = pop_expired_buckets(key, now_ms, window_duration_ms)
local granted_count = 0

if window_count - expired_count + requested <= window_threshold then
    granted_count = requested

    local timestamp, count = pop_current_bucket(key, now_ms, bucket_duration_ms)
    redis.call('RPUSH', key, timestamp .. SEPARATOR .. (count + requested))
end

local new_window_count = window_count - expired_count + granted_count
if new_window_count > 0 then
    redis.call('LPUSH', key, new_window_count)
end

return granted_count > 0


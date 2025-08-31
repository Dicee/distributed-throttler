-- Please see the documentation in LeakyBucketRateLimiter for high-level explanations on the logic

local LAST_LEAKED_MICROS = 'last_leaked_micros'
local AVAILABLE_TOKENS = 'available_tokens'

local key = KEYS[1]
local leak_freq_micros = tonumber(ARGV[1])
local leak_amount = tonumber(ARGV[2])
local requested_tokens = tonumber(ARGV[3]) -- we do not validate that requested_tokens <= leak_amount because this is done by the throttler class
local now_nanos = tonumber(ARGV[4]) -- injected for testing, but -1 in prod mode to use more accurate server time (eliminates clock drift)

local now_micros
if now_nanos == -1 then
    local time = redis.call('TIME')
    now_micros = tonumber(time[1]) * 1000000 + tonumber(time[2])
else
    now_micros = math.floor(now_nanos / 1000)
end

local tokens_state = redis.call('HMGET', key, LAST_LEAKED_MICROS, AVAILABLE_TOKENS)
local last_leaked_micros = tonumber(tokens_state[1])
local available_tokens = tonumber(tokens_state[2]) or 0

-- Set or reset the number of tokens if we reached the next leak occurrence. We do not preserve previously leaked token if
-- they were not used within a the previous leak period, otherwise the client would be able to accumulate tokens and perform
-- more calls than it should be allowed.
if last_leaked_micros == nil or last_leaked_micros + leak_freq_micros <= now_micros then
    available_tokens = leak_amount
end

if available_tokens >= requested_tokens then
    redis.call('HMSET', key, LAST_LEAKED_MICROS, now_micros, AVAILABLE_TOKENS, available_tokens - requested_tokens)
    return 0
else
    return last_leaked_micros + leak_freq_micros - now_micros
end
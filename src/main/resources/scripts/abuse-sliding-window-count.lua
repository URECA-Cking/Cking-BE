-- KEYS[1]: Sliding Window ZSET key
-- ARGV[1]: observation time in epoch milliseconds
-- ARGV[2]: ZSET member (observationId or requestId)
-- ARGV[3]: window size in milliseconds
-- ARGV[4]: TTL in seconds (window + 60 seconds, rounded up)

local key = KEYS[1]
local observedAtEpochMs = tonumber(ARGV[1])
local member = ARGV[2]
local windowMs = tonumber(ARGV[3])
local ttlSeconds = tonumber(ARGV[4])

redis.call('ZADD', key, observedAtEpochMs, member)
redis.call('ZREMRANGEBYSCORE', key, '-inf', observedAtEpochMs - windowMs)
local count = redis.call('ZCARD', key)
redis.call('EXPIRE', key, ttlSeconds)

return count

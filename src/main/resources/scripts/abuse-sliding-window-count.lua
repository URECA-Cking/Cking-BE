-- KEYS[1]: Sliding Window ZSET key
-- ARGV[1]: ZSET member (observationId or requestId)
-- ARGV[2]: window size in milliseconds
-- ARGV[3]: TTL in seconds (window + 60 seconds, rounded up)

local key = KEYS[1]
local member = ARGV[1]
local windowMs = tonumber(ARGV[2])
local ttlSeconds = tonumber(ARGV[3])
local redisTime = redis.call('TIME')
local executedAtEpochMs = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)

redis.call('ZADD', key, executedAtEpochMs, member)
redis.call('ZREMRANGEBYSCORE', key, '-inf', executedAtEpochMs - windowMs)
local count = redis.call('ZCARD', key)
redis.call('EXPIRE', key, ttlSeconds)

return count

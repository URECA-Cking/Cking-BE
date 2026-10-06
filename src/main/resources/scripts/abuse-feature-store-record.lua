-- KEYS[1..11]: mission request, duplicate mission, entry request, insufficient balance,
--              requestId rotation, last earn, rapid earn-spend, failure, failure type,
--              failure sequence, insufficient balance sequence
-- ARGV[1..13]: action type, result classification, result code, observationId, requestId,
--              each feature window in ms, rapid max delay in ms

local missionRequestKey = KEYS[1]
local duplicateMissionKey = KEYS[2]
local entryRequestKey = KEYS[3]
local insufficientBalanceKey = KEYS[4]
local requestIdRotationKey = KEYS[5]
local lastEarnKey = KEYS[6]
local rapidEarnSpendKey = KEYS[7]
local failureKey = KEYS[8]
local failureTypeKey = KEYS[9]
local failureSequenceKey = KEYS[10]
local insufficientBalanceSequenceKey = KEYS[11]

local actionType = ARGV[1]
local resultClassification = ARGV[2]
local resultCode = ARGV[3]
local observationId = ARGV[4]
local requestId = ARGV[5]
local missionRequestWindowMs = tonumber(ARGV[6])
local duplicateMissionWindowMs = tonumber(ARGV[7])
local entryRequestWindowMs = tonumber(ARGV[8])
local insufficientBalanceWindowMs = tonumber(ARGV[9])
local requestIdRotationWindowMs = tonumber(ARGV[10])
local rapidEarnSpendWindowMs = tonumber(ARGV[11])
local rapidEarnSpendMaxDelayMs = tonumber(ARGV[12])
local failureWindowMs = tonumber(ARGV[13])
local redisTime = redis.call('TIME')
local observedAtEpochMs = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)

local ttlPaddingMs = 60000
local zero = 0

-- Window TTL에 고정 padding을 더한 밀리초 값을 반환한다.
local function ttlMilliseconds(windowMs)
    return windowMs + ttlPaddingMs
end

-- 현재 Observation을 ZSET에 기록하고 범위 밖 데이터를 제거한 뒤 count를 반환한다.
local function recordWindow(key, member, windowMs)
    redis.call('ZADD', key, observedAtEpochMs, member)
    redis.call('ZREMRANGEBYSCORE', key, '-inf', observedAtEpochMs - windowMs)
    local count = redis.call('ZCARD', key)
    redis.call('PEXPIRE', key, ttlMilliseconds(windowMs))
    return count
end

-- 현재 요청을 기록하지 않고 Redis 실행 시각 기준으로 기존 window의 count만 반환한다.
local function currentWindowCount(key, windowMs)
    redis.call('ZREMRANGEBYSCORE', key, '-inf', observedAtEpochMs - windowMs)
    return redis.call('ZCARD', key)
end

-- 마지막 관찰 시각이 window 밖이면 연속 횟수를 1로 다시 시작한다.
local function recordSequence(key, windowMs)
    local previousObservedAt = tonumber(redis.call('HGET', key, 'lastObservedAtEpochMs'))
    local previousCount = tonumber(redis.call('HGET', key, 'count')) or zero
    local count = previousCount + 1
    if previousObservedAt == nil or previousObservedAt <= observedAtEpochMs - windowMs then
        count = 1
    end
    redis.call('HSET', key, 'count', count, 'lastObservedAtEpochMs', observedAtEpochMs)
    redis.call('PEXPIRE', key, ttlMilliseconds(windowMs))
    return count
end

-- window 안에 마지막으로 관찰된 실패 코드만 남겨 distinct type count를 계산한다.
local function recordFailureType()
    local fields = redis.call('HGETALL', failureTypeKey)
    for index = 1, #fields, 2 do
        if tonumber(fields[index + 1]) <= observedAtEpochMs - failureWindowMs then
            redis.call('HDEL', failureTypeKey, fields[index])
        end
    end
    redis.call('HSET', failureTypeKey, resultCode, observedAtEpochMs)
    redis.call('PEXPIRE', failureTypeKey, ttlMilliseconds(failureWindowMs))
    return redis.call('HLEN', failureTypeKey)
end

if resultClassification == 'REPLAY' or resultClassification == 'SYSTEM_FAILURE' then
    return {zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero}
end

local missionRequestCount = zero
local duplicateMissionFailureCount = zero
local entryRequestCount = zero
local insufficientBalanceFailureCount = zero
local insufficientBalanceConsecutiveCount = zero
local distinctRequestIdCount = zero
local rapidEarnSpendPairCount = zero
local rapidEarnSpendPairCreated = zero
local failureCount = zero
local failureConsecutiveCount = zero
local distinctFailureTypeCount = zero

if actionType == 'MISSION_COMPLETE' then
    missionRequestCount = recordWindow(missionRequestKey, observationId, missionRequestWindowMs)
    distinctRequestIdCount = recordWindow(requestIdRotationKey, requestId, requestIdRotationWindowMs)
else
    entryRequestCount = recordWindow(entryRequestKey, observationId, entryRequestWindowMs)
    missionRequestCount = currentWindowCount(missionRequestKey, missionRequestWindowMs)
end

if resultClassification == 'NEW_SUCCESS' then
    redis.call('DEL', failureSequenceKey)
    redis.call('DEL', insufficientBalanceSequenceKey)

    if actionType == 'MISSION_COMPLETE' then
        redis.call('PSETEX', lastEarnKey, rapidEarnSpendMaxDelayMs, observedAtEpochMs)
    else
        local lastEarnAt = tonumber(redis.call('GET', lastEarnKey))
        if lastEarnAt ~= nil and lastEarnAt <= observedAtEpochMs
                and observedAtEpochMs - lastEarnAt <= rapidEarnSpendMaxDelayMs then
            rapidEarnSpendPairCount = recordWindow(
                    rapidEarnSpendKey, observationId, rapidEarnSpendWindowMs)
            rapidEarnSpendPairCreated = 1
            redis.call('DEL', lastEarnKey)
        end
    end
else
    failureCount = recordWindow(failureKey, observationId, failureWindowMs)
    failureConsecutiveCount = recordSequence(failureSequenceKey, failureWindowMs)
    distinctFailureTypeCount = recordFailureType()

    if actionType == 'MISSION_COMPLETE' and resultCode == 'DUPLICATE_MISSION' then
        duplicateMissionFailureCount = recordWindow(
                duplicateMissionKey, observationId, duplicateMissionWindowMs)
    end

    if actionType == 'EVENT_ENTRY' and resultCode == 'INSUFFICIENT_BALANCE' then
        insufficientBalanceFailureCount = recordWindow(
                insufficientBalanceKey, observationId, insufficientBalanceWindowMs)
        insufficientBalanceConsecutiveCount = recordSequence(
                insufficientBalanceSequenceKey, insufficientBalanceWindowMs)
    end
end

return {
    missionRequestCount,
    duplicateMissionFailureCount,
    entryRequestCount,
    insufficientBalanceFailureCount,
    insufficientBalanceConsecutiveCount,
    distinctRequestIdCount,
    rapidEarnSpendPairCount,
    rapidEarnSpendPairCreated,
    failureCount,
    failureConsecutiveCount,
    distinctFailureTypeCount
}

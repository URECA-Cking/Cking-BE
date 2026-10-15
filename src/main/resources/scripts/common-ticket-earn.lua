-- 공용 응모권(크리에이터 무관) EARN 원자 처리(이슈 #219). ticket-earn.lua와 같은
-- 원칙(멱등성 확인 -> 중복 적립 가드 -> Balance 증가 -> Stream 발행)을 크리에이터
-- 축 없이 수행한다.
--
-- KEYS[1] = idem:common-mission:{requestId}                          String(JSON, TTL 25h)
-- KEYS[2] = mission:earn-guard:common:{userId}:{missionType}:{yyyymmdd} String(SETNX)
-- KEYS[3] = ticket:balance:common:{userId}                           String(integer)
-- KEYS[4] = ticket:maint:common:{userId}                             존재 여부만 확인(issue #256)
--
-- ARGV[1] = amount
-- ARGV[2] = fingerprint (CommonTicketEarnServiceImpl이 userId+missionType+missionId+amount로
--                        계산, periodKey 제외 — 자정 이후 재시도도 같은 요청으로 판정)
-- ARGV[3] = streamKey (예: stream:common-ticket-earned)
-- ARGV[4] = idemTtlSeconds
-- ARGV[5] = guardTtlSeconds
-- ARGV[6] = requestId
-- ARGV[7] = userId
-- ARGV[8] = missionType
-- ARGV[9] = missionId
-- ARGV[10] = periodKey (fingerprint에는 안 쓰이고 Guard 키 구성과 Stream 발행 필드로만 쓰인다)
-- ARGV[11] = sourceType (미지정이면 기존 MISSION, ONCE는 평생 1회 적립)
-- ARGV[12] = rewardType (ONCE일 때만 사용)
--
-- 반환: { resultCode, ...옵션 필드 }
--   ALREADY_PROCESSED / REQUEST_ID_CONFLICT / DUPLICATE_MISSION / EARN_STATUS_UNKNOWN /
--   BALANCE_MAINTENANCE / EARN_ACCEPTED { 'EARN_ACCEPTED', streamId, 적립후잔액 }

local idemKey    = KEYS[1]
local guardKey   = KEYS[2]
local balanceKey = KEYS[3]
local maintenanceLockKey = KEYS[4]

local amount      = tonumber(ARGV[1])
local fingerprint = ARGV[2]
local streamKey   = ARGV[3]
local idemTtl     = tonumber(ARGV[4])
local guardTtl    = tonumber(ARGV[5])
local requestId   = ARGV[6]
local userId      = ARGV[7]
local missionType = ARGV[8]
local missionId   = ARGV[9]
local periodKey   = ARGV[10]
local sourceType  = ARGV[11] or 'MISSION'
local rewardType  = ARGV[12] or ''
local once        = sourceType == 'ONCE'

-- 평생 1회 요청은 Redis 멱등·Guard 키에도 만료를 두지 않는다.
local function setIdem(value, onlyIfAbsent)
    if once then
        if onlyIfAbsent then
            return redis.pcall('SET', idemKey, value, 'NX')
        end
        return redis.pcall('SET', idemKey, value)
    end
    if onlyIfAbsent then
        return redis.pcall('SET', idemKey, value, 'NX', 'EX', idemTtl)
    end
    return redis.pcall('SET', idemKey, value, 'EX', idemTtl)
end

-- 1) 멱등성 확인
local stored = redis.call('GET', idemKey)
if stored then
    local parsed = cjson.decode(stored)

    if parsed.fingerprint ~= fingerprint then
        return { 'REQUEST_ID_CONFLICT' }
    end
    if parsed.status == 'COMPLETED' then
        local result = parsed.result
        result[1] = 'ALREADY_PROCESSED'
        return result
    end
    -- PROCESSING은 저장된 Guard로 확인하고, 기존 레코드는 이번 Guard 키를 사용한다.
    local originalGuardKey = parsed.guardKey or guardKey
    local guardValue = requestId .. ':' .. fingerprint
    if redis.call('GET', originalGuardKey) == guardValue then
        local result = { 'ALREADY_PROCESSED' }
        setIdem(cjson.encode({ fingerprint = fingerprint, status = 'COMPLETED', result = result, guardKey = originalGuardKey }), false)
        return result
    end
    return { 'EARN_STATUS_UNKNOWN' }
end

-- 수동 보정 락 확인 (idem 재현 분기를 모두 통과한 신규 요청에만 적용 - issue #256)
-- CommonTicketCompensationService가 이 userId의 공용 잔액을 보정하는 동안에는
-- PROCESSING 예약조차 만들지 않고 BALANCE_MAINTENANCE로 종료한다.
if redis.call('EXISTS', maintenanceLockKey) == 1 then
    return { 'BALANCE_MAINTENANCE' }
end

-- 신규 요청은 PROCESSING과 실제 Guard 키를 함께 기록한다.
local idemReserved = setIdem(
    cjson.encode({ fingerprint = fingerprint, status = 'PROCESSING', guardKey = guardKey }), true)
if not idemReserved then
    return redis.error_reply('IDEM_RESERVE_FAILED: unexpected idem conflict for requestId=' .. requestId)
end

-- 2) 중복 적립 가드(userId+missionType+yyyyMMdd). 공용 미션은 유형당 하나뿐이라
-- missionType만으로 이미 유일하다(크리에이터 축이 없음).
local guardValue = requestId .. ':' .. fingerprint
local guardAcquired
if once then
    guardAcquired = redis.pcall('SET', guardKey, guardValue, 'NX')
else
    guardAcquired = redis.pcall('SET', guardKey, guardValue, 'NX', 'EX', guardTtl)
end
if type(guardAcquired) == 'table' and guardAcquired.err then
    redis.call('DEL', idemKey)
    return redis.error_reply('GUARD_ACQUIRE_FAILED: ' .. guardAcquired.err)
end
if not guardAcquired then
    redis.call('DEL', idemKey)
    local existingGuard = redis.call('GET', guardKey)
    if existingGuard == guardValue then
        return { 'ALREADY_PROCESSED' }
    elseif string.sub(existingGuard, 1, #requestId + 1) == requestId .. ':' then
        return { 'REQUEST_ID_CONFLICT' }
    else
        return { 'DUPLICATE_MISSION' }
    end
end

-- 3) Balance 증가 + Stream 발행
local balanceExisted = redis.call('EXISTS', balanceKey) == 1
local newBalance = redis.pcall('INCRBY', balanceKey, amount)

if type(newBalance) == 'table' and newBalance.err then
    redis.call('DEL', guardKey)
    redis.call('DEL', idemKey)
    return redis.error_reply('INCRBY_FAILED: ' .. newBalance.err)
end

local streamId
if once then
    streamId = redis.pcall('XADD', streamKey, '*',
        'sourceType', sourceType, 'requestId', requestId, 'userId', userId,
        'rewardType', rewardType, 'amount', ARGV[1], 'fingerprint', fingerprint)
else
    streamId = redis.pcall('XADD', streamKey, '*',
        'requestId', requestId, 'userId', userId, 'missionType', missionType,
        'missionId', missionId, 'periodKey', periodKey, 'amount', ARGV[1])
end

if type(streamId) == 'table' and streamId.err then
    if balanceExisted then
        redis.call('DECRBY', balanceKey, amount)
    else
        redis.call('DEL', balanceKey)
    end
    redis.call('DEL', guardKey)
    redis.call('DEL', idemKey)
    return redis.error_reply('XADD_FAILED: ' .. streamId.err)
end

local result = { 'EARN_ACCEPTED', streamId, tostring(newBalance) }

setIdem(cjson.encode({ fingerprint = fingerprint, status = 'COMPLETED', result = result, guardKey = guardKey }), false)

return result

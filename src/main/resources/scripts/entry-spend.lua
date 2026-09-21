-- 응모 요청을 하나의 Lua 스크립트에서 원자적으로 처리한다.
-- 처리 순서: ticketCount 검증 → 멱등성 확인 → guard 확인 → Gate 확인 → 시각 확인
-- → 잔액 확인 → guard 선점 → 차감 → Stream 발행 → 멱등 결과 저장
-- idem이 남아 있는 동일 요청은 마감 이후에도 DUPLICATE_REPLAY로 처리한다.
-- idem 저장 실패 시 Guard는 이벤트 진행 중 재차감만 막고, 종료 뒤에는 Gate/시각 검증을 따른다.
-- 멱등키가 없는 신규 요청만 Gate·시각·잔액을 검증한다.
--
-- KEYS[1] = event:status:{eventId}                   String(OPEN/CLOSED)
-- KEYS[2] = event:endat:{eventId}                     String(epoch millis, 불변)
-- KEYS[3] = ticket:balance:{creatorId}:{userId}      String(integer)
-- KEYS[4] = idem:{requestId}                         String(JSON: {fingerprint, result})
-- KEYS[5] = entry:spend-guard:{requestId}            String(JSON: {fingerprint})
-- KEYS[6] = ticket:maint:{creatorId}:{userId}        존재 여부만 확인(issue #172)
--
-- ARGV[1] = ticketCount
-- ARGV[2] = fingerprint      (EntrySpendService가 eventId+userId+ticketCount로 계산)
-- ARGV[3] = streamKey        (예: stream:ticket-deducted)
-- ARGV[4] = idemTtlSeconds   (FR-P2-033: 1시간 = 3600. idem 전용 - guard는 endAt 기준)
-- ARGV[5] = eventId
-- ARGV[6] = userId
-- ARGV[7] = creatorId
-- ARGV[8] = requestId
--
-- 반환: { resultCode, ...옵션 필드 } (FR-P2-036, 기존 10종 + BALANCE_MAINTENANCE)
--   GATE_NOT_LOADED        -- Gate 키 자체가 없음(2.4절: 없음=OPEN으로 간주 금지)
--   INVALID_TICKET_COUNT   -- ticketCount가 1 미만이거나 100 초과 (방어용, 주 검증은 상위 레이어. §5.3 방어용 최대값 100)
--   EVENT_NOT_OPEN         -- status != OPEN
--   EVENT_CLOSED           -- now >= endAt
--   IDEMPOTENCY_CONFLICT   -- 동일 requestId, 다른 fingerprint (idem 또는 guard 기준)
--   DUPLICATE_REPLAY       -- 동일 requestId, 동일 fingerprint. guard 경로는 code만 반환
--   BALANCE_NOT_LOADED     -- Balance 키 자체가 없음(없음=0 취급 금지)
--   INSUFFICIENT_BALANCE   -- 보유 응모권 < ticketCount
--   BALANCE_MAINTENANCE    -- 수동 보정 락(ticket:maint:{creatorId}:{userId})이 걸려 있음(issue #172, HTTP 503)
--   SUCCESS                -- { 'SUCCESS', streamId, 차감후잔액 }
-- (SYSTEM_ERROR는 스크립트가 반환하는 코드가 아니라, 스크립트 실행 자체가
--  예외를 던졌을 때 호출측 Java 코드가 매핑하는 코드다.)

local statusKey  = KEYS[1]
local endAtKey   = KEYS[2]
local balanceKey = KEYS[3]
local idemKey    = KEYS[4]
local guardKey   = KEYS[5]
local maintenanceLockKey = KEYS[6]

local ticketCount = tonumber(ARGV[1])
local fingerprint = ARGV[2]
local streamKey   = ARGV[3]
local idemTtl     = tonumber(ARGV[4])
local eventId     = ARGV[5]
local userId      = ARGV[6]
local creatorId   = ARGV[7]
local requestId   = ARGV[8]

if ticketCount == nil or ticketCount < 1 or ticketCount > 100 then
    return { 'INVALID_TICKET_COUNT' }
end

-- 1) 멱등성 확인 (Gate/시각보다 먼저: 이미 성공한 요청은 이벤트 마감 여부와
--    무관하게 기존 결과를 그대로 재현해야 한다 - issue #36)
local stored = redis.call('GET', idemKey)
if stored then
    local parsed = cjson.decode(stored)
    if parsed.fingerprint == fingerprint then
        local result = parsed.result
        result[1] = 'DUPLICATE_REPLAY'
        return result
    else
        return { 'IDEMPOTENCY_CONFLICT' }
    end
end

-- 1-1) idem이 없을 때 guard로 동일 요청 여부를 확인한다.
local storedGuard = redis.call('GET', guardKey)
if storedGuard then
    local parsedGuard = cjson.decode(storedGuard)
    if parsedGuard.fingerprint == fingerprint then
        return { 'DUPLICATE_REPLAY' }
    else
        return { 'IDEMPOTENCY_CONFLICT' }
    end
end

-- 1-2) 수동 보정 락 확인 (idem·guard 재현 이후, 신규 차감 전에만 적용 - issue #172)
-- TicketCompensationService.resyncRedisToDb()가 이 (creatorId, userId) 조합을
-- 보정하는 동안에는 새 차감을 진행하지 않고 BALANCE_MAINTENANCE로 종료한다.
if redis.call('EXISTS', maintenanceLockKey) == 1 then
    return { 'BALANCE_MAINTENANCE' }
end

-- 2) Gate 확인 (여기부터는 idem·guard 모두 없는 신규 요청만 도달한다)
local status = redis.call('GET', statusKey)
local endAt  = redis.call('GET', endAtKey)
if status == false or endAt == false then
    return { 'GATE_NOT_LOADED' }
end
if status ~= 'OPEN' then
    return { 'EVENT_NOT_OPEN' }
end

-- 3) 시각 확인 (앱 서버 시계가 아니라 Redis 서버 시각 기준, v1.5.4 §2.5)
local time = redis.call('TIME')
local nowMillis = (tonumber(time[1]) * 1000) + math.floor(tonumber(time[2]) / 1000)
if nowMillis >= tonumber(endAt) then
    return { 'EVENT_CLOSED' }
end

-- 4) Balance 확인
local balance = redis.call('GET', balanceKey)
if balance == false then
    return { 'BALANCE_NOT_LOADED' }
end
if tonumber(balance) < ticketCount then
    return { 'INSUFFICIENT_BALANCE', balance }
end

-- 5) 차감 전에 guard를 한 번만 선점한다.
local guardAcquired = redis.call('SET', guardKey,
    cjson.encode({ fingerprint = fingerprint }), 'NX')
if not guardAcquired then
    return redis.error_reply('GUARD_ACQUIRE_FAILED: unexpected guard conflict for requestId=' .. requestId)
end
redis.call('PEXPIREAT', guardKey, tonumber(endAt))

-- 6) 차감 + Stream 발행 + 멱등 결과 저장. Redis Lua는 오류가 나도 이전 쓰기를 자동
-- 롤백하지 않는다. DECRBY는 Balance가 손상돼(예: "1.5") tonumber() 검증은 통과해도
-- Redis 단에서 실패할 수 있다 - pcall로 잡아 guard를 해제하고 반환한다(차감이 없었
-- 으므로 잔액 보상은 불필요). XADD 실패는 INCRBY 보상 후 guard 해제. 둘 다 Java에서
-- SYSTEM_ERROR로 매핑된다.
local newBalance = redis.pcall('DECRBY', balanceKey, ticketCount)
if type(newBalance) == 'table' and newBalance.err then
    redis.call('DEL', guardKey)
    return redis.error_reply('DECRBY_FAILED: ' .. newBalance.err)
end

local streamId = redis.pcall('XADD', streamKey, '*',
    'eventId', eventId,
    'userId', userId,
    'creatorId', creatorId,
    'requestId', requestId,
    'ticketCount', ARGV[1])

if type(streamId) == 'table' and streamId.err then
    redis.call('INCRBY', balanceKey, ticketCount)
    redis.call('DEL', guardKey)
    return redis.error_reply('XADD_FAILED: ' .. streamId.err)
end

local result = { 'SUCCESS', streamId, tostring(newBalance) }

-- guard가 재차감을 막으므로 idem 저장 실패는 처리 결과에 영향을 주지 않는다.
redis.pcall('SET', idemKey, cjson.encode({ fingerprint = fingerprint, result = result }), 'EX', idemTtl)

return result

-- 응모(Entry) 요청의 원자적 처리.
--
-- 순서(issue #36 결정 - v1.5.4 §5.3 원문 순서에서 변경): 멱등성(requestId+fingerprint)
-- 확인 -> Gate 확인 -> 시각 확인 -> Balance 확인 -> 차감 -> Stream 발행 -> 멱등 결과
-- 저장. 이미 성공한 동일 requestId 요청은 그 사이 이벤트가 마감됐더라도 Gate/시각
-- 상태와 무관하게 DUPLICATE_REPLAY로 기존 성공 결과를 그대로 재현해야 하므로,
-- 멱등성 확인을 Gate/시각 확인보다 먼저 수행한다(#29에서 발견된 문제의 해결책).
-- 멱등키가 없는 신규 요청만 기존과 동일하게 Gate/시각/Balance를 검증한다.
-- 전부 한 스크립트 안에서 순차 실행되어 다른 요청이 중간에 끼어들 수 없다(FR-P2-030).
--
-- KEYS[1] = event:status:{eventId}                   String(OPEN/CLOSED)
-- KEYS[2] = event:endat:{eventId}                     String(epoch millis, 불변)
-- KEYS[3] = ticket:balance:{creatorId}:{userId}      String(integer)
-- KEYS[4] = idem:{requestId}                         String(JSON: {fingerprint, result})
--
-- ARGV[1] = ticketCount
-- ARGV[2] = fingerprint      (EntrySpendService가 eventId+userId+ticketCount로 계산)
-- ARGV[3] = streamKey        (예: stream:ticket-deducted)
-- ARGV[4] = idemTtlSeconds   (FR-P2-033: 1시간 = 3600)
-- ARGV[5] = eventId
-- ARGV[6] = userId
-- ARGV[7] = creatorId
-- ARGV[8] = requestId
--
-- 반환: { resultCode, ...옵션 필드 } (FR-P2-036, 10종 결과코드 계약)
--   GATE_NOT_LOADED        -- Gate 키 자체가 없음(2.4절: 없음=OPEN으로 간주 금지)
--   INVALID_TICKET_COUNT   -- ticketCount가 1 미만이거나 100 초과 (방어용, 주 검증은 상위 레이어. §5.3 방어용 최대값 100)
--   EVENT_NOT_OPEN         -- status != OPEN
--   EVENT_CLOSED           -- now >= endAt
--   IDEMPOTENCY_CONFLICT   -- 동일 requestId, 다른 fingerprint
--   DUPLICATE_REPLAY       -- 동일 requestId, 동일 fingerprint -> 기존 결과 재반환
--   BALANCE_NOT_LOADED     -- Balance 키 자체가 없음(없음=0 취급 금지)
--   INSUFFICIENT_BALANCE   -- 보유 응모권 < ticketCount
--   SUCCESS                -- { 'SUCCESS', streamId, 차감후잔액 }
-- (SYSTEM_ERROR는 스크립트가 반환하는 코드가 아니라, 스크립트 실행 자체가
--  예외를 던졌을 때 호출측 Java 코드가 매핑하는 코드다.)

local statusKey  = KEYS[1]
local endAtKey   = KEYS[2]
local balanceKey = KEYS[3]
local idemKey    = KEYS[4]

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

-- 2) Gate 확인 (여기부터는 멱등키가 없는 신규 요청만 도달한다)
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

-- 5) 차감 + Stream 발행 + 멱등 결과 저장
-- Redis Lua는 실행 중 오류가 나도 이전 쓰기를 자동 롤백하지 않는다. XADD 실패는
-- pcall로 잡아 INCRBY 보상 후 오류로 반환하며, Java가 SYSTEM_ERROR로 매핑한다.
local newBalance = redis.call('DECRBY', balanceKey, ticketCount)

local streamId = redis.pcall('XADD', streamKey, '*',
    'eventId', eventId,
    'userId', userId,
    'creatorId', creatorId,
    'requestId', requestId,
    'ticketCount', ARGV[1])

if type(streamId) == 'table' and streamId.err then
    redis.call('INCRBY', balanceKey, ticketCount)
    return redis.error_reply('XADD_FAILED: ' .. streamId.err)
end

local result = { 'SUCCESS', streamId, tostring(newBalance) }
redis.call('SET', idemKey, cjson.encode({ fingerprint = fingerprint, result = result }), 'EX', idemTtl)

return result

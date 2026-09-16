-- 응모(Entry) 요청의 원자적 처리.
--
-- 순서(v1.5.4 §5.3 근거): Gate 확인 -> 시각 확인 -> 멱등성(requestId+fingerprint) 확인
-- -> Balance 확인 -> 차감 -> 결과 저장 -> Stream 발행. 전부 한 스크립트 안에서
-- 순차 실행되어 다른 요청이 중간에 끼어들 수 없다(FR-P2-030).
--
-- KEYS[1] = event:status:{eventId}                   String(OPEN/CLOSED)
-- KEYS[2] = event:endat:{eventId}                     String(epoch millis, 불변)
-- KEYS[3] = ticket:balance:{creatorId}:{userId}      String(integer)
-- KEYS[4] = idem:{requestId}                         String(JSON: {fingerprint, result})
--
-- ARGV[1] = ticketCount
-- ARGV[2] = fingerprint      (호출측이 eventId+userId+ticketCount 등으로 계산해 전달)
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

-- 1) Gate 확인
local status = redis.call('GET', statusKey)
local endAt  = redis.call('GET', endAtKey)
if status == false or endAt == false then
    return { 'GATE_NOT_LOADED' }
end
if status ~= 'OPEN' then
    return { 'EVENT_NOT_OPEN' }
end

-- 2) 시각 확인 (앱 서버 시계가 아니라 Redis 서버 시각 기준, v1.5.4 §2.5)
local time = redis.call('TIME')
local nowMillis = (tonumber(time[1]) * 1000) + math.floor(tonumber(time[2]) / 1000)
if nowMillis >= tonumber(endAt) then
    return { 'EVENT_CLOSED' }
end

-- 3) 멱등성 확인
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

-- 4) Balance 확인
local balance = redis.call('GET', balanceKey)
if balance == false then
    return { 'BALANCE_NOT_LOADED' }
end
if tonumber(balance) < ticketCount then
    return { 'INSUFFICIENT_BALANCE', balance }
end

-- 5) 차감 + 결과 저장 + Stream 발행
--    Redis Lua는 명령 하나가 에러를 던져도 그 전에 실행된 쓰기를 자동으로 되돌리지 않는다.
--    XADD가 실패하면 idem 키가 아직 저장되지 않은 상태라 DB UNIQUE 안전망(FR-P2-033)도
--    적용되지 않으므로, 여기서만은 pcall로 직접 잡아 DECRBY를 보상(INCRBY)한 뒤 에러로
--    반환한다(호출측 Java에서 SYSTEM_ERROR로 매핑).
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

-- 응모(Entry) 요청의 원자적 처리.
--
-- 순서(v1.5.4 §5.3 근거): Gate 확인 -> 시각 확인 -> 멱등성(requestId+fingerprint) 확인
-- -> Balance 확인 -> 차감 -> 결과 저장 -> Stream 발행. 전부 한 스크립트 안에서
-- 순차 실행되어 다른 요청이 중간에 끼어들 수 없다(FR-P2-030).
--
-- KEYS[1] = event:meta:{eventId}                    Hash: status, endAt(epoch millis)
-- KEYS[2] = ticket:balance:{creatorId}:{userId}      String(integer)
-- KEYS[3] = idem:{requestId}                         String(JSON: {fingerprint, result})
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
--   INVALID_TICKET_COUNT   -- ticketCount가 1 미만 (방어용, 주 검증은 상위 레이어)
--   EVENT_NOT_OPEN         -- status != OPEN
--   EVENT_CLOSED           -- now >= endAt
--   IDEMPOTENCY_CONFLICT   -- 동일 requestId, 다른 fingerprint
--   DUPLICATE_REPLAY       -- 동일 requestId, 동일 fingerprint -> 기존 결과 재반환
--   BALANCE_NOT_LOADED     -- Balance 키 자체가 없음(없음=0 취급 금지)
--   INSUFFICIENT_BALANCE   -- 보유 응모권 < ticketCount
--   SUCCESS                -- { 'SUCCESS', streamId, 차감후잔액 }
-- (SYSTEM_ERROR는 스크립트가 반환하는 코드가 아니라, 스크립트 실행 자체가
--  예외를 던졌을 때 호출측 Java 코드가 매핑하는 코드다.)

local metaKey    = KEYS[1]
local balanceKey = KEYS[2]
local idemKey    = KEYS[3]

local ticketCount = tonumber(ARGV[1])
local fingerprint = ARGV[2]
local streamKey   = ARGV[3]
local idemTtl     = tonumber(ARGV[4])
local eventId     = ARGV[5]
local userId      = ARGV[6]
local creatorId   = ARGV[7]
local requestId   = ARGV[8]

if ticketCount == nil or ticketCount < 1 then
    return { 'INVALID_TICKET_COUNT' }
end

-- 1) Gate 확인
local meta = redis.call('HMGET', metaKey, 'status', 'endAt')
local status = meta[1]
local endAt  = meta[2]
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

-- 5) 차감 + 결과 저장 + Stream 발행 (여기서부터는 실패해도 되돌릴 값이 없다:
--    단일 스레드 실행이라 다른 요청이 끼어들 수 없으므로 이 블록 자체가 원자적이다)
local newBalance = redis.call('DECRBY', balanceKey, ticketCount)

local streamId = redis.call('XADD', streamKey, '*',
    'eventId', eventId,
    'userId', userId,
    'creatorId', creatorId,
    'requestId', requestId,
    'ticketCount', ARGV[1])

local result = { 'SUCCESS', streamId, tostring(newBalance) }
redis.call('SET', idemKey, cjson.encode({ fingerprint = fingerprint, result = result }), 'EX', idemTtl)

return result

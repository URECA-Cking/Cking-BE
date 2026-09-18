-- EARN(적립) 요청의 원자적 처리 — 멱등성 확인 + 중복 적립 가드 +
-- Balance 증가 + Stream 발행을 하나의 스크립트에서 원자적으로 수행한다 (FR-P2-006/008).
--
-- 순서(entry-spend.lua와 동일 원칙): 멱등성 확인 -> 중복 적립 가드 -> Balance 증가
-- -> Stream 발행 -> 성공 시에만 멱등 결과 저장.
--
-- KEYS[1] = idem:mission:{requestId}                                        String(JSON, TTL 25h)
-- KEYS[2] = mission:earn-guard:{userId}:{missionType}:{creatorId}:{yyyymmdd} String(SETNX, FR-P2-006)
-- KEYS[3] = ticket:balance:{creatorId}:{userId}                             String(integer)
--
-- ARGV[1] = amount
-- ARGV[2] = fingerprint      (TicketEarnServiceImpl이 userId+creatorId+missionType+
--                             missionId+missionKey+amount로 계산, periodKey 제외)
-- ARGV[3] = streamKey        (예: stream:ticket-earned)
-- ARGV[4] = idemTtlSeconds   (25시간 = 90000)
-- ARGV[5] = guardTtlSeconds  (25시간 = 90000)
-- ARGV[6] = requestId
-- ARGV[7] = userId
-- ARGV[8] = creatorId
-- ARGV[9] = missionType
-- ARGV[10] = missionId
-- ARGV[11] = periodKey       (이번 호출 시점에 계산된 값. fingerprint에는 안 쓰이고
--                             Guard 키 구성과 Stream 발행 필드로만 쓰인다)
-- ARGV[12] = missionKey
--
-- 반환: { resultCode, ...옵션 필드 } (EarnResultCode 6종 중 4종은 이 스크립트가 반환)
--   ALREADY_PROCESSED   -- 동일 requestId, 동일 fingerprint, idem이 COMPLETED -> 기존 결과 재반환
--                           (또는 idem 저장 실패 후 가드로 복구된 경우, code만)
--   REQUEST_ID_CONFLICT -- 동일 requestId, 다른 fingerprint (idem 또는 가드 기준)
--   DUPLICATE_MISSION   -- 다른 requestId, 이미 완료(또는 처리 중)된 미션(가드 값의 requestId 불일치)
--   EARN_STATUS_UNKNOWN -- 동일 requestId, 동일 fingerprint, idem이 PROCESSING
--                           (이전 시도가 Balance/Stream 처리 뒤 확정 전에 죽은 경우 - 신규
--                           지급 금지, 같은 requestId로만 재시도. 이 코드는 원래 Java가
--                           QueryTimeoutException에만 매핑하던 것인데, 이 분기에서는 Lua가
--                           직접 반환한다)
--   EARN_ACCEPTED       -- { 'EARN_ACCEPTED', streamId, 적립후잔액 }
-- (EARN_PROCESSING_FAILED는 스크립트가 아니라 호출측 Java가 매핑한다)
--
-- periodKey는 서버 파생값이다. 자정 이후 재시도도 같은 요청으로 판정하도록
-- fingerprint에서 제외한다.
--
-- 동일 requestId 재시도는 idem 단계에서 끝나며 Guard는 신규 요청에만 사용한다.

local idemKey    = KEYS[1]
local guardKey   = KEYS[2]
local balanceKey = KEYS[3]

local amount      = tonumber(ARGV[1])
local fingerprint = ARGV[2]
local streamKey   = ARGV[3]
local idemTtl     = tonumber(ARGV[4])
local guardTtl    = tonumber(ARGV[5])
local requestId   = ARGV[6]
local userId      = ARGV[7]
local creatorId   = ARGV[8]
local missionType = ARGV[9]
local missionId   = ARGV[10]
local periodKey   = ARGV[11]
local missionKey  = ARGV[12]

-- 1) 멱등성 확인
local stored = redis.call('GET', idemKey)
if stored then
    local parsed = cjson.decode(stored)

    -- 이전 형식은 status가 없고 periodKey 포함 fingerprint를 사용했다.
    -- 기존 record의 TTL 동안 완료된 성공으로만 취급한다.
    if parsed.status == nil and parsed.result ~= nil then
        local result = parsed.result
        result[1] = 'ALREADY_PROCESSED'
        return result
    end

    if parsed.fingerprint ~= fingerprint then
        return { 'REQUEST_ID_CONFLICT' }
    end
    if parsed.status == 'COMPLETED' then
        local result = parsed.result
        result[1] = 'ALREADY_PROCESSED'
        return result
    end
    -- status == 'PROCESSING': 이전 시도가 Balance/Stream까지 처리했을 수도 있는
    -- 상태라 신규 지급을 진행하면 안 된다. 같은 requestId 재시도를 유도한다.
    return { 'EARN_STATUS_UNKNOWN' }
end

-- 신규 요청: idem을 PROCESSING으로 선점한다. 바로 위에서 idem 부재를 확인했고
-- Lua는 단일 스레드로 원자 실행되므로 NX 없이도 안전하지만, 방어적으로 명시한다.
local idemReserved = redis.call('SET', idemKey,
    cjson.encode({ fingerprint = fingerprint, status = 'PROCESSING' }),
    'NX', 'EX', idemTtl)
if not idemReserved then
    return redis.error_reply('IDEM_RESERVE_FAILED: unexpected idem conflict for requestId=' .. requestId)
end

-- 2) 중복 적립 가드 (userId+missionType+creatorId+yyyyMMdd, FR-P2-006)
-- EARN API Business Key(userId+creatorId+missionId+periodKey, FR-P2-008)와는
-- 다른 레이어의 별도 키다 - 임의로 통합하지 않는다(취합v1.5.4 §4.2).
-- Guard 선점 오류 시에는 아직 변경된 상태가 없으므로 idem 예약을 정리한다.
local guardValue = requestId .. ':' .. fingerprint
local guardAcquired = redis.pcall('SET', guardKey, guardValue, 'NX', 'EX', guardTtl)
if type(guardAcquired) == 'table' and guardAcquired.err then
    redis.call('DEL', idemKey)
    return redis.error_reply('GUARD_ACQUIRE_FAILED: ' .. guardAcquired.err)
end
if not guardAcquired then
    -- 이 요청 자체는 성사되지 않았으므로 방금 만든 PROCESSING 예약을 정리한다.
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

-- 3) Balance 증가 + Stream 발행. Balance 키가 없으면 0에서 시작(INCRBY가 자동
-- 생성) — 취합v1.5.4 §2.4가 EARN 최초 적립을 Balance Key 미존재 정책(SPEND는
-- BALANCE_NOT_LOADED 반환)의 명시적 예외로 확정했다.
local balanceExisted = redis.call('EXISTS', balanceKey) == 1
local newBalance = redis.pcall('INCRBY', balanceKey, amount)

if type(newBalance) == 'table' and newBalance.err then
    -- 아직 아무것도 반영되지 않았으므로 예약 전부를 지우고 재시도를 처음부터
    -- 받을 수 있게 한다.
    redis.call('DEL', guardKey)
    redis.call('DEL', idemKey)
    return redis.error_reply('INCRBY_FAILED: ' .. newBalance.err)
end

local streamId = redis.pcall('XADD', streamKey, '*',
    'requestId', requestId,
    'userId', userId,
    'creatorId', creatorId,
    'missionType', missionType,
    'missionId', missionId,
    'periodKey', periodKey,
    'missionKey', missionKey,
    'amount', ARGV[1])

if type(streamId) == 'table' and streamId.err then
    -- Balance는 보상해 원상복구했으므로 이 시도도 안전하게 처음부터 재시도할 수
    -- 있다.
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

-- 4) idem을 COMPLETED로 확정한다. 이 SET이 실패하면(pcall로 무시) idem은
-- PROCESSING인 채로 남는다 - Balance/Stream은 이미 반영됐으므로 되돌릴 수 없고,
-- 재시도는 1단계에서 EARN_STATUS_UNKNOWN으로 안전하게 막힌다(신규 지급 방지가
-- 최우선). 그 요청의 실제 성사 여부 복구는 이 스크립트 범위 밖이다.
redis.pcall('SET', idemKey,
    cjson.encode({ fingerprint = fingerprint, status = 'COMPLETED', result = result }),
    'EX', idemTtl)

return result

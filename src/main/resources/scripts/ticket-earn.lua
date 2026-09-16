-- EARN(적립) 요청의 원자적 처리 — 멱등성 확인 + 중복 적립(Business Key) 가드 +
-- Balance 증가 + Stream 발행을 하나의 스크립트에서 원자적으로 수행한다 (FR-P2-006/008).
--
-- 순서(entry-spend.lua와 동일 원칙, 성집·자비 합의 2026-09-16): 멱등성 확인 -> 중복
-- 적립 가드 -> Balance 증가 -> Stream 발행 -> 성공 시에만 멱등 결과 저장. XADD 실패
-- 시 Balance와 가드 모두 보상한다 — 가드를 안 풀면 실제로는 적립되지 않았는데도
-- 오늘 하루 이 미션을 영원히 다시 받을 수 없게 된다.
--
-- KEYS[1] = idem:mission:{requestId}                                        String(JSON, TTL 24h, FR-P1-017)
-- KEYS[2] = mission:earn-guard:{userId}:{missionType}:{creatorId}:{yyyymmdd} String(SETNX, FR-P2-006)
-- KEYS[3] = ticket:balance:{creatorId}:{userId}                             String(integer)
--
-- ARGV[1] = amount
-- ARGV[2] = fingerprint      (TicketEarnServiceImpl이 userId+creatorId+missionType+
--                             missionId+periodKey+missionKey+amount로 계산)
-- ARGV[3] = streamKey        (예: stream:ticket-earned)
-- ARGV[4] = idemTtlSeconds   (FR-P1-017 확정: 24시간 = 86400)
-- ARGV[5] = guardTtlSeconds  (가드 키 만료. 명세에 없어 방어적으로 2일 = 172800으로 설정)
-- ARGV[6] = requestId
-- ARGV[7] = userId
-- ARGV[8] = creatorId
-- ARGV[9] = missionType
-- ARGV[10] = missionId
-- ARGV[11] = periodKey
-- ARGV[12] = missionKey
--
-- 반환: { resultCode, ...옵션 필드 } (EarnResultCode 6종 중 4종은 이 스크립트가 반환)
--   ALREADY_PROCESSED   -- 동일 requestId, 동일 fingerprint -> 기존 결과 재반환
--   REQUEST_ID_CONFLICT -- 동일 requestId, 다른 fingerprint
--   DUPLICATE_MISSION   -- 새 requestId, 이미 완료된 미션(가드 SETNX 실패)
--   EARN_ACCEPTED       -- { 'EARN_ACCEPTED', streamId, 적립후잔액 }
-- (EARN_PROCESSING_FAILED/EARN_STATUS_UNKNOWN은 스크립트가 아니라 호출측 Java가 매핑한다)

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

-- 1) 멱등성 확인 (entry-spend.lua의 issue #36 결정과 동일하게 가드보다 먼저 수행)
local stored = redis.call('GET', idemKey)
if stored then
    local parsed = cjson.decode(stored)
    if parsed.fingerprint == fingerprint then
        local result = parsed.result
        result[1] = 'ALREADY_PROCESSED'
        return result
    else
        return { 'REQUEST_ID_CONFLICT' }
    end
end

-- 2) 중복 적립 가드 (Business Key: userId+creatorId+missionId+periodKey, FR-P2-006)
local guardAcquired = redis.call('SETNX', guardKey, requestId)
if guardAcquired == 0 then
    return { 'DUPLICATE_MISSION' }
end
redis.call('EXPIRE', guardKey, guardTtl)

-- 3) Balance 증가 + Stream 발행
local newBalance = redis.call('INCRBY', balanceKey, amount)

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
    redis.call('DECRBY', balanceKey, amount)
    redis.call('DEL', guardKey)
    return redis.error_reply('XADD_FAILED: ' .. streamId.err)
end

local result = { 'EARN_ACCEPTED', streamId, tostring(newBalance) }
redis.call('SET', idemKey, cjson.encode({ fingerprint = fingerprint, result = result }), 'EX', idemTtl)

return result

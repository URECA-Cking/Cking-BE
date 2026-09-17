-- EARN(적립) 요청의 원자적 처리 — 멱등성 확인 + 중복 적립 가드 +
-- Balance 증가 + Stream 발행을 하나의 스크립트에서 원자적으로 수행한다 (FR-P2-006/008).
--
-- 순서(entry-spend.lua와 동일 원칙): 멱등성 확인 -> 중복
-- 적립 가드 -> Balance 증가 -> Stream 발행 -> 성공 시에만 멱등 결과 저장. XADD 실패
-- 시 Balance와 가드 모두 보상한다 — 가드를 안 풀면 실제로는 적립되지 않았는데도
-- 오늘 하루 이 미션을 영원히 다시 받을 수 없게 된다. Balance 보상은 원래 키가
-- 있었는지(EXISTS)에 따라 DECRBY/DEL을 구분한다 — 원래 없던 키를 0으로 남기면
-- entry-spend.lua가 구분하는 "키 없음(BALANCE_NOT_LOADED)"과 "0"이 뒤섞인다(§2.4).
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
-- ARGV[5] = guardTtlSeconds  (가드 키 만료. 팀 확정 25시간 = 90000 — 키에 yyyyMMdd가
--                             포함돼 자정 지나면 자연 만료되지만, 서버 시간대 오차 대비
--                             24시간+1시간 여유를 둔다. PR #63 리뷰 반영)
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
--                           (idem 히트, 또는 idem 저장 실패 후 가드로 복구)
--   REQUEST_ID_CONFLICT -- 동일 requestId, 다른 fingerprint (idem 또는 가드 기준)
--   DUPLICATE_MISSION   -- 다른 requestId, 이미 완료된 미션(가드 값의 requestId 불일치)
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

-- 2) 중복 적립 가드 (userId+missionType+creatorId+yyyyMMdd, FR-P2-006)
-- EARN API Business Key(userId+creatorId+missionId+periodKey, FR-P2-008)와는
-- 다른 레이어의 별도 키다 - 임의로 통합하지 않는다(취합v1.5.4 §4.2).
-- 값에 requestId뿐 아니라 fingerprint까지 저장해, idem 저장이 실패해도 가드가
-- 보조 멱등성 장치 역할을 한다(PR #63 리뷰) - 동일 requestId+fingerprint 재시도는
-- ALREADY_PROCESSED로, requestId만 같고 fingerprint가 다르면 REQUEST_ID_CONFLICT로,
-- requestId 자체가 다르면 진짜 DUPLICATE_MISSION으로 구분한다.
local guardValue = requestId .. ':' .. fingerprint
local guardAcquired = redis.call('SET', guardKey, guardValue, 'NX', 'EX', guardTtl)
if not guardAcquired then
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
-- BALANCE_NOT_LOADED 반환)의 명시적 예외로 확정했다. Redis 재기동/eviction으로
-- 기존 유저의 키가 사라진 경우도 이 경로를 타 신규 유저처럼 0에서 재생성될 수
-- 있으나, 이는 §2.4가 받아들인 트레이드오프이며 §13.2 정합성 배치가 뒤늦게 보정한다.
local balanceExisted = redis.call('EXISTS', balanceKey) == 1
local newBalance = redis.pcall('INCRBY', balanceKey, amount)

if type(newBalance) == 'table' and newBalance.err then
    redis.call('DEL', guardKey)
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
    if balanceExisted then
        redis.call('DECRBY', balanceKey, amount)
    else
        redis.call('DEL', balanceKey)
    end
    redis.call('DEL', guardKey)
    return redis.error_reply('XADD_FAILED: ' .. streamId.err)
end

local result = { 'EARN_ACCEPTED', streamId, tostring(newBalance) }

-- idem 저장은 정합성 백스톱이 아니라 성능 최적화용 캐시다(FR-P1-017) - 여기서
-- 실패해도 Balance 증가와 Stream 발행은 이미 끝난 정상 처리이므로 pcall로 감싸
-- 무시하고 EARN_ACCEPTED를 그대로 반환한다. 실패 시 idem이 비어도 가드 값에
-- fingerprint까지 있어 같은 requestId 재시도는 위 2)에서 ALREADY_PROCESSED로
-- 복구된다(PR #63 리뷰).
redis.pcall('SET', idemKey, cjson.encode({ fingerprint = fingerprint, result = result }), 'EX', idemTtl)

return result

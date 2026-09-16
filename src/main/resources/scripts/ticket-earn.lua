-- EARN(적립) 요청의 원자적 처리 — Balance 증가 + Stream 발행만 담당한다.
--
-- Business Key(userId+creatorId+missionId+periodKey) 중복판정 가드
-- (mission:earn-guard:{userId}:{missionType}:{creatorId}:{yyyymmdd} SETNX)는
-- 이 스크립트 범위 밖이다 — 자비님(이슈 #30)이 별도로 처리한 뒤 이 스크립트를
-- 호출할지 여부를 결정한다. 그래서 이 스크립트는 ALREADY_PROCESSED/
-- DUPLICATE_MISSION/REQUEST_ID_CONFLICT를 반환하지 않는다 — 그 판정을 하려면
-- 가드가 있어야 하는데 없기 때문이다.
--
-- KEYS[1] = ticket:balance:{creatorId}:{userId}   String(integer)
--
-- ARGV[1] = amount
-- ARGV[2] = streamKey      (예: stream:ticket-earned)
-- ARGV[3] = requestId
-- ARGV[4] = userId
-- ARGV[5] = creatorId
-- ARGV[6] = missionType
-- ARGV[7] = missionId
-- ARGV[8] = periodKey
-- ARGV[9] = missionKey
--
-- 반환: { 'EARN_ACCEPTED', streamId, 적립후잔액 }
-- (EARN_PROCESSING_FAILED는 스크립트가 아니라 호출측 Java가 예외를 잡아 매핑한다)

local balanceKey = KEYS[1]

local amount      = tonumber(ARGV[1])
local streamKey   = ARGV[2]
local requestId   = ARGV[3]
local userId      = ARGV[4]
local creatorId   = ARGV[5]
local missionType = ARGV[6]
local missionId   = ARGV[7]
local periodKey   = ARGV[8]
local missionKey  = ARGV[9]

-- Balance 키가 없으면 0에서 시작(INCRBY가 자동 생성) — SPEND와 달리 EARN은
-- 응모권이 처음 생기는 지점이라 BALANCE_NOT_LOADED를 별도로 두지 않는다.
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
    return redis.error_reply('XADD_FAILED: ' .. streamId.err)
end

return { 'EARN_ACCEPTED', streamId, tostring(newBalance) }

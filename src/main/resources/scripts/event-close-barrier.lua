-- 이벤트 마감 시점에 신규 응모 Gate를 차단하고, 같은 Stream에 EVENT_ENTRY_CLOSED
-- barrier 메시지를 XADD해 그 메시지의 Stream ID를 cutoff로 원자적으로 확정한다.
-- 이미 확정된 이벤트가 재시도(스케줄러 재실행·서버 재기동)로 다시 호출돼도
-- 새 barrier를 추가하지 않고 기존 cutoff 값을 그대로 반환한다(멱등).
--
-- 새 cutoff를 확정할 때만 실시간 응모 현황 집계 키(FR-P2-045~050)에 24시간 만료를 건다 -
-- 마감 이후에는 신규 증가가 없으므로 조회는 DB 집계로 넘어가면 되고, 키를 영구히 남기지
-- 않는다. 기존 cutoff를 그대로 반환하는 재시도 경로에서는 이미 만료가 걸려 있으므로 다시
-- 걸지 않는다.
--
-- KEYS[1] = event:status:{eventId}       String, entry-spend.lua Gate가 읽는 키. OPEN이 아니면 차단.
-- KEYS[2] = event:cutoff:{eventId}       String, 확정된 cutoff streamId 저장소.
-- KEYS[3] = stream:ticket-deducted       barrier 메시지를 XADD할 응모 Stream.
-- KEYS[4] = event:entry-total:{eventId}  String, 실시간 응모 현황 누적 응모권 집계.
-- KEYS[5] = event:entrants:{eventId}     Hash, 실시간 응모 현황 참여자별 집계.
--
-- ARGV[1] = 마감 시 Gate에 채울 값 (entry-spend.lua는 'OPEN' 문자열만 통과시키므로 그 외 아무 값)
-- ARGV[2] = eventId (barrier 메시지 필드)
-- ARGV[3] = 집계 키 만료 밀리초 (예: 86400000 = 24시간)
--
-- 반환: cutoff streamId (String) = barrier 메시지의 Stream ID

local statusKey = KEYS[1]
local cutoffKey = KEYS[2]
local streamKey = KEYS[3]
local entryTotalKey = KEYS[4]
local entrantsKey = KEYS[5]
local closedGateValue = ARGV[1]
local eventId = ARGV[2]
local aggregateTtlMillis = ARGV[3]

local existingCutoff = redis.call('GET', cutoffKey)
if existingCutoff then
    return existingCutoff
end

redis.call('SET', statusKey, closedGateValue)

local cutoff = redis.call('XADD', streamKey, '*', 'type', 'EVENT_ENTRY_CLOSED', 'eventId', eventId)

redis.call('SET', cutoffKey, cutoff)

-- 집계는 표시용이라 만료 실패가 마감 자체를 막으면 안 되므로 pcall로 격리한다.
redis.pcall('PEXPIRE', entryTotalKey, aggregateTtlMillis)
redis.pcall('PEXPIRE', entrantsKey, aggregateTtlMillis)

return cutoff

-- 이벤트 마감 시점에 신규 응모 Gate를 차단하고 cutoff(응모 Stream의 마지막 ID)를
-- 원자적으로 확정한다. 이미 확정된 이벤트가 재시도(스케줄러 재실행·서버 재기동)로
-- 다시 호출돼도 새 cutoff를 만들지 않고 기존 값을 그대로 반환한다(멱등).
--
-- KEYS[1] = event:status:{eventId}    String, entry-spend.lua Gate가 읽는 키. OPEN이 아니면 차단.
-- KEYS[2] = event:cutoff:{eventId}    String, 확정된 cutoff streamId 저장소.
-- KEYS[3] = stream:ticket-deducted    확정 시점의 마지막 streamId를 읽어올 응모 Stream.
--
-- ARGV[1] = 마감 시 Gate에 채울 값 (entry-spend.lua는 'OPEN' 문자열만 통과시키므로 그 외 아무 값)
--
-- 반환: cutoff streamId (String)

local statusKey = KEYS[1]
local cutoffKey = KEYS[2]
local streamKey = KEYS[3]
local closedGateValue = ARGV[1]

local existingCutoff = redis.call('GET', cutoffKey)
if existingCutoff then
    return existingCutoff
end

redis.call('SET', statusKey, closedGateValue)

local last = redis.call('XREVRANGE', streamKey, '+', '-', 'COUNT', '1')
local cutoff
if #last == 0 then
    cutoff = '0-0'
else
    cutoff = last[1][1]
end

redis.call('SET', cutoffKey, cutoff)

return cutoff

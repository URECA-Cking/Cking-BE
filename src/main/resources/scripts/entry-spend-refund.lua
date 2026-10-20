-- 이미 DB에 반영된 requestId를 응모 Lua가 다시 차감했을 때(idem·guard가 만료된 뒤의 재시도) 그 차감을 되돌리고
-- 메시지를 ACK한다. 되돌리기와 ACK를 한 스크립트로 묶어, 되돌린 뒤 ACK가 실패해 다시 되돌리는 일을 막는다(#673).
-- 수동 보정 락이 있으면 되돌리지 않고 PEL에 남긴다. 먼저 XACK해서 이미 ACK된 메시지면(회수로 두 소비자가 같은
-- 메시지를 처리한 경우) 되돌리지 않는다. 잔액 키가 없으면 다음 적재가 DB 값으로 채우므로 ACK만 한다.
-- 실시간 집계는 두 키가 모두 있고 이번 차감분 이상일 때만 줄인다.
--
-- KEYS[1] = 잔액 키(ticket:balance:{creatorId}:{userId} 또는 ticket:balance:common:{userId})
-- KEYS[2] = 수동 보정 락 키(ticket:maint:...)
-- KEYS[3] = event:entry-total:{eventId}
-- KEYS[4] = event:entrants:{eventId}
-- KEYS[5] = 응모 Stream 키
--
-- ARGV[1] = ticketCount
-- ARGV[2] = userId
-- ARGV[3] = Consumer Group
-- ARGV[4] = 메시지 ID
--
-- 반환: REFUNDED(되돌리고 ACK) | BALANCE_NOT_LOADED(잔액 키가 없어 ACK만) | ALREADY_ACKED(다른 소비자가 처리함)
--       | MAINTENANCE(아무것도 하지 않음)

if redis.call('EXISTS', KEYS[2]) == 1 then
    return 'MAINTENANCE'
end
if redis.call('XACK', KEYS[5], ARGV[3], ARGV[4]) == 0 then
    return 'ALREADY_ACKED'
end

local ticketCount = tonumber(ARGV[1])
local result = 'BALANCE_NOT_LOADED'
if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('INCRBY', KEYS[1], ticketCount)
    result = 'REFUNDED'
end

local total = tonumber(redis.call('GET', KEYS[3]) or '0')
local entrant = tonumber(redis.call('HGET', KEYS[4], ARGV[2]) or '0')
if total >= ticketCount and entrant >= ticketCount then
    redis.call('DECRBY', KEYS[3], ticketCount)
    if entrant == ticketCount then
        redis.call('HDEL', KEYS[4], ARGV[2])
    else
        redis.call('HINCRBY', KEYS[4], ARGV[2], -ticketCount)
    end
end

return result

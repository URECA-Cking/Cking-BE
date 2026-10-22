-- 이미 DB에 반영된 requestId를 응모 Lua가 다시 차감했을 때(idem·guard가 만료된 뒤의 재시도) 그 차감을 되돌리고
-- 메시지를 ACK한다. 되돌리기와 ACK를 한 스크립트로 묶어, 되돌린 뒤 ACK가 실패해 다시 되돌리는 일을 막는다(#673).
-- Redis Lua는 뒤 명령이 실패해도 앞의 쓰기를 되돌리지 않으므로 검사를 모두 끝낸 뒤 쓰고, XACK는 맨 마지막에 한다.
--   1. 수동 보정 락이 있으면 아무것도 하지 않고 PEL에 남긴다
--   2. 메시지가 PEL에 없으면(PEL 회수로 다른 소비자가 이미 처리) 되돌리지 않는다
--   3. 잔액 값이 정수가 아니면 아무것도 쓰지 않고 오류를 돌려 PEL에 남긴다. 회수 스케줄러가 다시 시도한다
--   4. 실시간 집계는 표시용이라(entry-spend.lua와 같은 원칙) 되돌리기를 막지 않는다. 두 키가 정상이고 이번 차감분
--      이상이면 줄이고, 그 밖의 상태면 entry-total을 지워 조회가 DB 집계로 넘어가게 한다
--   5. 잔액 키가 없으면 다음 적재가 DB 값으로 채우므로 잔액은 건드리지 않는다
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
-- 반환: REFUNDED(되돌리고 ACK) | BALANCE_NOT_LOADED(잔액 키가 없어 집계만 고치고 ACK) | ALREADY_ACKED(다른 소비자가
--       처리함) | MAINTENANCE(아무것도 하지 않음). 잔액 값이 정수가 아니면 오류

local function isInteger(value)
    return type(value) == 'string' and string.match(value, '^-?%d+$') ~= nil
end

if redis.call('EXISTS', KEYS[2]) == 1 then
    return 'MAINTENANCE'
end
if #redis.call('XPENDING', KEYS[5], ARGV[3], ARGV[4], ARGV[4], 1) == 0 then
    return 'ALREADY_ACKED'
end

local ticketCount = tonumber(ARGV[1])

local balanceLoaded = redis.call('EXISTS', KEYS[1]) == 1
if balanceLoaded and not isInteger(redis.call('GET', KEYS[1])) then
    return redis.error_reply('REFUND_BALANCE_INVALID: ' .. KEYS[1])
end

local aggregate = 'none'
if redis.call('TYPE', KEYS[3]).ok ~= 'none' then
    aggregate = 'reset'
    if redis.call('TYPE', KEYS[3]).ok == 'string' and redis.call('TYPE', KEYS[4]).ok == 'hash' then
        local total = redis.call('GET', KEYS[3])
        local entrant = redis.call('HGET', KEYS[4], ARGV[2])
        if isInteger(total) and isInteger(entrant)
                and tonumber(total) >= ticketCount and tonumber(entrant) >= ticketCount then
            aggregate = tonumber(entrant) == ticketCount and 'remove-entrant' or 'decrement'
        end
    end
end

if balanceLoaded then
    redis.call('INCRBY', KEYS[1], ticketCount)
end
if aggregate == 'reset' then
    redis.call('DEL', KEYS[3])
elseif aggregate ~= 'none' then
    redis.call('DECRBY', KEYS[3], ticketCount)
    if aggregate == 'remove-entrant' then
        redis.call('HDEL', KEYS[4], ARGV[2])
    else
        redis.call('HINCRBY', KEYS[4], ARGV[2], -ticketCount)
    end
end
redis.call('XACK', KEYS[5], ARGV[3], ARGV[4])

return balanceLoaded and 'REFUNDED' or 'BALANCE_NOT_LOADED'

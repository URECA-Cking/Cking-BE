-- 대기 메시지(PEL)가 없고 오래 쉰 소비자를 Consumer Group에서 지운다.
-- XGROUP DELCONSUMER는 그 소비자의 PEL 기록도 지우므로, 확인과 삭제를 한 스크립트로 묶어
-- 확인 뒤 삭제 전에 새 메시지가 배정되는 일을 막는다.
--
-- KEYS[1] = Stream 키
-- ARGV[1] = Consumer Group
-- ARGV[2] = 지울 최소 쉰 시간(ms). XINFO CONSUMERS의 idle(마지막 읽기 시도 뒤 지난 시간) 기준
-- ARGV[3..] = 지우지 않을 이름(이 서버의 소비자, 서버들이 함께 쓰는 재처리 소비자)
--
-- 반환: 지운 소비자 이름 목록. Stream이나 그룹이 없으면 빈 목록. 그 밖의 오류(Stream이 아닌 키 등)는 그대로 돌려
-- 스케줄러가 경고를 남기게 한다. 삼키면 정리가 멈춰도 알 수 없다.

if redis.call('EXISTS', KEYS[1]) == 0 then
    return {}
end
local groupExists = false
for _, group in ipairs(redis.call('XINFO', 'GROUPS', KEYS[1])) do
    for i = 1, #group, 2 do
        if group[i] == 'name' and group[i + 1] == ARGV[1] then
            groupExists = true
        end
    end
end
if not groupExists then
    return {}
end
local consumers = redis.call('XINFO', 'CONSUMERS', KEYS[1], ARGV[1])

local minIdle = tonumber(ARGV[2])
local keep = {}
for i = 3, #ARGV do
    keep[ARGV[i]] = true
end
local removed = {}
for _, consumer in ipairs(consumers) do
    local fields = {}
    for i = 1, #consumer, 2 do
        fields[consumer[i]] = consumer[i + 1]
    end
    if not keep[fields['name']]
            and tonumber(fields['pending']) == 0
            and tonumber(fields['idle']) >= minIdle then
        redis.call('XGROUP', 'DELCONSUMER', KEYS[1], ARGV[1], fields['name'])
        removed[#removed + 1] = fields['name']
    end
end
return removed

-- 모든 Consumer Group이 처리를 끝낸 앞쪽 메시지를 Stream에서 지운다.
-- 그룹마다 PEL이 있으면 PEL의 가장 오래된 ID, 없으면 last-delivered-id가 기준이고, 가장 뒤처진 그룹의 기준보다
-- 작은 ID만 지운다. PEL 메시지는 EventDrainChecker·UnappliedBalanceMessageChecker가 ID로 원본을 다시 읽어서 남긴다.
-- 기준은 줄어들지 않으므로 여러 서버가 동시에 실행해도 결과가 같다. MINID ~는 묶음 단위로 지우고 한 번에 최대
-- 10,000건(기본 LIMIT)까지만 지운다.
--
-- KEYS[1] = Stream 키
--
-- 반환: 지운 메시지 수. Stream이나 그룹이 없으면 0

local groups = redis.pcall('XINFO', 'GROUPS', KEYS[1])
if (type(groups) == 'table' and groups.err) or #groups == 0 then
    return 0
end

local function isBefore(left, right)
    local leftMs, leftSeq = string.match(left, '(%d+)-(%d+)')
    local rightMs, rightSeq = string.match(right, '(%d+)-(%d+)')
    if tonumber(leftMs) ~= tonumber(rightMs) then
        return tonumber(leftMs) < tonumber(rightMs)
    end
    return tonumber(leftSeq) < tonumber(rightSeq)
end

local minId
for _, group in ipairs(groups) do
    local fields = {}
    for i = 1, #group, 2 do
        fields[group[i]] = group[i + 1]
    end
    local point = fields['last-delivered-id']
    if tonumber(fields['pending']) > 0 then
        point = redis.call('XPENDING', KEYS[1], fields['name'])[2]
    end
    if minId == nil or isBefore(point, minId) then
        minId = point
    end
end

return redis.call('XTRIM', KEYS[1], 'MINID', '~', minId)

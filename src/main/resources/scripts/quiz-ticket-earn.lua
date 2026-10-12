-- Quiz 보상 EARN의 Redis 원자 처리(이슈 #533). 멱등 guard 확인 -> 수동 보정 락 확인 -> Balance 적재 확인
-- -> guard 예약 -> Balance 증가 -> Stream 발행 -> guard 완료 기록 순서로 수행한다.
-- Mission용 ticket-earn.lua와 같은 Balance·maintenance 키와 같은 stream:ticket-earned를 쓰지만, 멱등 guard는
-- rewardRequestId 하나를 기준으로 하는 Quiz 전용 키다.
--
-- KEYS[1] = idem:quiz-reward:{rewardRequestId}   String(JSON). 만료 없음
-- KEYS[2] = ticket:balance:{creatorId}:{memberId} String(integer)
-- KEYS[3] = ticket:maint:{creatorId}:{memberId}   존재 여부만 확인(issue #172)
--
-- ARGV[1]  = requestId(rewardRequestId, 소문자 정규 UUID)
-- ARGV[2]  = attemptId
-- ARGV[3]  = memberId
-- ARGV[4]  = creatorId
-- ARGV[5]  = correctCount
-- ARGV[6]  = amount (correctCount와 같아야 한다)
-- ARGV[7]  = fingerprintVersion (quiz-reward-fingerprint-v1)
-- ARGV[8]  = fingerprint (SHA-256 소문자 hex. 내용 검증은 호출측 QuizEarnEvent가 한다)
-- ARGV[9]  = executionToken
-- ARGV[10] = streamKey (예: stream:ticket-earned)
--
-- 반환: { resultCode, ...옵션 필드 }
--   EARN_ACCEPTED       { code, streamId, 적립후잔액(GET 원문), guardCompleted('1'|'0') } -- 이번 호출이 적립하고 발행했다
--   ALREADY_ACCEPTED    { code, streamId, 저장된 executionToken }  -- 같은 요청이 이미 적립·발행됐다(DB 반영과 무관)
--   REQUEST_ID_CONFLICT { code }                    -- 같은 requestId, 다른 fingerprint. 아무것도 바꾸지 않았다
--   BALANCE_MAINTENANCE { code }                    -- 수동 보정 락. 아무것도 바꾸지 않았다
--   BALANCE_NOT_LOADED  { code }                    -- Balance 키 없음. 0으로 만들지 않고 아무것도 바꾸지 않았다
--   INVALID_REQUEST     { code, 이유 }              -- 입력 계약 위반. 아무것도 바꾸지 않았다
--   NOT_EXECUTED        { code, 이유, Redis 오류 }  -- 변경 전 실패했거나, 쓰기 실패 후 모든 변경을 되돌린 것을
--                                                     확인했다. 이유: GUARD_RESERVE_FAILED, BALANCE_READ_FAILED,
--                                                     INCRBY_FAILED, XADD_FAILED
--   EARN_STATUS_UNKNOWN { code, 이유 }              -- 적립 여부를 이 스크립트가 확정할 수 없다
--
-- 스크립트는 원자적으로 실행되지만 중간 오류로 앞선 쓰기가 되돌려지지는 않는다. 그래서 첫 쓰기 이후의
-- 명령은 모두 pcall로 실행하고, 실패하면 직접 되돌린 뒤 되돌리기까지 성공했을 때만 NOT_EXECUTED를 반환한다.
--
-- 잔액은 Lua number로 바꾸지 않는다. Lua number는 double이라 2^53을 넘는 정수를 정확히 담지 못한다. 잔액은 GET 원문
-- 문자열로만 반환·비교하고, INCRBY·DECRBY에는 ARGV 문자열을 그대로 넘긴다.

local guardKey           = KEYS[1]
local balanceKey         = KEYS[2]
local maintenanceLockKey = KEYS[3]

local requestId          = ARGV[1]
local attemptId          = ARGV[2]
local memberId           = ARGV[3]
local creatorId          = ARGV[4]
local correctCount       = ARGV[5]
local amountArg          = ARGV[6]
local fingerprintVersion = ARGV[7]
local fingerprint        = ARGV[8]
local executionToken     = ARGV[9]
local streamKey          = ARGV[10]

local FINGERPRINT_VERSION = 'quiz-reward-fingerprint-v1'
local INT_MAX = 2147483647
local LONG_MAX = '9223372036854775807'

local function isError(reply)
    return type(reply) == 'table' and reply.err ~= nil
end

-- 1..Long.MAX_VALUE. 숫자로 바꾸지 않고 자릿수와 같은 자릿수끼리의 사전식 비교로 확인한다.
local function isPositiveLong(value)
    if type(value) ~= 'string' or string.match(value, '^[1-9]%d*$') == nil then
        return false
    end
    return #value < #LONG_MAX or (#value == #LONG_MAX and value <= LONG_MAX)
end

local function isCanonicalUuid(value)
    return type(value) == 'string'
        and string.match(value, '^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$') ~= nil
        and string.lower(value) == value
end

local function invalid(reason)
    return { 'INVALID_REQUEST', reason }
end

-- 0) 입력 계약. 어긋나면 아무것도 바꾸지 않는다.
if not isCanonicalUuid(requestId) then return invalid('requestId') end
if not isPositiveLong(attemptId) then return invalid('attemptId') end
if not isPositiveLong(memberId) then return invalid('memberId') end
if not isPositiveLong(creatorId) then return invalid('creatorId') end
if type(correctCount) ~= 'string' or #correctCount > 10 or string.match(correctCount, '^[1-9]%d*$') == nil
        or tonumber(correctCount) > INT_MAX then
    return invalid('correctCount')
end
if amountArg ~= correctCount then return invalid('amount') end
if fingerprintVersion ~= FINGERPRINT_VERSION then return invalid('fingerprintVersion') end
if type(fingerprint) ~= 'string' or #fingerprint ~= 64 or string.match(fingerprint, '^[0-9a-f]+$') == nil then
    return invalid('fingerprint')
end
if type(executionToken) ~= 'string' or #executionToken > 256 or string.find(executionToken, '%S') == nil then
    return invalid('executionToken')
end
if type(streamKey) ~= 'string' or streamKey == '' then return invalid('streamKey') end
-- 키와 인자가 같은 요청·회원·Creator를 가리키는지 확인한다(memberId·creatorId 순서 착오 방지).
if guardKey ~= 'idem:quiz-reward:' .. requestId then return invalid('guardKey') end
if balanceKey ~= 'ticket:balance:' .. creatorId .. ':' .. memberId then return invalid('balanceKey') end
if maintenanceLockKey ~= 'ticket:maint:' .. creatorId .. ':' .. memberId then return invalid('maintenanceKey') end

-- 1) 멱등 guard. 같은 rewardRequestId는 여기서 끝나며 Balance를 다시 바꾸지 않는다.
local stored = redis.call('GET', guardKey)
if stored then
    local decoded, parsed = pcall(cjson.decode, stored)
    if not decoded or type(parsed) ~= 'table' then
        return { 'EARN_STATUS_UNKNOWN', 'GUARD_UNREADABLE' }
    end
    if parsed.fingerprint ~= fingerprint then
        return { 'REQUEST_ID_CONFLICT' }
    end
    if parsed.status == 'COMPLETED' and type(parsed.streamId) == 'string' then
        return { 'ALREADY_ACCEPTED', parsed.streamId, parsed.executionToken }
    end
    -- PROCESSING이 남아 있으면 이전 실행이 완료 기록 또는 되돌리기에 실패한 것이다. 다시 적립하지 않는다.
    return { 'EARN_STATUS_UNKNOWN', 'GUARD_' .. tostring(parsed.status) }
end

-- 2) 수동 보정 락. 기존 EARN과 같이 신규 요청에만 적용한다.
if redis.call('EXISTS', maintenanceLockKey) == 1 then
    return { 'BALANCE_MAINTENANCE' }
end

-- 3) Balance 키가 없으면 0에서 시작하지 않는다. 적재(TicketBalanceKeyLoader)는 호출측 책임이다.
if redis.call('EXISTS', balanceKey) == 0 then
    return { 'BALANCE_NOT_LOADED' }
end

-- 변경 전 잔액 원문. XADD 실패 보상 후 정확히 같은 값으로 돌아왔는지 이 문자열로 확인한다.
local balanceBefore = redis.pcall('GET', balanceKey)
if isError(balanceBefore) then
    return { 'NOT_EXECUTED', 'BALANCE_READ_FAILED', balanceBefore.err }
end
if type(balanceBefore) ~= 'string' then
    return { 'NOT_EXECUTED', 'BALANCE_READ_FAILED', 'balance key disappeared' }
end

-- 4) guard 예약. 이후 실패는 이 예약까지 지워야 NOT_EXECUTED다.
local processing = cjson.encode({
    status = 'PROCESSING', fingerprint = fingerprint, executionToken = executionToken, attemptId = attemptId })
local reserved = redis.pcall('SET', guardKey, processing, 'NX')
if isError(reserved) then
    return { 'NOT_EXECUTED', 'GUARD_RESERVE_FAILED', reserved.err }
end
if not reserved then
    return { 'EARN_STATUS_UNKNOWN', 'GUARD_RESERVE_CONFLICT' }
end

local function releaseGuard()
    return not isError(redis.pcall('DEL', guardKey))
end

-- 5) Balance 증가
local incremented = redis.pcall('INCRBY', balanceKey, amountArg)
if isError(incremented) then
    if releaseGuard() then
        return { 'NOT_EXECUTED', 'INCRBY_FAILED', incremented.err }
    end
    return { 'EARN_STATUS_UNKNOWN', 'INCRBY_FAILED_GUARD_LEFT' }
end

-- 적립 후 잔액 원문. INCRBY 응답(Lua number)은 큰 값에서 부정확할 수 있어 쓰지 않는다.
local balanceAfter = redis.pcall('GET', balanceKey)

-- 6) Stream 발행. 필드는 QuizEarnEvent.toStreamFields()와 같은 이름·순서다.
local streamId = redis.pcall('XADD', streamKey, '*',
    'sourceType', 'QUIZ',
    'payloadVersion', '1',
    'requestId', requestId,
    'attemptId', attemptId,
    'memberId', memberId,
    'userId', memberId,
    'creatorId', creatorId,
    'correctCount', correctCount,
    'amount', amountArg,
    'fingerprintVersion', fingerprintVersion,
    'fingerprint', fingerprint,
    'executionToken', executionToken)

if isError(streamId) then
    -- 스크립트가 원자적이라 INCRBY 이후 다른 명령이 끼지 않았다. 되돌린 뒤 GET 원문이 변경 전 원문과 정확히
    -- 같을 때만 복원으로 본다. 같지 않으면 복원을 확인할 수 없으므로 재시도 가능으로 분류하지 않는다.
    local decremented = redis.pcall('DECRBY', balanceKey, amountArg)
    if isError(decremented) then
        return { 'EARN_STATUS_UNKNOWN', 'XADD_FAILED_COMPENSATION_FAILED' }
    end
    local restored = redis.pcall('GET', balanceKey)
    if isError(restored) or restored ~= balanceBefore then
        return { 'EARN_STATUS_UNKNOWN', 'XADD_FAILED_COMPENSATION_UNVERIFIED' }
    end
    if not releaseGuard() then
        return { 'EARN_STATUS_UNKNOWN', 'XADD_FAILED_GUARD_LEFT' }
    end
    return { 'NOT_EXECUTED', 'XADD_FAILED', streamId.err }
end

-- 7) guard 완료 기록. 실패해도 적립·발행은 끝났으므로 성공으로 반환하고, guard는 PROCESSING으로 남아
-- 이후 같은 요청이 UNKNOWN으로 멈춘다(다시 적립하지 않는다).
local completed = redis.pcall('SET', guardKey, cjson.encode({
    status = 'COMPLETED', fingerprint = fingerprint, executionToken = executionToken, attemptId = attemptId,
    streamId = streamId }))

if isError(balanceAfter) or type(balanceAfter) ~= 'string' then
    -- 적립·발행은 끝났지만 잔액을 알려줄 수 없다. 성공으로 단정하지 않는다(guard가 COMPLETED면 이후 중복으로 답한다).
    return { 'EARN_STATUS_UNKNOWN', 'BALANCE_AFTER_UNREADABLE' }
end
return { 'EARN_ACCEPTED', streamId, balanceAfter, isError(completed) and '0' or '1' }

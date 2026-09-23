-- DB의 OPEN 이벤트를 응모 Gate로 적재한다. 마감 barrier(event-close-barrier.lua)와 같은
-- Redis 원자 단위에서 조율되므로, 마감이 시작된 이벤트의 Gate를 stale한 DB 조회값으로 다시 열지 않는다.
-- Gate가 처음 열리는 순간에만 실시간 응모 현황 집계 키(FR-P2-045~050)도 DB 집계값으로 함께
-- 초기화한다 - 이미 Gate가 있으면(다른 인스턴스가 먼저 열었거나 정상 운영 중) 집계에는 손대지
-- 않는다("키 없음 = 0 금지" 원칙: 집계 키가 없는 상태로 남아 있으면 조회는 DB로 대체된다).
--
-- KEYS[1] = event:status:{eventId}
-- KEYS[2] = event:endat:{eventId}
-- KEYS[3] = event:cutoff:{eventId}       barrier가 확정했으면 존재. 있으면 마감이 시작된 이벤트다.
-- KEYS[4] = event:entry-total:{eventId}  누적 사용 응모권 수
-- KEYS[5] = event:entrants:{eventId}     Hash(userId -> 사용 응모권 수)
--
-- ARGV[1] = endAt (epoch millis)
-- ARGV[2] = DB 기준 누적 사용 응모권 수 (Gate가 이미 있으면 사용되지 않음)
-- ARGV[3..] = (userId, ticketCount) 쌍 (Gate가 이미 있으면 사용되지 않음)
--
-- 반환: 1 = 적재 시도(이미 있는 키는 덮어쓰지 않음), 0 = 마감 시작으로 건너뜀

if redis.call('EXISTS', KEYS[3]) == 1 then
    return 0
end

if redis.call('EXISTS', KEYS[1]) == 1 then
    -- Gate는 이미 열려 있다 - endAt만 복원 시도하고(이미 있으면 NX가 무시) 집계는 건드리지 않는다.
    redis.call('SET', KEYS[2], ARGV[1], 'NX')
    return 1
end

-- 집계 키는 status와 별도로 존재 여부를 확인하되, entry-total·entrants 둘은 항상 같은 단위로
-- 취급한다(둘 다 없을 때만 함께 초기화). 만약 한쪽만 살아 있으면(예: 부분 eviction) 없는
-- 쪽만 지금 DB 스냅샷으로 채워 넣지 않는다 - 살아 있는 쪽은 Consumer가 아직 DB에 반영하지
-- 못한 증가분을 포함한 최신값인데, 없는 쪽을 이번 DB 스냅샷(그보다 과거 시점)으로 채우면
-- 두 키가 서로 다른 시점 값이 되어 어긋난다. 이미 있는 집계 키는 절대 건드리지 않는다.
if redis.call('EXISTS', KEYS[4]) == 0 and redis.call('EXISTS', KEYS[5]) == 0 then
    redis.call('SET', KEYS[4], ARGV[2])
    for i = 3, #ARGV, 2 do
        redis.call('HSET', KEYS[5], ARGV[i], ARGV[i + 1])
    end
end

-- entry-spend.lua는 status·endat 두 키가 모두 있어야 Gate로 인정한다. status를 마지막에 써서
-- 반쯤 열린 상태를 막는다. 집계 키는 그 이전에 채워 넣어 Gate가 열리자마자 조회 가능하게 한다.
redis.call('SET', KEYS[2], ARGV[1], 'NX')
redis.call('SET', KEYS[1], 'OPEN', 'NX')
return 1

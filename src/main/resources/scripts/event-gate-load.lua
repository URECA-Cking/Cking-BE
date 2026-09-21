-- DB의 OPEN 이벤트를 응모 Gate로 적재한다. 마감 barrier(event-close-barrier.lua)와 같은
-- Redis 원자 단위에서 조율되므로, 마감이 시작된 이벤트의 Gate를 stale한 DB 조회값으로 다시 열지 않는다.
--
-- KEYS[1] = event:status:{eventId}
-- KEYS[2] = event:endat:{eventId}
-- KEYS[3] = event:cutoff:{eventId}    barrier가 확정했으면 존재. 있으면 마감이 시작된 이벤트다.
--
-- ARGV[1] = endAt (epoch millis)
--
-- 반환: 1 = 적재 시도(이미 있는 키는 덮어쓰지 않음), 0 = 마감 시작으로 건너뜀

if redis.call('EXISTS', KEYS[3]) == 1 then
    return 0
end

-- entry-spend.lua는 두 키가 모두 있어야 Gate로 인정한다. status를 마지막에 써서 반쯤 열린 상태를 막는다.
redis.call('SET', KEYS[2], ARGV[1], 'NX')
redis.call('SET', KEYS[1], 'OPEN', 'NX')
return 1

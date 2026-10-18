-- 마감 직전 몰림 측정용 이벤트. seed.sql 뒤, closing-burst.js를 시작하기 직전에 실행한다.
--   SET @closing_creator = 1, @closing_minutes = 10;
-- @closing_minutes 뒤에 끝나는 이벤트를 만들고 그 ID를 출력한다. 응모권은 seed.sql의 크리에이터 잔액을 쓴다.

-- end_at은 UTC로 저장하므로 UNIX_TIMESTAMP도 UTC 기준으로 계산한다.
SET time_zone = '+00:00';

INSERT INTO event (creator_id, title, start_at, end_at, winner_count, draw_method, status, request_id, created_by)
SELECT creator_id, CONCAT('lt-closing-', creator_id, '-', UNIX_TIMESTAMP()),
       UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE, UTC_TIMESTAMP(6) + INTERVAL @closing_minutes MINUTE,
       10, 'WEIGHTED', 'OPEN', UUID(), member_id
FROM creator
WHERE creator_id = @closing_creator;

SELECT event_id AS closing_event_id, creator_id, end_at, FLOOR(UNIX_TIMESTAMP(end_at) * 1000) AS end_at_ms
FROM event
WHERE event_id = LAST_INSERT_ID();

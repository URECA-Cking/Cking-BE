-- 부하 테스트 시딩. Flyway가 적용된 빈 DB에 한 번 실행한다.
-- 실행 전에 같은 세션에서 규모를 지정한다.
--   SET @creators = 5, @members = 1000, @start_balance = 100, @entry_hours = 24,
--       @race_members = 40, @race_balance = 11;
-- 회원 ID: 1..@creators는 크리에이터 본인, 그 뒤 @members명이 응모하는 회원이다.
-- 적립 경로는 측정 대상이 아니라서 응모권은 시작 잔액으로만 준다.

SET SESSION cte_max_recursion_depth = 10000000;
SET @total_members = @creators + @members;

-- 응모 전에 닉네임 설정 여부를 본다(MemberProfileGuard). 닉네임 규칙을 지키고 ID로 고유하게 만든다.
INSERT INTO member (member_id, name, nickname, nickname_normalized, role, onboarding_completed)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @total_members)
SELECT n, CONCAT('lt-member-', n), CONCAT('lt-', n), CONCAT('lt-', n), 'USER', 1 FROM seq;

-- 추첨·검증 API를 부를 관리자. 관리자 API는 회원 역할이 ADMIN인지만 본다(MemberQueryService.validateAdmin).
SET @admin_member_id = @total_members + 1;
INSERT INTO member (member_id, name, nickname, nickname_normalized, role, onboarding_completed)
VALUES (@admin_member_id, 'lt-admin', CONCAT('lt-', @admin_member_id), CONCAT('lt-', @admin_member_id), 'ADMIN', 1);

INSERT INTO creator (creator_id, member_id, name, activity_name, activity_name_normalized, activity_name_configured)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @creators)
SELECT n, n, CONCAT('lt-creator-', n), CONCAT('lt-creator-', n), CONCAT('lt-creator-', n), 1 FROM seq;

-- 크리에이터마다 응모용 이벤트 하나. 측정 중에 마감되지 않도록 @entry_hours 뒤에 끝난다.
INSERT INTO event (event_id, creator_id, title, start_at, end_at, winner_count, draw_method, status,
                   request_id, created_by)
SELECT creator_id, creator_id, CONCAT('lt-entry-', creator_id),
       UTC_TIMESTAMP(6) - INTERVAL 1 HOUR, UTC_TIMESTAMP(6) + INTERVAL @entry_hours HOUR,
       1, 'WEIGHTED', 'OPEN', UUID(), member_id
FROM creator;

-- 동시 요청 측정(race.js)용 회원. 관리자 다음 ID부터 이어지고 크리에이터 1의 잔액만 가진다.
SET @race_first_member_id = @admin_member_id + 1;
INSERT INTO member (member_id, name, nickname, nickname_normalized, role, onboarding_completed)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @race_members)
SELECT @admin_member_id + n, CONCAT('lt-race-', n), CONCAT('lt-', @admin_member_id + n),
       CONCAT('lt-', @admin_member_id + n), 'USER', 1 FROM seq;

INSERT INTO user_ticket_balance (member_id, creator_id, balance)
SELECT member_id, 1, @race_balance FROM member WHERE name LIKE 'lt-race-%';

-- 시작 잔액과 같은 금액의 원장 행을 함께 넣어 잔액 = 원장 합계가 처음부터 맞게 한다.
INSERT INTO user_ticket_balance (member_id, creator_id, balance)
SELECT m.member_id, c.creator_id, @start_balance
FROM member m CROSS JOIN creator c
WHERE m.member_id BETWEEN @creators + 1 AND @total_members;

INSERT INTO ticket_ledger (member_id, creator_id, delta_amount, type, reason, balance_before, balance_after)
SELECT member_id, creator_id, balance, 'COMPENSATE', 'loadtest seed', 0, balance
FROM user_ticket_balance;

SELECT @admin_member_id AS admin_member_id, @race_first_member_id AS race_first_member_id,
       (SELECT COUNT(*) FROM member) AS members,
       (SELECT COUNT(*) FROM creator) AS creators,
       (SELECT COUNT(*) FROM event WHERE status = 'OPEN') AS open_events,
       (SELECT COUNT(*) FROM user_ticket_balance) AS balances,
       (SELECT SUM(delta_amount) FROM ticket_ledger) = (SELECT SUM(balance) FROM user_ticket_balance) AS ledger_matches;

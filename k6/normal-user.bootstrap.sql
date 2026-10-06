-- #434 전용 빈 격리 스키마에만 실행한다. 기존 cking DB에는 절대 실행하지 않는다.
-- Flyway 적용 후 정확히 한 번 실행한다. 모든 ID는 이 시나리오 전용 합성 ID다.
USE cking_abuse_k6_434;

INSERT INTO member (member_id, name, role) VALUES
  (101, 'k6-user-101', 'USER'), (102, 'k6-user-102', 'USER'),
  (103, 'k6-user-103', 'USER'), (104, 'k6-user-104', 'USER'),
  (105, 'k6-user-105', 'USER'), (201, 'k6-creator-201', 'USER'),
  (202, 'k6-creator-202', 'USER');

INSERT INTO creator (creator_id, member_id, name) VALUES
  (201, 201, 'k6-creator-201'), (202, 202, 'k6-creator-202');

INSERT INTO mission (mission_id, creator_id, type, reward_amount) VALUES
  (301, 201, 'LIKE', 1), (302, 202, 'LIKE', 1);

INSERT INTO event (event_id, creator_id, title, start_at, end_at,
                   winner_count, draw_method, status, request_id, created_by)
VALUES (401, 201, 'k6-normal-event',
        UTC_TIMESTAMP(6) - INTERVAL 1 HOUR,
        UTC_TIMESTAMP(6) + INTERVAL 1 DAY,
        1, 'WEIGHTED', 'OPEN', '00000000-0000-4000-8000-000000000401', 201);

INSERT INTO user_ticket_balance (member_id, creator_id, balance) VALUES
  (101, 201, 0), (102, 201, 2), (103, 201, 0),
  (104, 201, 0), (104, 202, 0);

-- 다중 응모의 사전 잔액도 Ledger 합계와 일치시킨다.
INSERT INTO ticket_ledger (member_id, creator_id, delta_amount, type, reason,
                           balance_before, balance_after)
VALUES (102, 201, 2, 'COMPENSATE', 'k6 #434 정상 다중 응모 초기 잔액', 0, 2);

INSERT INTO user_common_ticket_balance (member_id, balance) VALUES (105, 0);

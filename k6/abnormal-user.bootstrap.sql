-- #441: 새로 만든 빈 cking_abuse_k6_441 스키마에 Flyway 적용 후 한 번만 실행한다.
-- 기존 cking 스키마·정상군 #434 스키마에서 실행하면 안 된다.
USE cking_abuse_k6_441;

INSERT INTO member (member_id, name, role) VALUES
  (111, 'k6-abnormal-111', 'USER'), (112, 'k6-abnormal-112', 'USER'),
  (113, 'k6-abnormal-113', 'USER'), (114, 'k6-abnormal-114', 'USER'),
  (115, 'k6-abnormal-115', 'USER'), (116, 'k6-abnormal-116', 'USER'),
  (117, 'k6-abnormal-117', 'USER'), (211, 'k6-creator-211', 'USER'),
  (212, 'k6-creator-212', 'USER');

INSERT INTO creator (creator_id, member_id, name) VALUES
  (211, 211, 'k6-creator-211'), (212, 212, 'k6-creator-212');

INSERT INTO mission (mission_id, creator_id, type, reward_amount, active_to) VALUES
  (311, 211, 'LIKE', 1, NULL),
  (312, 211, 'SHARE', 1, NULL),
  (313, 212, 'LIKE', 1, UTC_TIMESTAMP(6) - INTERVAL 1 DAY);

INSERT INTO event (event_id, creator_id, title, start_at, end_at,
                   winner_count, draw_method, status, request_id, created_by)
VALUES (411, 211, 'k6-abnormal-event',
        UTC_TIMESTAMP(6) - INTERVAL 1 HOUR,
        UTC_TIMESTAMP(6) + INTERVAL 1 DAY,
        1, 'WEIGHTED', 'OPEN', '00000000-0000-4000-8000-000000000411', 211);

INSERT INTO user_ticket_balance (member_id, creator_id, balance) VALUES
  (111, 211, 0), (112, 211, 0), (113, 211, 12), (114, 211, 0),
  (115, 211, 0), (116, 211, 0), (117, 211, 0);

-- 사전 지급된 응모권도 Ledger 합계와 일치시킨다.
INSERT INTO ticket_ledger (member_id, creator_id, delta_amount, type, reason,
                           balance_before, balance_after)
VALUES (113, 211, 12, 'COMPENSATE', 'k6 #441 응모 반복 초기 잔액', 0, 12);

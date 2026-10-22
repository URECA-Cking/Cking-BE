-- Ticket 원장의 EARN 원인에 Quiz 보상 요청을 추가한다 (이슈 #533)
--
-- 기존 EARN은 mission_completion을 원인으로 가진다. Quiz 보상 EARN은 가짜 Mission 완료를 만들지 않고
-- ticket_quiz_reward_request를 원인으로 가진다. EARN은 둘 중 정확히 하나를 참조한다.
--
-- quiz_reward_request_id
--   uk_ledger_quiz_reward_request: Quiz 보상 요청 하나는 원장 한 줄로만 반영한다. 기존 uk_ledger_request(request_id)도 유지한다.
--   fk_ledger_quiz_reward_request: 원장의 요청 ID·회원·Creator가 Quiz 보상 요청과 같아야 한다. 참조 대상으로
--   uk_ticket_quiz_reward_ledger_ref를 둔다. quiz_reward_request_id가 NULL인 기존 행은 검사하지 않는다.
--
-- ck_ledger_type_source
--   EARN       : event_entry_id 없음, mission_completion_id와 quiz_reward_request_id 중 정확히 하나
--   SPEND      : 기존과 같고, quiz_reward_request_id 없음
--   COMPENSATE : 기존과 같다
--   기존 행은 quiz_reward_request_id가 NULL이고 EARN은 mission_completion_id가 있어 새 조건을 그대로 만족한다.
--   기존 데이터를 지우거나 고치지 않는다.
ALTER TABLE ticket_quiz_reward_request
    ADD CONSTRAINT uk_ticket_quiz_reward_ledger_ref
        UNIQUE (quiz_reward_request_id, reward_request_id, member_id, creator_id);

ALTER TABLE ticket_ledger
    ADD COLUMN quiz_reward_request_id BIGINT NULL COMMENT 'Quiz 보상 EARN 원인' AFTER mission_completion_id;

ALTER TABLE ticket_ledger
    ADD CONSTRAINT uk_ledger_quiz_reward_request UNIQUE (quiz_reward_request_id),
    ADD CONSTRAINT fk_ledger_quiz_reward_request
        FOREIGN KEY (quiz_reward_request_id, request_id, member_id, creator_id)
            REFERENCES ticket_quiz_reward_request (quiz_reward_request_id, reward_request_id, member_id, creator_id);

ALTER TABLE ticket_ledger DROP CHECK ck_ledger_type_source;

ALTER TABLE ticket_ledger
    ADD CONSTRAINT ck_ledger_type_source CHECK (
        (type = 'EARN' AND event_entry_id IS NULL
            AND ((mission_completion_id IS NOT NULL) <> (quiz_reward_request_id IS NOT NULL)))
     OR (type = 'SPEND' AND event_entry_id IS NOT NULL AND mission_completion_id IS NULL
            AND quiz_reward_request_id IS NULL)
     OR (type = 'COMPENSATE')
    );

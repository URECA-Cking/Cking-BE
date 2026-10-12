-- Quiz 보상 Ticket 지급 요청의 durable 접수 기록 (이슈 #533)
--
-- Fan Quiz 응시(attempt) 하나의 보상 요청 하나다. Mission 보상과 구분되는 원인이며 mission을 참조하지 않는다.
-- uk_ticket_quiz_reward_attempt: rewardRequestId가 달라도 같은 attempt의 보상은 한 번만 접수한다.
-- uk_ticket_quiz_reward_request: 같은 rewardRequestId는 한 요청이다. 같은 입력이면 기존 요청이고, 다르면 충돌이다.
--   같은 UUID는 전역 ticket_earn_request(reward_policy=QUIZ)에도 같은 Transaction에서 선점한다.
-- payload_fingerprint는 fingerprint_version 규칙으로 source·attemptId·memberId·creatorId·correctCount를 해시한 값이다.
--   rewardRequestId와 시각은 넣지 않는다.
-- amount는 지급할 Ticket 수이며 정답 1개당 1장이라 correct_count와 같다. 0 이하 보상은 접수하지 않는다.
-- attempt_id는 Quiz 응시 테이블이 아직 없어 FK를 두지 않는다. attempt 소유권과 채점 결과는 호출자가 보장한다.
-- status (지금은 RECEIVED 접수만 구현한다. 다른 상태로의 전이는 후속 단계에서 구현한다)
--   RECEIVED          : 접수했다. 아직 Redis EARN을 실행하지 않았다.
--   EXECUTING         : Redis EARN 실행을 시작했다. 결과는 아직 확인하지 않았다.
--   ACCEPTED          : Redis가 EARN을 받아들였다. Ticket 원장·잔액에는 아직 반영되지 않았을 수 있다.
--   APPLIED           : Ticket 원장·잔액에 반영했다. applied_at이 있다.
--   UNKNOWN           : 실행 결과를 확인할 수 없다. 같은 요청으로 처리 여부를 확인해야 한다.
--   RETRYABLE_FAILURE : 반영되지 않은 것이 확인된 실패다. 같은 요청으로 다시 시도할 수 있다.
--   어느 상태에서도 복구는 새 rewardRequestId를 만들지 않는다.
--
-- 기존 테이블은 바꾸지 않는다.
CREATE TABLE ticket_quiz_reward_request (
    quiz_reward_request_id BIGINT      NOT NULL AUTO_INCREMENT,
    reward_request_id      VARCHAR(36) NOT NULL,
    attempt_id             BIGINT      NOT NULL,
    member_id              BIGINT      NOT NULL,
    creator_id             BIGINT      NOT NULL,
    correct_count          INT         NOT NULL,
    amount                 BIGINT      NOT NULL,
    fingerprint_version    VARCHAR(40) NOT NULL,
    payload_fingerprint    CHAR(64)    NOT NULL,
    status                 VARCHAR(20) NOT NULL,
    created_at             DATETIME(6) NOT NULL,
    applied_at             DATETIME(6) NULL,
    PRIMARY KEY (quiz_reward_request_id),
    CONSTRAINT uk_ticket_quiz_reward_request UNIQUE (reward_request_id),
    CONSTRAINT uk_ticket_quiz_reward_attempt UNIQUE (attempt_id),
    CONSTRAINT fk_ticket_quiz_reward_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_ticket_quiz_reward_creator FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    CONSTRAINT ck_ticket_quiz_reward_attempt CHECK (attempt_id > 0),
    CONSTRAINT ck_ticket_quiz_reward_count CHECK (correct_count > 0),
    CONSTRAINT ck_ticket_quiz_reward_amount CHECK (amount = correct_count),
    CONSTRAINT ck_ticket_quiz_reward_status CHECK (status IN ('RECEIVED', 'EXECUTING', 'ACCEPTED', 'APPLIED',
        'UNKNOWN', 'RETRYABLE_FAILURE')),
    CONSTRAINT ck_ticket_quiz_reward_applied CHECK ((status = 'APPLIED') = (applied_at IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

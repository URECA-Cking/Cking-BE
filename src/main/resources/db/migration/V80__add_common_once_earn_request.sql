-- 공용 응모권 평생 1회 지급은 Redis 전에 회원·보상 종류를 영속 선점한다.
ALTER TABLE ticket_earn_request MODIFY COLUMN reward_policy VARCHAR(20) NOT NULL
    COMMENT 'DAILY, ONCE, COMMON, COMMON_ONCE, QUIZ';

CREATE TABLE ticket_common_once_earn_request (
    once_earn_request_id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(36) NOT NULL,
    member_id BIGINT NOT NULL,
    reward_type VARCHAR(50) NOT NULL,
    amount BIGINT NOT NULL,
    payload_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL COMMENT 'PENDING, ACCEPTED',
    created_at DATETIME(6) NOT NULL,
    accepted_at DATETIME(6) NULL,
    PRIMARY KEY (once_earn_request_id),
    CONSTRAINT uk_common_once_request UNIQUE (request_id),
    CONSTRAINT uk_common_once_business UNIQUE (member_id, reward_type),
    CONSTRAINT fk_common_once_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT ck_common_once_amount CHECK (amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE common_ticket_ledger
    ADD COLUMN once_earn_request_id BIGINT NULL COMMENT '공용 평생 1회 EARN 원인',
    ADD CONSTRAINT fk_common_ledger_once FOREIGN KEY (once_earn_request_id)
        REFERENCES ticket_common_once_earn_request (once_earn_request_id);

ALTER TABLE common_ticket_ledger DROP CHECK ck_common_ledger_type_source;
ALTER TABLE common_ticket_ledger ADD CONSTRAINT ck_common_ledger_type_source CHECK (
    (type = 'EARN' AND event_entry_id IS NULL AND
        ((mission_completion_id IS NOT NULL AND once_earn_request_id IS NULL) OR
         (mission_completion_id IS NULL AND once_earn_request_id IS NOT NULL)))
    OR (type = 'SPEND' AND event_entry_id IS NOT NULL AND mission_completion_id IS NULL
        AND once_earn_request_id IS NULL)
    OR (type = 'COMPENSATE' AND once_earn_request_id IS NULL)
);

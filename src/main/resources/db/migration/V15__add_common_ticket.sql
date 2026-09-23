-- 공용 응모권(이슈 #219): 크리에이터에 묶이지 않는 미션·잔액·Ledger.
-- 기존 mission/user_ticket_balance/ticket_ledger는 creator_id가 NOT NULL FK라
-- 공용 개념을 담을 수 없어 별도 테이블로 분리한다(공용은 크리에이터가 아님).

CREATE TABLE common_mission (
    mission_id    BIGINT      NOT NULL AUTO_INCREMENT,
    type          VARCHAR(30) NOT NULL COMMENT 'ATTENDANCE',
    reward_amount INT         NOT NULL,
    active_from   DATETIME(6) NULL,
    active_to     DATETIME(6) NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (mission_id),
    CONSTRAINT uk_common_mission_type UNIQUE (type),
    CONSTRAINT ck_common_mission_reward CHECK (reward_amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE common_mission_completion (
    completion_id       BIGINT      NOT NULL AUTO_INCREMENT,
    member_id           BIGINT      NOT NULL,
    mission_id          BIGINT      NOT NULL,
    period_key          VARCHAR(10) NOT NULL COMMENT 'YYYY-MM-DD',
    request_id          VARCHAR(36) NOT NULL COMMENT '멱등 요청 ID (UUID)',
    payload_fingerprint CHAR(64)    NOT NULL COMMENT 'EARN 요청 전체 payload의 SHA-256 hex',
    completed_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (completion_id),
    CONSTRAINT uk_common_completion_request UNIQUE (request_id),
    -- 명세 4.2 Business Key와 동일 원칙(크리에이터 축만 제외). 같은 날 중복 보상을 막는 최종 방어선.
    CONSTRAINT uk_common_completion_business UNIQUE (member_id, mission_id, period_key),
    CONSTRAINT fk_common_completion_member  FOREIGN KEY (member_id)  REFERENCES member (member_id),
    CONSTRAINT fk_common_completion_mission FOREIGN KEY (mission_id) REFERENCES common_mission (mission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE user_common_ticket_balance (
    member_id  BIGINT      NOT NULL,
    balance    BIGINT      NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (member_id),
    CONSTRAINT fk_common_balance_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT ck_common_balance_non_negative CHECK (balance >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE common_ticket_ledger (
    ledger_id             BIGINT       NOT NULL AUTO_INCREMENT,
    member_id             BIGINT       NOT NULL,
    event_entry_id        BIGINT       NULL COMMENT 'SPEND 원인(2조 구현 예정)',
    mission_completion_id BIGINT       NULL COMMENT 'EARN 원인',
    delta_amount          BIGINT       NOT NULL,
    type                  VARCHAR(30)  NOT NULL COMMENT 'EARN, SPEND, COMPENSATE',
    reason                VARCHAR(500) NULL,
    request_id            VARCHAR(36)  NULL COMMENT 'COMPENSATE는 요청 없이 배치가 기록하므로 NULL 허용',
    balance_before        BIGINT       NULL,
    balance_after         BIGINT       NULL,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (ledger_id),
    CONSTRAINT uk_common_ledger_request UNIQUE (request_id),
    CONSTRAINT fk_common_ledger_member     FOREIGN KEY (member_id)             REFERENCES member (member_id),
    CONSTRAINT fk_common_ledger_entry      FOREIGN KEY (event_entry_id)        REFERENCES event_entry (entry_id),
    CONSTRAINT fk_common_ledger_completion FOREIGN KEY (mission_completion_id) REFERENCES common_mission_completion (completion_id),
    CONSTRAINT ck_common_ledger_type_source CHECK (
        (type = 'EARN'       AND mission_completion_id IS NOT NULL AND event_entry_id IS NULL)
     OR (type = 'SPEND'      AND event_entry_id        IS NOT NULL AND mission_completion_id IS NULL)
     OR (type = 'COMPENSATE')
    ),
    INDEX idx_common_ledger_member (member_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 공용 미션은 크리에이터 승인 같은 자연스러운 생성 계기가 없는 단일 행이라
-- (유형당 하나, uk_common_mission_type) 초기화 서비스 대신 데이터로 시딩한다.
-- 보상 1장, 상시 활성 — 크리에이터별 기본 미션과 동일한 정책(이슈 #185).
INSERT INTO common_mission (type, reward_amount, active_from, active_to)
VALUES ('ATTENDANCE', 1, NULL, NULL);

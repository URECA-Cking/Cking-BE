-- 평생 1회 보상의 Redis 실행 전 durable claim이다.
CREATE TABLE ticket_once_earn_request (
    once_earn_request_id BIGINT       NOT NULL AUTO_INCREMENT,
    request_id           VARCHAR(36)  NOT NULL,
    member_id            BIGINT       NOT NULL,
    creator_id           BIGINT       NOT NULL,
    mission_id           BIGINT       NOT NULL,
    mission_type         VARCHAR(30)  NOT NULL,
    amount               BIGINT       NOT NULL,
    period_key           VARCHAR(10)  NOT NULL COMMENT '최초 요청 UTC 날짜, YYYY-MM-DD',
    payload_fingerprint  CHAR(64)     NOT NULL,
    status               VARCHAR(20)  NOT NULL COMMENT 'PENDING, ACCEPTED',
    created_at           DATETIME(6)  NOT NULL,
    accepted_at          DATETIME(6)  NULL,
    PRIMARY KEY (once_earn_request_id),
    CONSTRAINT uk_ticket_once_earn_request_id UNIQUE (request_id),
    CONSTRAINT uk_ticket_once_earn_business UNIQUE (member_id, creator_id, mission_id),
    CONSTRAINT fk_ticket_once_earn_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_ticket_once_earn_creator FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    CONSTRAINT fk_ticket_once_earn_mission FOREIGN KEY (mission_id) REFERENCES mission (mission_id),
    CONSTRAINT ck_ticket_once_earn_amount CHECK (amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- DAILY와 ONCE가 같은 request_id를 Redis 실행 전에 전역 선점하는 durable request다.
CREATE TABLE ticket_earn_request (
    earn_request_id       BIGINT       NOT NULL AUTO_INCREMENT,
    request_id            VARCHAR(36)  NOT NULL,
    payload_fingerprint   CHAR(64)     NOT NULL,
    reward_policy         VARCHAR(10)  NOT NULL COMMENT 'DAILY, ONCE',
    status                VARCHAR(20)  NOT NULL COMMENT 'PENDING, ACCEPTED',
    created_at            DATETIME(6)  NOT NULL,
    accepted_at           DATETIME(6)  NULL,
    PRIMARY KEY (earn_request_id),
    CONSTRAINT uk_ticket_earn_request_id UNIQUE (request_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

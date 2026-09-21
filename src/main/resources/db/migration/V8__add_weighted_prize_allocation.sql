-- 상품 확률 가중치는 응모자 선정 가중치와 분리해 Event -> Snapshot -> Winner로 보존한다.

CREATE TABLE event_prize (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    event_id           BIGINT       NOT NULL,
    prize_key          VARCHAR(100) NOT NULL,
    display_name       VARCHAR(200) NOT NULL,
    priority           INT          NOT NULL,
    probability_weight BIGINT       NOT NULL,
    quantity           INT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_event_prize_key UNIQUE (event_id, prize_key),
    CONSTRAINT fk_event_prize_event FOREIGN KEY (event_id) REFERENCES event (event_id),
    CONSTRAINT ck_event_prize_priority CHECK (priority > 0),
    CONSTRAINT ck_event_prize_weight CHECK (probability_weight > 0),
    CONSTRAINT ck_event_prize_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE draw_snapshot
    ADD COLUMN prize_algorithm_version VARCHAR(30) NOT NULL DEFAULT 'PRIZE_WEIGHTED_V1'
        COMMENT '당첨자 상품 배정 알고리즘' AFTER algorithm_version;

CREATE TABLE draw_snapshot_prize (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    snapshot_id        BIGINT       NOT NULL,
    prize_key          VARCHAR(100) NOT NULL,
    display_name       VARCHAR(200) NOT NULL,
    priority           INT          NOT NULL,
    probability_weight BIGINT       NOT NULL,
    quantity           INT          NOT NULL,
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_snapshot_prize_key UNIQUE (snapshot_id, prize_key),
    CONSTRAINT fk_snapshot_prize_snapshot FOREIGN KEY (snapshot_id) REFERENCES draw_snapshot (id),
    CONSTRAINT ck_snapshot_prize_priority CHECK (priority > 0),
    CONSTRAINT ck_snapshot_prize_weight CHECK (probability_weight > 0),
    CONSTRAINT ck_snapshot_prize_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE drawing
    ADD COLUMN prize_algorithm_version VARCHAR(30) NOT NULL DEFAULT 'PRIZE_WEIGHTED_V1'
        COMMENT '당첨자 상품 배정 알고리즘' AFTER algorithm_version;

ALTER TABLE winner
    ADD COLUMN snapshot_prize_id BIGINT NULL AFTER applied_ticket_count,
    ADD COLUMN prize_key VARCHAR(100) NULL AFTER snapshot_prize_id,
    ADD COLUMN prize_display_name VARCHAR(200) NULL AFTER prize_key,
    ADD COLUMN prize_priority INT NULL AFTER prize_display_name,
    ADD CONSTRAINT fk_winner_snapshot_prize FOREIGN KEY (snapshot_prize_id) REFERENCES draw_snapshot_prize (id),
    ADD CONSTRAINT ck_winner_prize_priority CHECK (prize_priority IS NULL OR prize_priority > 0);

-- Creator OAuth 원본 이름(name)과 공개 활동명을 분리한다. 기존 Creator는 본명 대신 임시 활동명으로 이관한다.
ALTER TABLE creator
    ADD COLUMN activity_name VARCHAR(20) NULL AFTER name,
    ADD COLUMN activity_name_normalized VARCHAR(20) NULL AFTER activity_name,
    ADD COLUMN activity_name_changed_at DATETIME(6) NULL AFTER activity_name_normalized,
    ADD COLUMN previous_activity_name VARCHAR(20) NULL AFTER activity_name_changed_at,
    ADD COLUMN previous_activity_name_normalized VARCHAR(20) NULL AFTER previous_activity_name,
    ADD COLUMN activity_name_configured BOOLEAN NOT NULL DEFAULT FALSE AFTER previous_activity_name_normalized;

UPDATE creator
SET activity_name = CONCAT('creator-', creator_id),
    activity_name_normalized = CONCAT('creator-', creator_id),
    activity_name_configured = FALSE;

ALTER TABLE creator
    MODIFY COLUMN activity_name VARCHAR(20) NOT NULL,
    MODIFY COLUMN activity_name_normalized VARCHAR(20) NOT NULL,
    ADD CONSTRAINT uk_creator_activity_name_normalized UNIQUE (activity_name_normalized);

CREATE TABLE creator_activity_name_reservation (
    reservation_id BIGINT NOT NULL AUTO_INCREMENT,
    activity_name VARCHAR(20) NOT NULL,
    activity_name_normalized VARCHAR(20) NOT NULL,
    creator_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reservation_id),
    CONSTRAINT uk_creator_activity_name_reservation_normalized UNIQUE (activity_name_normalized),
    CONSTRAINT fk_creator_activity_name_reservation_creator
        FOREIGN KEY (creator_id) REFERENCES creator (creator_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

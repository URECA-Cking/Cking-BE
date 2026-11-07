ALTER TABLE creator
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN status_notice VARCHAR(500) NULL;

CREATE INDEX idx_creator_status_id ON creator (status, creator_id);

CREATE TABLE creator_status_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    creator_id BIGINT NOT NULL,
    previous_status VARCHAR(20) NOT NULL,
    new_status VARCHAR(20) NOT NULL,
    reason_code VARCHAR(50) NOT NULL,
    detail_reason VARCHAR(1000) NOT NULL,
    public_notice VARCHAR(500) NOT NULL,
    admin_id BIGINT NOT NULL,
    request_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_creator_status_history_request UNIQUE (request_id),
    CONSTRAINT fk_creator_status_history_creator FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    CONSTRAINT fk_creator_status_history_admin FOREIGN KEY (admin_id) REFERENCES member (member_id),
    INDEX idx_creator_status_history_creator (creator_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

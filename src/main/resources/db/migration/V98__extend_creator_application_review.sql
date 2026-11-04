ALTER TABLE creator_application
    ADD COLUMN submitted_form JSON NULL,
    ADD COLUMN draft_form JSON NULL,
    ADD COLUMN submission_version INT NOT NULL DEFAULT 0,
    ADD COLUMN revision_reason VARCHAR(500) NULL;

CREATE TABLE creator_application_submission (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    application_id BIGINT NOT NULL,
    version INT NOT NULL,
    form_json JSON NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_creator_application_submission_application
        FOREIGN KEY (application_id) REFERENCES creator_application (id),
    CONSTRAINT uk_creator_application_submission_version UNIQUE (application_id, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE creator_application_review_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    application_id BIGINT NOT NULL,
    action VARCHAR(30) NOT NULL,
    submission_version INT NOT NULL,
    actor_id BIGINT NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_creator_application_review_history_application
        FOREIGN KEY (application_id) REFERENCES creator_application (id),
    CONSTRAINT fk_creator_application_review_history_actor
        FOREIGN KEY (actor_id) REFERENCES member (member_id),
    INDEX idx_creator_application_review_history_application (application_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

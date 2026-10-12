ALTER TABLE creator_space
    ADD COLUMN profile_image_key VARCHAR(200) NULL AFTER profile_image_url;

CREATE TABLE creator_space_profile_image (
    profile_image_id BIGINT NOT NULL AUTO_INCREMENT,
    object_key VARCHAR(200) NOT NULL,
    creator_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    status_changed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (profile_image_id),
    CONSTRAINT uk_creator_space_profile_image_object_key UNIQUE (object_key),
    CONSTRAINT fk_creator_space_profile_image_creator
        FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    INDEX idx_creator_space_profile_image_cleanup (status, status_changed_at, profile_image_id),
    INDEX idx_creator_space_profile_image_unlinked_cleanup (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

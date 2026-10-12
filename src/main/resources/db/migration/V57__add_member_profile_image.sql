ALTER TABLE member
    ADD COLUMN profile_image_key VARCHAR(200) NULL AFTER previous_nickname_normalized;

CREATE TABLE member_profile_image (
    profile_image_id BIGINT NOT NULL AUTO_INCREMENT,
    object_key VARCHAR(200) NOT NULL,
    member_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    status_changed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (profile_image_id),
    CONSTRAINT uk_member_profile_image_object_key UNIQUE (object_key),
    CONSTRAINT fk_member_profile_image_member
        FOREIGN KEY (member_id) REFERENCES member (member_id),
    INDEX idx_member_profile_image_cleanup (status, status_changed_at, profile_image_id),
    INDEX idx_member_profile_image_unlinked_cleanup (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

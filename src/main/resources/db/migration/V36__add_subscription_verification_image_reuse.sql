-- 정규화 이미지 SHA-256의 재사용 탐지를 직렬화하고 결과를 감사용으로 보존한다.
-- 이미지 bytes나 Object key는 이 테이블들에 저장하지 않는다.
CREATE TABLE subscription_verification_image_hash_lock (
    image_sha256 CHAR(64)    NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (image_sha256)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE subscription_verification_image_reuse (
    verification_id         BIGINT       NOT NULL,
    matched_verification_id BIGINT       NULL,
    reuse_type              VARCHAR(40) NOT NULL COMMENT 'FIRST_USE, SAME_MEMBER_SAME_CREATOR, SAME_MEMBER_DIFFERENT_CREATOR, DIFFERENT_MEMBER',
    detected_at             DATETIME(6) NOT NULL,
    PRIMARY KEY (verification_id),
    INDEX idx_subscription_verification_image_reuse_matched (matched_verification_id),
    CONSTRAINT fk_subscription_verification_image_reuse_verification
        FOREIGN KEY (verification_id) REFERENCES subscription_verification (verification_id),
    CONSTRAINT fk_subscription_verification_image_reuse_matched_verification
        FOREIGN KEY (matched_verification_id) REFERENCES subscription_verification (verification_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

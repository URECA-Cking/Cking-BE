CREATE TABLE oauth_account (
    oauth_account_id BIGINT       NOT NULL AUTO_INCREMENT,
    member_id        BIGINT       NOT NULL,
    provider         VARCHAR(20)  NOT NULL COMMENT 'GOOGLE, KAKAO',
    provider_user_id VARCHAR(255) NOT NULL,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (oauth_account_id),
    CONSTRAINT uk_oauth_account_provider_user UNIQUE (provider, provider_user_id),
    CONSTRAINT fk_oauth_account_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    INDEX idx_oauth_account_member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

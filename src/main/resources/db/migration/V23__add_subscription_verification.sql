-- YouTube 구독 인증 요청과 비동기 처리·보상 상태를 보존한다.
-- active_guard / approved_guard는 해당 상태일 때만 1이고 그 외에는 NULL이다.
-- MySQL UNIQUE가 여러 NULL을 허용하는 성질을 이용해 활성 요청과 승인 기록을
-- 사용자·Creator·Mission별 최대 한 건으로 제한하면서 종료 이력은 보존한다.
CREATE TABLE subscription_verification (
    verification_id          BIGINT       NOT NULL AUTO_INCREMENT,
    member_id                BIGINT       NOT NULL,
    creator_id               BIGINT       NOT NULL,
    mission_id               BIGINT       NOT NULL,
    request_id               VARCHAR(36)  NOT NULL,
    request_fingerprint      CHAR(64)     NOT NULL,
    target_channel_name      VARCHAR(100) NOT NULL,
    target_channel_handle    VARCHAR(100) NOT NULL,
    image_object_key         VARCHAR(500) NOT NULL,
    image_sha256             CHAR(64)     NOT NULL,
    normalization_version    VARCHAR(30)  NOT NULL,
    status                   VARCHAR(30)  NOT NULL COMMENT 'PENDING, PROCESSING, APPROVED, REJECTED, RETRY_REQUIRED, FAILED',
    reason_code              VARCHAR(50)  NULL,
    attempt_count            INT          NOT NULL DEFAULT 0,
    processing_token         VARCHAR(36)  NULL,
    processing_started_at    DATETIME(6)  NULL,
    processing_lease_until   DATETIME(6)  NULL,
    next_attempt_at          DATETIME(6)  NULL,
    processed_at             DATETIME(6)  NULL,
    reward_request_id        VARCHAR(36)  NOT NULL,
    reward_period_key        VARCHAR(10)  NOT NULL COMMENT 'Verification 생성 UTC 날짜, YYYY-MM-DD',
    reward_status            VARCHAR(30)  NOT NULL COMMENT 'NOT_REQUESTED, PENDING, ACCEPTED, RETRY_REQUIRED',
    active_guard             TINYINT      NULL COMMENT 'PENDING/PROCESSING이면 1, 그 외 NULL',
    approved_guard           TINYINT      NULL COMMENT 'APPROVED이면 1, 그 외 NULL',
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (verification_id),
    CONSTRAINT uk_subscription_verification_request UNIQUE (request_id),
    CONSTRAINT uk_subscription_verification_reward_request UNIQUE (reward_request_id),
    CONSTRAINT uk_subscription_verification_active
        UNIQUE (member_id, creator_id, mission_id, active_guard),
    CONSTRAINT uk_subscription_verification_approved
        UNIQUE (member_id, creator_id, mission_id, approved_guard),
    CONSTRAINT fk_subscription_verification_member
        FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_subscription_verification_creator
        FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    CONSTRAINT fk_subscription_verification_mission
        FOREIGN KEY (mission_id) REFERENCES mission (mission_id),
    CONSTRAINT ck_subscription_verification_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_subscription_verification_active_guard CHECK (active_guard IS NULL OR active_guard = 1),
    CONSTRAINT ck_subscription_verification_approved_guard CHECK (approved_guard IS NULL OR approved_guard = 1),
    INDEX idx_subscription_verification_recovery (status, next_attempt_at),
    INDEX idx_subscription_verification_image_hash (image_sha256),
    INDEX idx_subscription_verification_history
        (member_id, creator_id, mission_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

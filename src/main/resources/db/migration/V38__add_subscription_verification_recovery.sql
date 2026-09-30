ALTER TABLE subscription_verification
    ADD COLUMN reward_attempt_count INT NOT NULL DEFAULT 0 AFTER reward_status,
    ADD CONSTRAINT ck_subscription_verification_reward_attempt
        CHECK (reward_attempt_count >= 0),
    ADD INDEX idx_subscription_verification_processing_recovery
        (status, processing_lease_until, attempt_count, verification_id),
    ADD INDEX idx_subscription_verification_reward_recovery
        (status, reward_status, next_attempt_at, verification_id);

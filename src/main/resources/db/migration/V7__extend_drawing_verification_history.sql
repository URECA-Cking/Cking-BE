-- 원본 Seed 결정적 재현과 새 Seed 독립 재실행의 인원 수 검증을
-- 하나의 append-only 이력에서 감사할 수 있도록 증거 필드를 확장한다.
ALTER TABLE draw_verification_history
    ADD COLUMN verification_mode       VARCHAR(50)  NULL
        COMMENT 'LEGACY_INTEGRITY, DETERMINISTIC_AND_CARDINALITY_REPLAY' AFTER status,
    ADD COLUMN replay_seed_value       VARBINARY(32) NULL
        COMMENT '독립 재실행에 사용한 32-byte Seed' AFTER verification_mode,
    ADD COLUMN expected_winner_count   INT           NULL AFTER replay_seed_value,
    ADD COLUMN actual_winner_count     INT           NULL AFTER expected_winner_count,
    ADD COLUMN winner_count_matched    BOOLEAN       NULL AFTER actual_winner_count,
    ADD COLUMN winners_unique          BOOLEAN       NULL AFTER winner_count_matched,
    ADD COLUMN candidates_matched      BOOLEAN       NULL AFTER winners_unique,
    ADD COLUMN exclusions_matched      BOOLEAN       NULL AFTER candidates_matched,
    ADD COLUMN ranks_matched           BOOLEAN       NULL AFTER exclusions_matched;

-- V7 이전 이력은 원본 Seed 결정적 재현과 새 Seed 보조 검증을 수행하지 않았으므로
-- 실제 수행 범위를 보존하는 legacy mode로 명시한다.
UPDATE draw_verification_history
SET verification_mode = 'LEGACY_INTEGRITY'
WHERE verification_mode IS NULL;

ALTER TABLE draw_verification_history
    MODIFY COLUMN verification_mode VARCHAR(50) NOT NULL DEFAULT 'LEGACY_INTEGRITY'
        COMMENT 'LEGACY_INTEGRITY, DETERMINISTIC_AND_CARDINALITY_REPLAY';

ALTER TABLE draw_verification_history
    ADD CONSTRAINT ck_verification_expected_winner_count
        CHECK (expected_winner_count IS NULL OR expected_winner_count > 0),
    ADD CONSTRAINT ck_verification_actual_winner_count
        CHECK (actual_winner_count IS NULL OR actual_winner_count >= 0);

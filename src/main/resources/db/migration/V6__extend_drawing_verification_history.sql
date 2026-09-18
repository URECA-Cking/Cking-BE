-- 기존 결과 무결성 검증과 새 Seed를 사용한 독립 재실행의 인원 수 검증을
-- 하나의 append-only 이력에서 구분해 감사할 수 있도록 증거 필드를 확장한다.
ALTER TABLE draw_verification_history
    ADD COLUMN verification_mode       VARCHAR(30)  NOT NULL DEFAULT 'CARDINALITY_REPLAY'
        COMMENT 'CARDINALITY_REPLAY' AFTER status,
    ADD COLUMN replay_seed_value       VARBINARY(32) NULL
        COMMENT '독립 재실행에 사용한 32-byte Seed' AFTER verification_mode,
    ADD COLUMN expected_winner_count   INT           NULL AFTER replay_seed_value,
    ADD COLUMN actual_winner_count     INT           NULL AFTER expected_winner_count,
    ADD COLUMN winner_count_matched    BOOLEAN       NULL AFTER actual_winner_count,
    ADD COLUMN winners_unique          BOOLEAN       NULL AFTER winner_count_matched,
    ADD COLUMN candidates_matched      BOOLEAN       NULL AFTER winners_unique,
    ADD COLUMN exclusions_matched      BOOLEAN       NULL AFTER candidates_matched,
    ADD COLUMN ranks_matched           BOOLEAN       NULL AFTER exclusions_matched;

ALTER TABLE draw_verification_history
    ADD CONSTRAINT ck_verification_expected_winner_count
        CHECK (expected_winner_count IS NULL OR expected_winner_count > 0),
    ADD CONSTRAINT ck_verification_actual_winner_count
        CHECK (actual_winner_count IS NULL OR actual_winner_count >= 0);

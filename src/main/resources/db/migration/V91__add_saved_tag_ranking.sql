-- 전체 공개 확인 태그 snapshot의 순위. 원문/자동 후보는 저장하지 않는다.
CREATE TABLE saved_tag_ranking_state (
    state_id TINYINT PRIMARY KEY,
    application_sequence BIGINT NOT NULL DEFAULT 0,
    snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    model_version VARCHAR(255) NULL,
    scores JSON NULL,
    CONSTRAINT ck_saved_tag_singleton CHECK (state_id = 1)
) ENGINE=InnoDB;
INSERT INTO saved_tag_ranking_state(state_id) VALUES(1);

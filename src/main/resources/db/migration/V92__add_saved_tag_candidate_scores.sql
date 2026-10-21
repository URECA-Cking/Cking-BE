-- V91 remains immutable. Existing JSON scores require one batch reapply before indexed reads are READY.
CREATE TABLE saved_tag_ranking_score (
    tag_type VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tag_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    creator_id BIGINT NOT NULL,
    application_sequence BIGINT NOT NULL,
    score DECIMAL(9,8) NOT NULL,
    input_revision BIGINT NOT NULL,
    confirmation_id BIGINT NOT NULL,
    taxonomy_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    config_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (tag_type, tag_code, creator_id),
    KEY idx_saved_tag_creator (creator_id),
    CONSTRAINT fk_saved_tag_score_creator FOREIGN KEY (creator_id) REFERENCES creator(creator_id) ON DELETE CASCADE,
    CONSTRAINT ck_saved_tag_score_range CHECK (score BETWEEN -1 AND 1)
) ENGINE=InnoDB;

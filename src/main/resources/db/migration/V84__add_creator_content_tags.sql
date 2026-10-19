-- Immutable automatic/creator-confirmed history. No draft taxonomy is seeded.
ALTER TABLE creator_space ADD COLUMN content_revision BIGINT NOT NULL DEFAULT 1;
CREATE TABLE creator_content_taxonomy (
    taxonomy_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    taxonomy_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    criteria_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    config_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parent_codes JSON NOT NULL,
    topic_parents JSON NOT NULL,
    taxonomy_snapshot JSON NOT NULL,
    approved BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    active_marker TINYINT GENERATED ALWAYS AS (IF(active, 1, NULL)) STORED,
    UNIQUE KEY uk_creator_content_taxonomy_active (active_marker)
) ENGINE=InnoDB;
CREATE TABLE creator_content_automatic (
    automatic_revision CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    creator_id BIGINT NOT NULL,
    input_revision BIGINT NOT NULL,
    input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    config_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_creator_content_input (creator_id, input_revision, config_hash),
    CONSTRAINT fk_content_auto_creator FOREIGN KEY (creator_id) REFERENCES creator(creator_id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE TABLE creator_content_confirmation (
    confirmation_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    creator_id BIGINT NOT NULL,
    automatic_revision CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_revision BIGINT NOT NULL,
    input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    taxonomy_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tags JSON NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_content_confirm_creator FOREIGN KEY (creator_id) REFERENCES creator(creator_id) ON DELETE CASCADE,
    CONSTRAINT fk_content_confirm_auto FOREIGN KEY (automatic_revision) REFERENCES creator_content_automatic(automatic_revision) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE TABLE creator_content_state (
    creator_id BIGINT PRIMARY KEY,
    automatic_revision CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    confirmation_id BIGINT NULL,
    CONSTRAINT fk_content_state_creator FOREIGN KEY (creator_id) REFERENCES creator(creator_id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE TABLE creator_content_job (
    creator_id BIGINT PRIMARY KEY,
    generation BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    lease_token CHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    committed_at DATETIME(6) NOT NULL,
    claimed_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    next_retry_at DATETIME(6) NOT NULL,
    last_summary JSON NULL,
    CONSTRAINT fk_content_job_creator FOREIGN KEY (creator_id) REFERENCES creator(creator_id) ON DELETE CASCADE,
    KEY idx_content_job_pending (status, next_retry_at)
) ENGINE=InnoDB;
INSERT INTO creator_content_job(creator_id,generation,committed_at,next_retry_at)
    SELECT creator_id,content_revision,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM creator_space;
DELIMITER $$
CREATE TRIGGER creator_content_insert AFTER INSERT ON creator_space FOR EACH ROW
BEGIN
    INSERT INTO creator_content_job(creator_id,generation,committed_at,next_retry_at)
        VALUES(NEW.creator_id,NEW.content_revision,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
END$$
CREATE TRIGGER creator_content_update AFTER UPDATE ON creator_space FOR EACH ROW
BEGIN
    IF NEW.content_revision <> OLD.content_revision THEN
        INSERT INTO creator_content_job(creator_id,generation,status,committed_at,next_retry_at)
            VALUES(NEW.creator_id,NEW.content_revision,'PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
        ON DUPLICATE KEY UPDATE generation=NEW.content_revision,status='PENDING',lease_token=NULL,
            lease_until=NULL,attempt_count=0,committed_at=UTC_TIMESTAMP(6),claimed_at=NULL,
            completed_at=NULL,next_retry_at=UTC_TIMESTAMP(6),last_summary=NULL;
    END IF;
END$$
DELIMITER ;

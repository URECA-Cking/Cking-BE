ALTER TABLE event
    ADD COLUMN archived_at DATETIME(6) NULL AFTER deleted_at,
    ADD COLUMN archived_by BIGINT NULL AFTER archived_at,
    ADD INDEX idx_event_archived_created (archived_at, created_at, event_id);

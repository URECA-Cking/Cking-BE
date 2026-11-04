-- Existing manually entered channels have no ownership evidence.
ALTER TABLE creator_youtube_channel
    ADD COLUMN youtube_channel_id VARCHAR(100) NULL,
    ADD COLUMN verification_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
    ADD COLUMN verification_method VARCHAR(20) NULL,
    ADD COLUMN verified_at DATETIME(6) NULL,
    DROP INDEX uk_creator_youtube_channel_handle,
    ADD CONSTRAINT uk_creator_youtube_channel_id UNIQUE (youtube_channel_id);


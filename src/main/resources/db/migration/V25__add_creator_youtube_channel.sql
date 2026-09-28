-- Creator가 구독 인증 대상으로 설정한 YouTube 채널을 Creator당 하나만 보존한다.
CREATE TABLE creator_youtube_channel (
    creator_id     BIGINT       NOT NULL,
    channel_name   VARCHAR(100) NOT NULL,
    channel_handle VARCHAR(100) NOT NULL,
    channel_url    VARCHAR(500) NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (creator_id),
    CONSTRAINT uk_creator_youtube_channel_handle UNIQUE (channel_handle),
    CONSTRAINT fk_creator_youtube_channel_creator
        FOREIGN KEY (creator_id) REFERENCES creator (creator_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Event 배너 이미지 업로드(이슈 #748). 배너가 없으면 두 컬럼 모두 NULL이고 화면이 대체 이미지를 고른다.
ALTER TABLE event
    ADD COLUMN banner_image_key VARCHAR(200) NULL AFTER description,
    ADD COLUMN banner_image_url VARCHAR(500) NULL AFTER banner_image_key;

-- 업로드 기록과 정리 대상이다. 저장 경로는 CDN 공개·서버 쓰기 권한이 이미 열린 creator-space-profile-images/{creatorId}/event-banner/ 아래이며,
-- Creator Space 이미지(creator_space_profile_image)와 테이블·정리 작업을 나눠 서로의 키를 연결하거나 지우지 않는다.
CREATE TABLE event_banner_image (
    banner_image_id BIGINT NOT NULL AUTO_INCREMENT,
    object_key VARCHAR(200) NOT NULL,
    creator_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    status_changed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (banner_image_id),
    CONSTRAINT uk_event_banner_image_object_key UNIQUE (object_key),
    CONSTRAINT fk_event_banner_image_creator
        FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    INDEX idx_event_banner_image_cleanup (status, status_changed_at, banner_image_id),
    INDEX idx_event_banner_image_unlinked_cleanup (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

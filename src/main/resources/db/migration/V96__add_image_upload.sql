-- 앱 밖 이미지 파이프라인 업로드(이슈 #780). 앱이 예약하고 사전 서명 PUT을 주며, 정규화 결과는 결과 큐로 받는다.
-- object_key는 용도별 업로드 기록(member_profile_image 등)의 키와 같고, 원본은 raw/<object_key>에 올라온다.
CREATE TABLE image_upload (
    upload_id CHAR(36) NOT NULL,
    member_id BIGINT NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    object_key VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    failure_reason VARCHAR(40) NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (upload_id),
    CONSTRAINT uk_image_upload_object_key UNIQUE (object_key),
    INDEX idx_image_upload_member (member_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

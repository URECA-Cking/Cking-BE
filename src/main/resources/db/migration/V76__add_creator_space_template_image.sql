-- 관리자 Creator Space 템플릿 이미지 업로드 기록(이슈 #598). 템플릿에 쓰지 않은 업로드를 정리할 수 있도록 올린 객체를 기록한다.
-- Creator 소유 이미지(creator_space_profile_image)와 달리 Creator에 속하지 않고, 연결(LINKED) 상태도 없다.
-- 템플릿·Space는 key가 아니라 CDN URL만 저장하므로, 정리는 이 key가 어느 URL 컬럼에도 포함되지 않을 때만 한다.
-- status: UPLOADING → UPLOADED → DELETE_PENDING → DELETING → DELETED. 저장소 객체 삭제는 DB 트랜잭션에 묶을 수 없어서
-- DELETE_PENDING → DELETING 조건부 UPDATE로 삭제를 먼저 확정하고, 템플릿 저장은 같은 행을 UPLOADED로 고정해 둘이 동시에
-- 성공하지 못하게 한다. DELETED는 지운 뒤에도 남겨 옛 URL을 다시 붙여 넣는 저장을 거절하는 데 쓴다.
CREATE TABLE creator_space_template_image (
    template_image_id BIGINT NOT NULL AUTO_INCREMENT,
    object_key VARCHAR(200) NOT NULL,
    image_kind VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    status_changed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (template_image_id),
    CONSTRAINT uk_creator_space_template_image_object_key UNIQUE (object_key),
    INDEX idx_creator_space_template_image_cleanup (status, status_changed_at, template_image_id),
    INDEX idx_creator_space_template_image_unreferenced_cleanup (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

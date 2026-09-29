-- Creator Space 게시글과 게시글 이미지 업로드 기록 (이슈 #318)
--
-- creator_post: Creator가 작성하는 게시글. visibility는 PUBLIC(전체 공개) 또는 FOLLOWERS(팔로워 공개)다.
-- 소프트 삭제 없이 하드 삭제한다.
-- idx_creator_post_creator_created는 크리에이터별 게시글 목록을 지원한다.
--   WHERE creator_id = ? ORDER BY created_at DESC, post_id DESC
CREATE TABLE creator_post (
    post_id     BIGINT        NOT NULL AUTO_INCREMENT,
    creator_id  BIGINT        NOT NULL,
    content     VARCHAR(2000) NOT NULL,
    visibility  VARCHAR(20)   NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (post_id),
    INDEX idx_creator_post_creator_created (creator_id, created_at, post_id),
    CONSTRAINT fk_creator_post_creator FOREIGN KEY (creator_id) REFERENCES creator (creator_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- creator_post_image: 저장소에 올린 게시글 이미지마다 한 행. 소유자·연결 검증과 정리에 쓴다.
--
-- 상태
--   UPLOADING      : 저장소 put 전에 먼저 기록한다. put이 중간에 실패해도 기록이 남아 정리할 수 있다.
--   UPLOADED       : put 완료. post_id가 NULL이면 아직 게시글에 연결되지 않은 이미지다.
--   DELETE_PENDING : 저장소 삭제 대기. 저장소 delete 성공 후 행을 삭제한다.
-- 게시글 연결은 `status = 'UPLOADED' AND post_id IS NULL AND creator_id = 본인` 조건부 UPDATE로만 하고,
-- 정리 스케줄러도 `post_id IS NULL` 조건부 UPDATE로 DELETE_PENDING을 선점하므로 둘 중 하나만 성공한다.
--
-- idx_creator_post_image_post는 게시글별 이미지 순서 조회를 지원한다.
-- idx_creator_post_image_status_changed는 정리 스케줄러의 상태·경과 시간 조회를 지원한다.
CREATE TABLE creator_post_image (
    image_id           BIGINT       NOT NULL AUTO_INCREMENT,
    object_key         VARCHAR(200) NOT NULL,
    creator_id         BIGINT       NOT NULL,
    post_id            BIGINT       NULL,
    display_order      INT          NULL,
    status             VARCHAR(20)  NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    status_changed_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (image_id),
    CONSTRAINT uk_creator_post_image_object_key UNIQUE (object_key),
    INDEX idx_creator_post_image_post (post_id, display_order),
    INDEX idx_creator_post_image_status_changed (status, status_changed_at),
    CONSTRAINT fk_creator_post_image_creator FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    CONSTRAINT fk_creator_post_image_post FOREIGN KEY (post_id) REFERENCES creator_post (post_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

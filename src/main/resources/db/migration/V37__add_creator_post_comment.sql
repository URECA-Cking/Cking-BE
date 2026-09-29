-- Creator Space 게시글 댓글 (이슈 #341)
--
-- 게시글(creator_post)에 달린 댓글이다. 소프트 삭제 없이 하드 삭제하며, 게시글을 삭제하면 같은
-- Transaction에서 댓글을 먼저 삭제한다. member_id는 댓글 작성자다.
--
-- idx_creator_post_comment_post_created는 게시글별 댓글 목록을 지원한다.
--   WHERE post_id = ? ORDER BY created_at ASC, comment_id ASC
CREATE TABLE creator_post_comment (
    comment_id  BIGINT       NOT NULL AUTO_INCREMENT,
    post_id     BIGINT       NOT NULL,
    member_id   BIGINT       NOT NULL,
    content     VARCHAR(500) NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (comment_id),
    INDEX idx_creator_post_comment_post_created (post_id, created_at, comment_id),
    CONSTRAINT fk_creator_post_comment_post FOREIGN KEY (post_id) REFERENCES creator_post (post_id),
    CONSTRAINT fk_creator_post_comment_member FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

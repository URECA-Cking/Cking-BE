-- Creator Space 게시글 좋아요 (이슈 #554)
--
-- 회원은 게시글마다 한 번만 좋아요할 수 있다. 복합 PK (post_id, member_id)가 중복을 막고, 게시글별 좋아요 수 집계
-- (WHERE post_id = ?)와 한 페이지 게시글에 대한 내 좋아요 조회(WHERE post_id IN (...) AND member_id = ?)를 함께 지원한다.
-- 게시글을 삭제하면 좋아요도 함께 지워진다(ON DELETE CASCADE). 회원 FK에는 CASCADE를 걸지 않는다.
CREATE TABLE creator_post_like (
    post_id    BIGINT      NOT NULL,
    member_id  BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (post_id, member_id),
    CONSTRAINT fk_creator_post_like_post FOREIGN KEY (post_id) REFERENCES creator_post (post_id) ON DELETE CASCADE,
    CONSTRAINT fk_creator_post_like_member FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 현재 팔로우 관계에만 게시글 읽음 기준을 보관한다. 언팔로우 시 관계와 함께 제거된다.
-- 조회는 기존 uk_creator_follow_member_creator 및 idx_creator_post_creator_created를 사용한다.
ALTER TABLE creator_follow
    ADD COLUMN last_read_at DATETIME(6) NULL;

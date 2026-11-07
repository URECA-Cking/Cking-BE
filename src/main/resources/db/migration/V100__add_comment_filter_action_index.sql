-- 관리자 필터 차단 댓글 목록의 최근 판정 순 페이지 조회를 지원한다(이슈 #809).
-- WHERE filter_action = 'BLOCK' ORDER BY filtered_at DESC, comment_id DESC
CREATE INDEX idx_creator_post_comment_action_filtered_id
    ON creator_post_comment (filter_action, filtered_at, comment_id);

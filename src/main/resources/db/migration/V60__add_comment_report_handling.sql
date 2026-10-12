-- 게시글 댓글 신고 처리와 관리자 숨김 (이슈 #499)
--
-- 관리자가 신고된 댓글을 확인한 뒤 숨김 또는 반려로 처리한다. 신고마다 처리 상태·처리한 관리자·처리 시각을 남기고,
-- 숨김은 필터 판정(filter_status·filter_action)과 별도 컬럼에 저장해 필터 재판정이나 댓글 수정으로 덮어써지지 않게 한다.
--
-- creator_post_comment_report.status
--   PENDING  : 접수(관리자가 아직 처리하지 않음). 기존 신고는 모두 접수로 시작한다.
--   RESOLVED : 처리 완료. 관리자가 댓글을 숨기면서 같은 댓글의 접수 신고를 한 번에 처리했다.
--   REJECTED : 반려. 관리자가 신고를 받아들이지 않고 같은 댓글의 접수 신고를 한 번에 처리했다.
-- handled_by / handled_at : 처리한 관리자와 시각. 접수 상태에서는 NULL이다.
--
-- creator_post_comment.admin_hidden_at / admin_hidden_by
--   관리자가 숨긴 시각과 관리자. NULL이면 숨기지 않은 댓글이다. 숨김은 되돌리지 않는다.
--
-- idx_comment_report_status_comment는 관리자 목록의 상태 필터(접수 신고가 있는 댓글 찾기)와 처리 대상 갱신
--   WHERE comment_id = ? AND status = 'PENDING' 에 쓰인다.
ALTER TABLE creator_post_comment_report
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER detail,
    ADD COLUMN handled_by BIGINT NULL AFTER status,
    ADD COLUMN handled_at DATETIME(6) NULL AFTER handled_by,
    ADD INDEX idx_comment_report_status_comment (status, comment_id),
    ADD CONSTRAINT fk_comment_report_handled_by FOREIGN KEY (handled_by) REFERENCES member (member_id);

ALTER TABLE creator_post_comment
    ADD COLUMN admin_hidden_at DATETIME(6) NULL AFTER filter_next_attempt_at,
    ADD COLUMN admin_hidden_by BIGINT NULL AFTER admin_hidden_at,
    ADD CONSTRAINT fk_comment_admin_hidden_by FOREIGN KEY (admin_hidden_by) REFERENCES member (member_id);

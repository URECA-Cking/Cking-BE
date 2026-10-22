-- 관리자 Creator 신청 목록의 상태 필터·심사 순서 페이지 조회를 지원한다.
-- WHERE status = ? ORDER BY requested_at ASC, id ASC
CREATE INDEX idx_creator_app_status_requested_id
    ON creator_application (status, requested_at, id);

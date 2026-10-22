-- 사용자별 알림 목록: member_id 조건과 최신순(created_at, id) 정렬을 함께 지원한다.
CREATE INDEX idx_notification_member_created_id
    ON notification (member_id, created_at DESC, id DESC);

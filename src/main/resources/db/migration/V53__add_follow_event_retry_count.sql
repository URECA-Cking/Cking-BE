-- 기존 pending 이벤트도 실패 횟수 0부터 지수 백오프로 재시도한다.
ALTER TABLE creator_follow_event
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_follow_event_retry_count CHECK (retry_count >= 0);

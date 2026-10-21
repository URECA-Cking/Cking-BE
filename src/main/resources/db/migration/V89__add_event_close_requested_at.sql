-- 마감 요청 시각(OPEN -> CLOSING 전이 시점). 사용자 기준 마감 시각이며 공개 Event 응답으로 내려간다(#716).
-- Drain 완료 시각인 closed_at과 구분한다. 이 컬럼 전에 마감된 Event는 비어 있다.
ALTER TABLE event
    ADD COLUMN close_requested_at DATETIME(6) NULL COMMENT '마감 요청 시각(OPEN -> CLOSING 전이 시점)';

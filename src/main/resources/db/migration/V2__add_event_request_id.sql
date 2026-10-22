-- Event 생성 요청 멱등성 식별자 추가
--
-- DB 스키마 문서 §8에 request_id(UNIQUE, NOT NULL)가 추가되어 반영한다.
-- 동일 requestId + 동일 요청 → 기존 Event 반환
-- 동일 requestId + 다른 요청 → IDEMPOTENCY_CONFLICT
--
-- V1은 이미 develop에 반영되어 체크섬이 고정되었으므로 별도 마이그레이션으로 추가한다.

ALTER TABLE event
    ADD COLUMN request_id VARCHAR(36) NOT NULL COMMENT 'Event 생성 요청 멱등성 식별자 (UUID)' AFTER deleted_at,
    ADD CONSTRAINT uk_event_request UNIQUE (request_id);

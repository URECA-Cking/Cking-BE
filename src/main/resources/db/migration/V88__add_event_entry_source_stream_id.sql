-- 응모를 반영한 Stream 메시지 ID. 같은 requestId로 새 메시지가 오면 응모 Lua가 다시 차감한 것이라
-- 반영 단계가 그 차감을 Redis에서 되돌린다(#673). 이 컬럼 전에 반영된 응모는 비어 있다.
ALTER TABLE event_entry
    ADD COLUMN source_stream_id VARCHAR(41) NULL COMMENT '응모를 반영한 Redis Stream 메시지 ID';

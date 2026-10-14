-- 알림 일반화와 제재 알림 (이슈 #572)
--
-- notification은 당첨 알림 전용이라 event_id·drawing_id·winner_id가 NOT NULL이었다. 제재 알림(댓글 작성 제한 시작·해제)은
-- 이벤트·추첨·당첨과 무관하므로 세 컬럼을 NULL 허용으로 바꾸고, 제재 알림이 가리키는 member_sanction을 sanction_id로
-- 참조한다. 기존 당첨 알림 행은 그대로 유효하다.
--
-- ck_notification_winner_lineage_all_or_none
--   MySQL은 복합 FK의 컬럼 중 하나라도 NULL이면 그 FK를 검사하지 않는다. 세 컬럼이 NULL 허용이 되면 winner_id만 채우고
--   event_id를 비워 V6의 계보 보장(fk_notification_winner_lineage)을 우회한 행이 들어갈 수 있다. 세 컬럼을 모두 채우거나
--   모두 비우도록 강제해 당첨 알림의 계보 보장을 유지한다.
-- uk_notification_sanction_type
--   같은 제재에 같은 종류의 알림이 중복 생성되지 않는다. 당첨 알림의 uk_notification_winner_type(winner_id, type)은
--   winner_id가 NULL이면 중복을 막지 못하므로 제재 알림은 이 키로 막는다. 두 키 모두 NULL인 컬럼은 유니크에서 제외된다.
-- fk_notification_sanction
--   제재 기록은 삭제하지 않으므로 CASCADE를 걸지 않는다.
ALTER TABLE notification
    MODIFY COLUMN event_id   BIGINT NULL,
    MODIFY COLUMN drawing_id BIGINT NULL,
    MODIFY COLUMN winner_id  BIGINT NULL,
    MODIFY COLUMN type       VARCHAR(30) NOT NULL
        COMMENT 'INITIAL_WINNER, REDRAW_WINNER, COMMENT_RESTRICTION_STARTED, COMMENT_RESTRICTION_RELEASED',
    ADD COLUMN sanction_id BIGINT NULL AFTER winner_id,
    ADD CONSTRAINT fk_notification_sanction FOREIGN KEY (sanction_id) REFERENCES member_sanction (sanction_id),
    ADD CONSTRAINT uk_notification_sanction_type UNIQUE (sanction_id, type),
    ADD CONSTRAINT ck_notification_winner_lineage_all_or_none CHECK (
        (event_id IS NULL AND drawing_id IS NULL AND winner_id IS NULL)
        OR (event_id IS NOT NULL AND drawing_id IS NOT NULL AND winner_id IS NOT NULL));

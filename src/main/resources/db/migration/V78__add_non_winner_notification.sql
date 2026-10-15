-- 미당첨자 결과 공개 알림 (이슈 #605)
--
-- 최초 추첨 결과 공개 시 후보였으나 당첨되지 못한 Member에게 INITIAL_NON_WINNER 알림을 보낸다. 이 알림은 Winner가 없으므로
-- winner_id가 NULL이고 event_id·drawing_id는 채운다. V71의 "세 컬럼 모두 NULL 또는 모두 NOT NULL" CHECK로는 저장할 수 없어
-- 유형별 CHECK로 교체한다. 기존 제재 알림(세 값 NULL)과 당첨 알림(세 값 NOT NULL)은 그대로 유효하다.
--
-- ck_notification_lineage_by_type
--   제재 알림: event·drawing·winner 모두 NULL / 당첨 알림: 모두 NOT NULL(미당첨 유형 제외) /
--   미당첨 알림: event·drawing NOT NULL, winner NULL(INITIAL_NON_WINNER만 허용).
--   winner_id만 비운 행이 당첨 계보 보장(fk_notification_winner_lineage)을 우회하지 못하도록 유형과 함께 묶는다.
-- uk_notification_event_member_type
--   uk_notification_winner_type은 winner_id가 NULL이면 중복을 막지 못한다. Winner가 (event_id, member_id)당 하나라
--   기존 당첨 알림 행은 이 키를 이미 만족하며, 같은 이벤트·Member에 같은 유형의 알림이 둘 이상 생기지 않게 한다.
--   event_id가 NULL인 제재 알림은 유니크에서 제외된다.
ALTER TABLE notification
    MODIFY COLUMN type VARCHAR(30) NOT NULL
        COMMENT 'INITIAL_WINNER, REDRAW_WINNER, INITIAL_NON_WINNER, COMMENT_RESTRICTION_STARTED, COMMENT_RESTRICTION_RELEASED',
    DROP CONSTRAINT ck_notification_winner_lineage_all_or_none,
    ADD CONSTRAINT ck_notification_lineage_by_type CHECK (
        (event_id IS NULL AND drawing_id IS NULL AND winner_id IS NULL)
        OR (event_id IS NOT NULL AND drawing_id IS NOT NULL AND winner_id IS NOT NULL
            AND type <> 'INITIAL_NON_WINNER')
        OR (event_id IS NOT NULL AND drawing_id IS NOT NULL AND winner_id IS NULL
            AND type = 'INITIAL_NON_WINNER')),
    ADD CONSTRAINT uk_notification_event_member_type UNIQUE (event_id, member_id, type);

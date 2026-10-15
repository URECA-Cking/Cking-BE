-- 알림 숨김 (이슈 #616)
--
-- 사용자가 알림함에서 숨긴 알림을 표시한다. 행을 지우지 않는다: 당첨·제재 알림은 이력으로 남아야 하고,
-- uk_notification_winner_type, uk_notification_sanction_type(V71), uk_notification_event_member_type(V78) 유니크가
-- 행이 있어야 같은 결과 알림의 중복 생성을 계속 막는다.
-- NULL이면 보이는 알림, 값이 있으면 숨긴 시각이다. 숨김을 되돌리는 기능은 없다.
-- 목록 인덱스(member_id, created_at DESC, id DESC)는 그대로 쓴다. 숨긴 행은 사용자당 소수라 인덱스 범위를 읽으며 걸러낸다.
ALTER TABLE notification
    ADD COLUMN hidden_at DATETIME(6) NULL COMMENT '사용자가 알림함에서 숨긴 시각. NULL이면 보이는 알림' AFTER read_at;

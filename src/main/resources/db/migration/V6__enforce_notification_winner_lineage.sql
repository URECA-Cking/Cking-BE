-- Notification의 member/event/drawing 식별자는 반드시 참조 Winner의 계보와 같아야 한다.
-- 단일 FK 네 개만으로는 각각의 행 존재 여부만 확인하므로, Winner 계보 전체를 참조하는 복합 FK를 둔다.

ALTER TABLE winner
    ADD CONSTRAINT uk_winner_notification_lineage
        UNIQUE (id, member_id, event_id, drawing_id);

ALTER TABLE notification
    ADD CONSTRAINT fk_notification_winner_lineage
        FOREIGN KEY (winner_id, member_id, event_id, drawing_id)
        REFERENCES winner (id, member_id, event_id, drawing_id);

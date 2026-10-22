-- Drawing은 검증된 Snapshot의 확정 입력을 그대로 사용해야 하며,
-- Winner의 event_id는 연결된 Drawing의 event_id와 같아야 한다.
-- 단일 FK만으로는 이 관계를 보장할 수 없어 복합 후보키와 FK를 추가한다.

ALTER TABLE draw_snapshot
    ADD CONSTRAINT uk_snapshot_drawing_contract
        UNIQUE (id, event_id, draw_method, algorithm_version);

ALTER TABLE drawing
    ADD CONSTRAINT uk_drawing_id_event
        UNIQUE (id, event_id),
    ADD CONSTRAINT fk_drawing_snapshot_contract
        FOREIGN KEY (snapshot_id, event_id, draw_method, algorithm_version)
        REFERENCES draw_snapshot (id, event_id, draw_method, algorithm_version);

ALTER TABLE winner
    ADD CONSTRAINT fk_winner_drawing_event
        FOREIGN KEY (drawing_id, event_id)
        REFERENCES drawing (id, event_id);

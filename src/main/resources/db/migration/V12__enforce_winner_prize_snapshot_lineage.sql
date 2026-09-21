-- Winner의 상품이 Drawing이 참조하는 공식 Snapshot 소속임을 DB에서도 강제한다.
ALTER TABLE winner
    ADD COLUMN snapshot_id BIGINT NULL AFTER drawing_id;

-- 이미 상품이 배정된 데이터가 있다면 연결된 Drawing의 공식 Snapshot으로 계보를 보강한다.
UPDATE winner w
JOIN drawing d ON d.id = w.drawing_id
SET w.snapshot_id = d.snapshot_id
WHERE w.snapshot_prize_id IS NOT NULL;

ALTER TABLE drawing
    ADD CONSTRAINT uk_drawing_winner_snapshot_lineage
        UNIQUE (id, event_id, snapshot_id);

ALTER TABLE draw_snapshot_prize
    ADD CONSTRAINT uk_snapshot_prize_lineage
        UNIQUE (id, snapshot_id);

ALTER TABLE winner
    ADD CONSTRAINT ck_winner_prize_snapshot_fields CHECK (
        (snapshot_id IS NULL
            AND snapshot_prize_id IS NULL
            AND prize_key IS NULL
            AND prize_display_name IS NULL
            AND prize_priority IS NULL)
        OR
        (snapshot_id IS NOT NULL
            AND snapshot_prize_id IS NOT NULL
            AND prize_key IS NOT NULL
            AND prize_display_name IS NOT NULL
            AND prize_priority IS NOT NULL)
    ),
    ADD CONSTRAINT fk_winner_drawing_snapshot_lineage
        FOREIGN KEY (drawing_id, event_id, snapshot_id)
        REFERENCES drawing (id, event_id, snapshot_id),
    ADD CONSTRAINT fk_winner_snapshot_prize_lineage
        FOREIGN KEY (snapshot_prize_id, snapshot_id)
        REFERENCES draw_snapshot_prize (id, snapshot_id);

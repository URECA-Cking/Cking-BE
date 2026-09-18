-- Drawing이 공식 Snapshot에 보존된 상품 배정 알고리즘 버전을 그대로 사용하도록 DB에서도 강제한다.
ALTER TABLE draw_snapshot
    ADD CONSTRAINT uk_snapshot_prize_drawing_contract
        UNIQUE (id, event_id, draw_method, algorithm_version, prize_algorithm_version);

ALTER TABLE drawing
    ADD CONSTRAINT fk_drawing_snapshot_prize_contract
        FOREIGN KEY (snapshot_id, event_id, draw_method, algorithm_version, prize_algorithm_version)
        REFERENCES draw_snapshot (id, event_id, draw_method, algorithm_version, prize_algorithm_version);

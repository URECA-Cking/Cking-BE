-- Event에서 후보 선정 방식과 상품 배정 방식을 독립적으로 선택하고 Snapshot에 확정한다.
ALTER TABLE event
    ADD COLUMN prize_algorithm_version VARCHAR(30) NOT NULL DEFAULT 'PRIZE_WEIGHTED_V1'
        COMMENT '당첨자 상품 배정 알고리즘' AFTER draw_method;

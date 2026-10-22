-- 기존 Winner 상태 전이는 모두 SELECTED에서 종결 상태로만 이동했다.
ALTER TABLE winner_status_history
    ADD COLUMN previous_status VARCHAR(30) NULL AFTER status;

UPDATE winner_status_history
SET previous_status = 'SELECTED';

ALTER TABLE winner_status_history
    MODIFY COLUMN previous_status VARCHAR(30) NOT NULL;

-- 같은 시각의 이력도 생성 순서대로 안정적으로 반환한다.
CREATE INDEX idx_winner_hist_mgmt_created_id
    ON winner_status_history (winner_management_id, created_at, id);

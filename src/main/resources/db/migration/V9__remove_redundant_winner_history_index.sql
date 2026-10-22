-- V8에서 생성한 복합 인덱스가 기존 인덱스의 선행 컬럼을 포함하므로 중복 인덱스를 제거한다.
DROP INDEX idx_winner_hist_mgmt ON winner_status_history;

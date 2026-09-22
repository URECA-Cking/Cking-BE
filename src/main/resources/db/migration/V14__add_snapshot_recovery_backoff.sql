-- 반복 실패하는 CLOSED Event가 Snapshot 복구 배치를 독점하지 않도록 실패 백오프 상태를 보관한다.
CREATE TABLE snapshot_recovery_failure (
    event_id         BIGINT      NOT NULL,
    failure_count    INT         NOT NULL,
    next_attempt_at  DATETIME(6) NOT NULL,
    failure_code     VARCHAR(50) NULL,
    failure_message  TEXT        NULL,
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT fk_snapshot_recovery_failure_event
        FOREIGN KEY (event_id) REFERENCES event (event_id),
    CONSTRAINT ck_snapshot_recovery_failure_count CHECK (failure_count > 0),
    INDEX idx_snapshot_recovery_failure_next_attempt (next_attempt_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- CLOSED Snapshot 누락 복구 조회: 상태·마감 시각 순서 및 범위 검색을 지원한다.
ALTER TABLE event
    ADD INDEX idx_event_status_closed (status, closed_at);

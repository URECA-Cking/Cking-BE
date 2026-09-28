-- 크킹 초기 스키마
--
-- ENUM 컬럼은 VARCHAR로 만든다. JPA @Enumerated(STRING)이 VARCHAR를 기대하므로
-- 네이티브 ENUM을 쓰면 ddl-auto=validate가 실패하고, 값 추가 시 ALTER TABLE이 필요하다.
-- 값 제한은 애플리케이션 enum이 담당한다.
--
-- 시각은 DATETIME(6)으로 둔다. 명세 2.5의 startAt <= now < endAt 경계 판정과
-- endAt / closedAt 구분에 초 단위보다 높은 정밀도가 필요하다.
--
-- charset/collation은 테이블마다 명시한다. 서버 기본값에 의존하면 CI나 RDS 파라미터
-- 그룹의 기본값이 다를 때 오류 없이 다른 charset으로 생성된다.

-- =====================================================================
-- 1. member
-- =====================================================================
CREATE TABLE member (
    member_id   BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(50)  NOT NULL,
    phone       VARCHAR(20)  NULL,
    email       VARCHAR(255) NULL,
    role        VARCHAR(20)  NOT NULL COMMENT 'USER, ADMIN',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 2. creator
-- =====================================================================
CREATE TABLE creator (
    creator_id BIGINT      NOT NULL AUTO_INCREMENT,
    member_id  BIGINT      NOT NULL,
    name       VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (creator_id),
    CONSTRAINT uk_creator_member UNIQUE (member_id),
    CONSTRAINT fk_creator_member FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 3. creator_application
-- =====================================================================
CREATE TABLE creator_application (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    member_id     BIGINT       NOT NULL,
    status        VARCHAR(30)  NOT NULL COMMENT 'PENDING, APPROVED, REJECTED',
    reject_reason VARCHAR(500) NULL,
    requested_at  DATETIME(6)  NOT NULL,
    reviewed_by   BIGINT       NULL,
    reviewed_at   DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_creator_app_member   FOREIGN KEY (member_id)   REFERENCES member (member_id),
    CONSTRAINT fk_creator_app_reviewer FOREIGN KEY (reviewed_by) REFERENCES member (member_id),
    INDEX idx_creator_app_member_status (member_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 4. mission
-- =====================================================================
CREATE TABLE mission (
    mission_id    BIGINT      NOT NULL AUTO_INCREMENT,
    creator_id    BIGINT      NOT NULL,
    type          VARCHAR(30) NOT NULL COMMENT 'ATTENDANCE, LIKE',
    reward_amount INT         NOT NULL,
    active_from   DATETIME(6) NULL,
    active_to     DATETIME(6) NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (mission_id),
    CONSTRAINT uk_mission_creator_type UNIQUE (creator_id, type),
    CONSTRAINT fk_mission_creator FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    CONSTRAINT ck_mission_reward CHECK (reward_amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 5. mission_completion
-- =====================================================================
CREATE TABLE mission_completion (
    completion_id BIGINT      NOT NULL AUTO_INCREMENT,
    member_id     BIGINT      NOT NULL,
    creator_id    BIGINT      NOT NULL,
    mission_id    BIGINT      NOT NULL,
    period_key    VARCHAR(10) NOT NULL COMMENT 'YYYY-MM-DD',
    request_id    VARCHAR(36) NOT NULL COMMENT '멱등 요청 ID (UUID)',
    completed_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (completion_id),
    CONSTRAINT uk_completion_request UNIQUE (request_id),
    -- 명세 4.2 Business Key. 같은 날 중복 보상을 막는 최종 방어선이다.
    CONSTRAINT uk_completion_business UNIQUE (member_id, creator_id, mission_id, period_key),
    CONSTRAINT fk_completion_member  FOREIGN KEY (member_id)  REFERENCES member (member_id),
    CONSTRAINT fk_completion_creator FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    CONSTRAINT fk_completion_mission FOREIGN KEY (mission_id) REFERENCES mission (mission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 6. user_ticket_balance
-- =====================================================================
CREATE TABLE user_ticket_balance (
    member_id  BIGINT      NOT NULL,
    creator_id BIGINT      NOT NULL,
    balance    BIGINT      NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (member_id, creator_id),
    CONSTRAINT fk_balance_member  FOREIGN KEY (member_id)  REFERENCES member (member_id),
    CONSTRAINT fk_balance_creator FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    -- 명세 15.1 불변식: Balance >= 0
    CONSTRAINT ck_balance_non_negative CHECK (balance >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 8. event   (ticket_ledger가 event_entry를 참조하므로 event/event_entry를 먼저 만든다)
-- =====================================================================
CREATE TABLE event (
    event_id         BIGINT       NOT NULL AUTO_INCREMENT,
    creator_id       BIGINT       NOT NULL,
    title            VARCHAR(200) NOT NULL,
    description      TEXT         NULL,
    start_at         DATETIME(6)  NOT NULL,
    end_at           DATETIME(6)  NOT NULL,
    winner_count     INT          NOT NULL,
    draw_method      VARCHAR(30)  NOT NULL COMMENT 'WEIGHTED',
    status           VARCHAR(30)  NOT NULL COMMENT 'DRAFT, PENDING_APPROVAL, REJECTED, SCHEDULED, OPEN, CLOSING, CLOSED, DRAW_COMPLETED, PUBLISHED',
    cutoff_stream_id VARCHAR(64)  NULL COMMENT '마감 경계 Redis Stream ID',
    closed_at        DATETIME(6)  NULL,
    published_at     DATETIME(6)  NULL,
    deleted_at       DATETIME(6)  NULL,
    created_by       BIGINT       NOT NULL,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id),
    CONSTRAINT fk_event_creator FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    CONSTRAINT fk_event_member  FOREIGN KEY (created_by) REFERENCES member (member_id),
    CONSTRAINT ck_event_winner_count CHECK (winner_count > 0),
    CONSTRAINT ck_event_period       CHECK (start_at < end_at),
    -- 명세 3.7 자동 시작 스케줄러: status = SCHEDULED AND start_at <= now < end_at
    INDEX idx_event_status_start (status, start_at),
    -- 명세 6.2 마감 스캐너(10초 주기): status = OPEN AND end_at <= now
    INDEX idx_event_status_end (status, end_at),
    -- 명세 3.9 목록 조회: deleted_at IS NULL 인 것만
    INDEX idx_event_creator_deleted (creator_id, deleted_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 9. event_approval_request
-- =====================================================================
CREATE TABLE event_approval_request (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    event_id       BIGINT       NOT NULL,
    approval_round INT          NOT NULL,
    status         VARCHAR(30)  NOT NULL COMMENT 'PENDING, APPROVED, REJECTED',
    requested_by   BIGINT       NOT NULL,
    requested_at   DATETIME(6)  NOT NULL,
    reviewed_by    BIGINT       NULL,
    reviewed_at    DATETIME(6)  NULL,
    reject_reason  VARCHAR(500) NULL,
    PRIMARY KEY (id),
    -- 승인 기록을 UPDATE로 덮지 않고 차수별 이력으로 보존한다.
    CONSTRAINT uk_approval_event_round UNIQUE (event_id, approval_round),
    CONSTRAINT fk_approval_event     FOREIGN KEY (event_id)     REFERENCES event (event_id),
    CONSTRAINT fk_approval_requester FOREIGN KEY (requested_by) REFERENCES member (member_id),
    CONSTRAINT fk_approval_reviewer  FOREIGN KEY (reviewed_by)  REFERENCES member (member_id),
    CONSTRAINT ck_approval_round CHECK (approval_round > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 10. event_entry
-- =====================================================================
CREATE TABLE event_entry (
    entry_id          BIGINT      NOT NULL AUTO_INCREMENT,
    member_id         BIGINT      NOT NULL,
    event_id          BIGINT      NOT NULL,
    request_id        VARCHAR(36) NOT NULL COMMENT '멱등 요청 ID (UUID)',
    used_ticket_count BIGINT      NOT NULL,
    applied_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (entry_id),
    -- 명세 5.6: Stream at-least-once 재전달의 최종 방어선
    CONSTRAINT uk_entry_request UNIQUE (request_id),
    CONSTRAINT fk_entry_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_entry_event  FOREIGN KEY (event_id)  REFERENCES event (event_id),
    CONSTRAINT ck_entry_ticket_count CHECK (used_ticket_count >= 1),
    -- 명세 7.2 Snapshot 후보 집계: event_id로 모아 member_id별 SUM
    INDEX idx_entry_event_member (event_id, member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 7. ticket_ledger
-- =====================================================================
CREATE TABLE ticket_ledger (
    ledger_id             BIGINT       NOT NULL AUTO_INCREMENT,
    member_id             BIGINT       NOT NULL,
    creator_id            BIGINT       NOT NULL,
    event_entry_id        BIGINT       NULL COMMENT 'SPEND 원인',
    mission_completion_id BIGINT       NULL COMMENT 'EARN 원인',
    delta_amount          BIGINT       NOT NULL,
    type                  VARCHAR(30)  NOT NULL COMMENT 'EARN, SPEND, COMPENSATE',
    reason                VARCHAR(500) NULL,
    request_id            VARCHAR(36)  NULL COMMENT 'COMPENSATE는 요청 없이 배치가 기록하므로 NULL 허용',
    balance_before        BIGINT       NULL,
    balance_after         BIGINT       NULL,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (ledger_id),
    CONSTRAINT uk_ledger_request UNIQUE (request_id),
    CONSTRAINT fk_ledger_member     FOREIGN KEY (member_id)             REFERENCES member (member_id),
    CONSTRAINT fk_ledger_creator    FOREIGN KEY (creator_id)            REFERENCES creator (creator_id),
    CONSTRAINT fk_ledger_entry      FOREIGN KEY (event_entry_id)        REFERENCES event_entry (entry_id),
    CONSTRAINT fk_ledger_completion FOREIGN KEY (mission_completion_id) REFERENCES mission_completion (completion_id),
    -- 타입별 원인 컬럼 조합을 강제한다.
    CONSTRAINT ck_ledger_type_source CHECK (
        (type = 'EARN'       AND mission_completion_id IS NOT NULL AND event_entry_id IS NULL)
     OR (type = 'SPEND'      AND event_entry_id        IS NOT NULL AND mission_completion_id IS NULL)
     OR (type = 'COMPENSATE')
    ),
    -- 명세 13.2 정합성 검증 배치(5분 주기)의 Ledger 합계 조회
    INDEX idx_ledger_member_creator (member_id, creator_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 11. draw_snapshot
-- =====================================================================
CREATE TABLE draw_snapshot (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    event_id            BIGINT      NOT NULL,
    candidate_count     INT         NOT NULL,
    total_ticket_count  BIGINT      NOT NULL,
    winner_count        INT         NOT NULL,
    draw_method         VARCHAR(30) NOT NULL COMMENT 'WEIGHTED',
    algorithm_version   VARCHAR(30) NOT NULL COMMENT 'WEIGHTED_V1',
    snapshot_hash       VARCHAR(64) NOT NULL COMMENT 'SHA-256 hex',
    verification_status VARCHAR(30) NOT NULL COMMENT 'UNVERIFIED, VERIFIED, INVALID',
    verified_at         DATETIME(6) NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- 명세 7.3: Event당 공식 Snapshot 하나. 동시 INSERT의 최종 방어선이다.
    CONSTRAINT uk_snapshot_event UNIQUE (event_id),
    CONSTRAINT fk_snapshot_event FOREIGN KEY (event_id) REFERENCES event (event_id),
    CONSTRAINT ck_snapshot_candidate_count CHECK (candidate_count >= 0),
    CONSTRAINT ck_snapshot_total_ticket    CHECK (total_ticket_count >= 0),
    CONSTRAINT ck_snapshot_winner_count    CHECK (winner_count > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 12. draw_snapshot_candidate
-- =====================================================================
CREATE TABLE draw_snapshot_candidate (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    snapshot_id  BIGINT      NOT NULL,
    member_id    BIGINT      NOT NULL,
    ticket_count BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_candidate_snapshot_member UNIQUE (snapshot_id, member_id),
    CONSTRAINT fk_candidate_snapshot FOREIGN KEY (snapshot_id) REFERENCES draw_snapshot (id),
    CONSTRAINT fk_candidate_member   FOREIGN KEY (member_id)   REFERENCES member (member_id),
    -- 명세 7.2: ticket_count <= 0 후보는 Snapshot에 포함하지 않는다.
    CONSTRAINT ck_candidate_ticket_count CHECK (ticket_count > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 13. draw_seed
-- =====================================================================
CREATE TABLE draw_seed (
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    seed_value VARBINARY(255) NOT NULL,
    created_at DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 14. drawing
--
-- drawing.redraw_request_id -> redraw_request.id 와
-- redraw_request.original_drawing_id -> drawing.id 가 서로를 참조하므로,
-- drawing을 먼저 만들고 해당 FK는 redraw_request 생성 후 ALTER로 추가한다.
-- =====================================================================
CREATE TABLE drawing (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    event_id            BIGINT      NOT NULL,
    draw_no             INT         NOT NULL COMMENT '최초 추첨은 0',
    draw_type           VARCHAR(30) NOT NULL COMMENT 'INITIAL, REDRAW',
    original_drawing_id BIGINT      NULL,
    redraw_request_id   BIGINT      NULL,
    snapshot_id         BIGINT      NOT NULL,
    seed_id             BIGINT      NOT NULL,
    draw_method         VARCHAR(30) NOT NULL COMMENT 'WEIGHTED',
    algorithm_version   VARCHAR(30) NOT NULL COMMENT 'WEIGHTED_V1',
    winner_count        INT         NOT NULL,
    status              VARCHAR(30) NOT NULL COMMENT 'READY, RUNNING, FAILED, COMPLETED',
    visibility          VARCHAR(30) NOT NULL COMMENT 'PRIVATE, PUBLIC',
    input_payload       TEXT        NULL,
    output_payload      TEXT        NULL,
    input_hash          VARCHAR(64) NULL,
    result_hash         VARCHAR(64) NULL,
    requested_by        BIGINT      NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    first_started_at    DATETIME(6) NULL,
    completed_at        DATETIME(6) NULL,
    published_at        DATETIME(6) NULL,
    attempt_count       INT         NOT NULL DEFAULT 0,
    version             BIGINT      NOT NULL DEFAULT 0 COMMENT 'JPA @Version 낙관적 락',
    PRIMARY KEY (id),
    -- 명세 10.1: Event당 INITIAL Drawing 하나 (draw_no = 0)
    CONSTRAINT uk_drawing_event_no   UNIQUE (event_id, draw_no),
    CONSTRAINT uk_drawing_seed       UNIQUE (seed_id),
    CONSTRAINT uk_drawing_redraw_req UNIQUE (redraw_request_id),
    CONSTRAINT fk_drawing_event     FOREIGN KEY (event_id)            REFERENCES event (event_id),
    CONSTRAINT fk_drawing_original  FOREIGN KEY (original_drawing_id) REFERENCES drawing (id),
    CONSTRAINT fk_drawing_snapshot  FOREIGN KEY (snapshot_id)         REFERENCES draw_snapshot (id),
    CONSTRAINT fk_drawing_seed      FOREIGN KEY (seed_id)             REFERENCES draw_seed (id),
    CONSTRAINT fk_drawing_requester FOREIGN KEY (requested_by)        REFERENCES member (member_id),
    CONSTRAINT ck_drawing_winner_count  CHECK (winner_count > 0),
    CONSTRAINT ck_drawing_attempt_count CHECK (attempt_count >= 0),
    -- 타입별 필수 조합. INITIAL과 REDRAW의 구조가 섞이는 것을 막는다.
    CONSTRAINT ck_drawing_type CHECK (
        (draw_type = 'INITIAL' AND draw_no = 0 AND original_drawing_id IS NULL     AND redraw_request_id IS NULL)
     OR (draw_type = 'REDRAW'  AND draw_no > 0 AND original_drawing_id IS NOT NULL AND redraw_request_id IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 19. redraw_request
-- =====================================================================
CREATE TABLE redraw_request (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    event_id            BIGINT       NOT NULL,
    original_drawing_id BIGINT       NOT NULL,
    vacancy_count       INT          NOT NULL,
    reason              VARCHAR(500) NULL,
    idempotency_key     VARCHAR(100) NOT NULL,
    status              VARCHAR(30)  NOT NULL COMMENT 'REQUESTED, APPROVED, REJECTED',
    execution_status    VARCHAR(30)  NOT NULL COMMENT 'PENDING, EXECUTED, INSUFFICIENT_CANDIDATES, FAILED',
    requested_by        BIGINT       NOT NULL,
    requested_at        DATETIME(6)  NOT NULL,
    reviewed_by         BIGINT       NULL,
    reviewed_at         DATETIME(6)  NULL,
    reject_reason       VARCHAR(500) NULL,
    completed_at        DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_redraw_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_redraw_event     FOREIGN KEY (event_id)            REFERENCES event (event_id),
    CONSTRAINT fk_redraw_original  FOREIGN KEY (original_drawing_id) REFERENCES drawing (id),
    CONSTRAINT fk_redraw_requester FOREIGN KEY (requested_by)        REFERENCES member (member_id),
    CONSTRAINT fk_redraw_reviewer  FOREIGN KEY (reviewed_by)         REFERENCES member (member_id),
    CONSTRAINT ck_redraw_vacancy CHECK (vacancy_count > 0),
    INDEX idx_redraw_event_status (event_id, status, execution_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 순환 참조 해소: drawing -> redraw_request
ALTER TABLE drawing
    ADD CONSTRAINT fk_drawing_redraw_request
    FOREIGN KEY (redraw_request_id) REFERENCES redraw_request (id);

-- =====================================================================
-- 15. draw_attempt_history
-- =====================================================================
CREATE TABLE draw_attempt_history (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    drawing_id      BIGINT       NOT NULL,
    attempt_no      INT          NOT NULL,
    status          VARCHAR(30)  NOT NULL COMMENT 'STARTED, FAILED, SUCCEEDED',
    failure_stage   VARCHAR(100) NULL,
    failure_code    VARCHAR(50)  NULL,
    failure_message TEXT         NULL,
    requested_by    BIGINT       NULL,
    started_at      DATETIME(6)  NOT NULL,
    finished_at     DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_attempt_drawing_no UNIQUE (drawing_id, attempt_no),
    CONSTRAINT fk_attempt_drawing   FOREIGN KEY (drawing_id)   REFERENCES drawing (id),
    CONSTRAINT fk_attempt_requester FOREIGN KEY (requested_by) REFERENCES member (member_id),
    CONSTRAINT ck_attempt_no CHECK (attempt_no > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 16. winner
-- =====================================================================
CREATE TABLE winner (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    event_id             BIGINT      NOT NULL,
    drawing_id           BIGINT      NOT NULL,
    member_id            BIGINT      NOT NULL,
    rank_in_drawing      INT         NOT NULL,
    applied_ticket_count BIGINT      NOT NULL,
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_winner_drawing_rank UNIQUE (drawing_id, rank_in_drawing),
    -- 명세 12.6: 한 번 Winner가 된 Member는 REDRAW에서 다시 당첨될 수 없다.
    CONSTRAINT uk_winner_event_member UNIQUE (event_id, member_id),
    CONSTRAINT fk_winner_event   FOREIGN KEY (event_id)   REFERENCES event (event_id),
    CONSTRAINT fk_winner_drawing FOREIGN KEY (drawing_id) REFERENCES drawing (id),
    CONSTRAINT fk_winner_member  FOREIGN KEY (member_id)  REFERENCES member (member_id),
    CONSTRAINT ck_winner_rank   CHECK (rank_in_drawing > 0),
    CONSTRAINT ck_winner_ticket CHECK (applied_ticket_count > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 17. winner_management
-- =====================================================================
CREATE TABLE winner_management (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    winner_id  BIGINT      NOT NULL,
    status     VARCHAR(30) NOT NULL COMMENT 'SELECTED, RECEIVED, DECLINED, DISQUALIFIED',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_winner_mgmt_winner UNIQUE (winner_id),
    CONSTRAINT fk_winner_mgmt_winner FOREIGN KEY (winner_id) REFERENCES winner (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 18. winner_status_history
-- =====================================================================
CREATE TABLE winner_status_history (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    winner_management_id BIGINT       NOT NULL,
    status               VARCHAR(30)  NOT NULL,
    reason               VARCHAR(500) NULL,
    changed_by           BIGINT       NULL,
    created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_winner_hist_mgmt    FOREIGN KEY (winner_management_id) REFERENCES winner_management (id),
    CONSTRAINT fk_winner_hist_changer FOREIGN KEY (changed_by)           REFERENCES member (member_id),
    INDEX idx_winner_hist_mgmt (winner_management_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 20. redraw_request_vacancy
-- =====================================================================
CREATE TABLE redraw_request_vacancy (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    redraw_request_id BIGINT      NOT NULL,
    winner_id         BIGINT      NOT NULL,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- 동일 요청 내부 중복 방어. 진행 중 요청 간 동시 점유는 Service Transaction이 보장한다.
    CONSTRAINT uk_vacancy_request_winner UNIQUE (redraw_request_id, winner_id),
    CONSTRAINT fk_vacancy_request FOREIGN KEY (redraw_request_id) REFERENCES redraw_request (id),
    CONSTRAINT fk_vacancy_winner  FOREIGN KEY (winner_id)         REFERENCES winner (id),
    INDEX idx_vacancy_winner (winner_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 21. redraw_exclusion
-- =====================================================================
CREATE TABLE redraw_exclusion (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    drawing_id       BIGINT      NOT NULL,
    member_id        BIGINT      NOT NULL,
    exclusion_reason VARCHAR(30) NOT NULL COMMENT 'ALREADY_WINNER, DECLINED, DISQUALIFIED',
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_exclusion_drawing_member UNIQUE (drawing_id, member_id),
    CONSTRAINT fk_exclusion_drawing FOREIGN KEY (drawing_id) REFERENCES drawing (id),
    CONSTRAINT fk_exclusion_member  FOREIGN KEY (member_id)  REFERENCES member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 22. redraw_execution_history
-- =====================================================================
CREATE TABLE redraw_execution_history (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    redraw_request_id BIGINT      NOT NULL,
    execution_status  VARCHAR(30) NOT NULL,
    failure_code      VARCHAR(50) NULL,
    failure_message   TEXT        NULL,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_redraw_hist_request FOREIGN KEY (redraw_request_id) REFERENCES redraw_request (id),
    INDEX idx_redraw_hist_request (redraw_request_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 23. draw_verification_history
-- =====================================================================
CREATE TABLE draw_verification_history (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    drawing_id            BIGINT      NOT NULL,
    status                VARCHAR(30) NOT NULL COMMENT 'VERIFIED, VERIFICATION_FAILED',
    snapshot_hash_matched BOOLEAN     NOT NULL,
    input_hash_matched    BOOLEAN     NOT NULL,
    result_hash_matched   BOOLEAN     NOT NULL,
    algorithm_matched     BOOLEAN     NOT NULL,
    failure_code          VARCHAR(50) NULL,
    failure_message       TEXT        NULL,
    verified_by           BIGINT      NULL,
    verified_at           DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_verification_drawing  FOREIGN KEY (drawing_id)  REFERENCES drawing (id),
    CONSTRAINT fk_verification_verifier FOREIGN KEY (verified_by) REFERENCES member (member_id),
    INDEX idx_verification_drawing (drawing_id, verified_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 24. notification
-- =====================================================================
CREATE TABLE notification (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    member_id  BIGINT       NOT NULL,
    event_id   BIGINT       NOT NULL,
    drawing_id BIGINT       NOT NULL,
    winner_id  BIGINT       NOT NULL,
    type       VARCHAR(30)  NOT NULL COMMENT 'INITIAL_WINNER, REDRAW_WINNER',
    title      VARCHAR(200) NOT NULL,
    body       TEXT         NOT NULL,
    read_at    DATETIME(6)  NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- 명세 11.3: 동일 공개 요청이 중복 수행되어도 알림은 하나만 생성된다.
    CONSTRAINT uk_notification_winner_type UNIQUE (winner_id, type),
    CONSTRAINT fk_notification_member  FOREIGN KEY (member_id)  REFERENCES member (member_id),
    CONSTRAINT fk_notification_event   FOREIGN KEY (event_id)   REFERENCES event (event_id),
    CONSTRAINT fk_notification_drawing FOREIGN KEY (drawing_id) REFERENCES drawing (id),
    CONSTRAINT fk_notification_winner  FOREIGN KEY (winner_id)  REFERENCES winner (id),
    -- 사용자별 알림 목록 조회 (읽음 여부 포함)
    INDEX idx_notification_member_read (member_id, read_at, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 25. dead_stream_message
-- =====================================================================
CREATE TABLE dead_stream_message (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    source_stream_id  VARCHAR(64) NOT NULL COMMENT '원본 Redis Stream 메시지 ID',
    stream_type       VARCHAR(30) NOT NULL COMMENT 'EARN, SPEND',
    payload           JSON        NOT NULL COMMENT '원본 Stream 메시지 전체. 수동 replay에 사용한다',
    request_id        VARCHAR(36) NULL,
    event_id          BIGINT      NULL,
    member_id         BIGINT      NULL,
    failure_reason    TEXT        NULL,
    retry_count       INT         NOT NULL DEFAULT 0,
    last_failed_at    DATETIME(6) NULL,
    resolution_status VARCHAR(30) NOT NULL DEFAULT 'UNRESOLVED' COMMENT 'UNRESOLVED, RESOLVED',
    resolved_by       BIGINT      NULL,
    resolved_at       DATETIME(6) NULL,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- A안: 동일 원본 메시지는 하나의 row로 유지하고 retry_count를 증가시킨다.
    CONSTRAINT uk_dead_stream_source UNIQUE (source_stream_id, stream_type),
    CONSTRAINT fk_dead_stream_event    FOREIGN KEY (event_id)    REFERENCES event (event_id),
    CONSTRAINT fk_dead_stream_member   FOREIGN KEY (member_id)   REFERENCES member (member_id),
    CONSTRAINT fk_dead_stream_resolver FOREIGN KEY (resolved_by) REFERENCES member (member_id),
    CONSTRAINT ck_dead_stream_retry CHECK (retry_count >= 0),
    -- 명세 6.7: cutoff 범위에 UNRESOLVED가 있으면 CLOSED로 전이할 수 없다.
    INDEX idx_dead_stream_event_status (event_id, resolution_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

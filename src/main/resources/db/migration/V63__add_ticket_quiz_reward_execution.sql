-- Quiz 보상 Redis EARN 실행 이력 (이슈 #533)
--
-- 지급 요청(ticket_quiz_reward_request) 하나의 Redis 실행 시도마다 한 줄이다. 실행권(execution_token)은 Redis 호출 전에
-- 이 행으로 발급·커밋한다. 기존 행은 덮어쓰거나 지우지 않고, 다시 실행하면 execution_seq를 하나 늘린 새 행을 만든다.
--
-- execution_token: 실행권. 서버가 만든 소문자 UUID이며 전역에서 유일하다. Redis guard·Stream 메시지의 executionToken과 같다.
-- execution_seq  : 같은 요청 안의 실행 순번. 1부터 시작한다.
-- status
--   STARTED  : 실행권을 발급했다. Redis 결과는 아직 기록하지 않았다.
--   FINISHED : Redis 결과를 기록했다. finished_at·outcome이 있다.
-- outcome        : Redis 실행 결과 분류(QuizRedisEarnOutcome). FINISHED에만 있다.
-- reason         : Lua가 알려준 안전한 사유 코드. Redis 원본 오류 메시지는 저장하지 않는다.
-- stream_id      : ACCEPTED·ALREADY_ACCEPTED의 Stream ID.
-- not_executed_confirmed: 이 실행이 Redis를 바꾸지 않았음이 확인됐는지. 확인된 무반영 결과에만 TRUE일 수 있다.
--
-- started_request_id: STARTED일 때만 요청 ID가 생기는 칼럼. MySQL은 부분 UNIQUE가 없어 여기에 UNIQUE를 걸어,
--   한 요청에 결과가 기록되지 않은 실행권이 동시에 둘 이상 생기지 않게 한다.
--
-- 기존 테이블은 바꾸지 않는다.
CREATE TABLE ticket_quiz_reward_execution (
    quiz_reward_execution_id BIGINT      NOT NULL AUTO_INCREMENT,
    quiz_reward_request_id   BIGINT      NOT NULL,
    execution_seq            INT         NOT NULL,
    execution_token          CHAR(36)    NOT NULL,
    status                   VARCHAR(20) NOT NULL,
    outcome                  VARCHAR(30) NULL,
    reason                   VARCHAR(64) NULL,
    stream_id                VARCHAR(41) NULL,
    not_executed_confirmed   BOOLEAN     NOT NULL DEFAULT FALSE,
    started_at               DATETIME(6) NOT NULL,
    finished_at              DATETIME(6) NULL,
    started_request_id       BIGINT GENERATED ALWAYS AS (
        CASE WHEN status = 'STARTED' THEN quiz_reward_request_id ELSE NULL END) STORED,
    PRIMARY KEY (quiz_reward_execution_id),
    CONSTRAINT uk_ticket_quiz_reward_execution_token UNIQUE (execution_token),
    CONSTRAINT uk_ticket_quiz_reward_execution_seq UNIQUE (quiz_reward_request_id, execution_seq),
    CONSTRAINT uk_ticket_quiz_reward_execution_started UNIQUE (started_request_id),
    CONSTRAINT fk_ticket_quiz_reward_execution_request FOREIGN KEY (quiz_reward_request_id)
        REFERENCES ticket_quiz_reward_request (quiz_reward_request_id),
    CONSTRAINT ck_ticket_quiz_reward_execution_seq CHECK (execution_seq > 0),
    CONSTRAINT ck_ticket_quiz_reward_execution_token CHECK (
        REGEXP_LIKE(execution_token, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_ticket_quiz_reward_execution_status CHECK (status IN ('STARTED', 'FINISHED')),
    CONSTRAINT ck_ticket_quiz_reward_execution_finished CHECK (
        (status = 'FINISHED') = (finished_at IS NOT NULL) AND (status = 'FINISHED') = (outcome IS NOT NULL)),
    CONSTRAINT ck_ticket_quiz_reward_execution_started CHECK (
        status = 'FINISHED' OR (reason IS NULL AND stream_id IS NULL AND not_executed_confirmed = FALSE)),
    CONSTRAINT ck_ticket_quiz_reward_execution_outcome CHECK (outcome IS NULL OR outcome IN ('ACCEPTED',
        'ALREADY_ACCEPTED', 'REQUEST_ID_CONFLICT', 'BALANCE_MAINTENANCE', 'BALANCE_NOT_LOADED', 'INVALID_REQUEST',
        'RETRYABLE_FAILURE', 'UNKNOWN')),
    CONSTRAINT ck_ticket_quiz_reward_execution_stream CHECK (
        (outcome IN ('ACCEPTED', 'ALREADY_ACCEPTED')) = (stream_id IS NOT NULL)),
    -- BOOLEAN은 TINYINT(1)의 별칭이라 2·-1도 들어갈 수 있다. 0·1만 허용한다.
    CONSTRAINT ck_ticket_quiz_reward_execution_not_executed_value CHECK (not_executed_confirmed IN (0, 1)),
    CONSTRAINT ck_ticket_quiz_reward_execution_not_executed CHECK (not_executed_confirmed = FALSE
        OR outcome IN ('REQUEST_ID_CONFLICT', 'BALANCE_MAINTENANCE', 'BALANCE_NOT_LOADED', 'INVALID_REQUEST',
            'RETRYABLE_FAILURE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

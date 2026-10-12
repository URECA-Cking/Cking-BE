-- Quiz revision GPT Pointwise Judge 평가 (이슈 #529)
--
-- quiz_judge_series: revision 하나의 평가 묶음. revision마다 하나이며 첫 평가 요청 때 만든다.
--   첫 요청의 Judge 입력 hash와 실행 설정 snapshot을 고정한다. 설정이 바뀌어도 새 series를 만들지 않으므로
--   allocated_attempts(기술적 시도 횟수, 최대 2)가 초기화되지 않는다.
-- quiz_judge_job: 평가 시도(attempt) 하나. Creator의 requestId 하나에 한 행이다.
--   status
--     PENDING   : 접수했고 Worker가 아직 점유하지 않았다.
--     RUNNING   : Worker가 점유해 Provider를 호출하고 있다.
--     SUCCEEDED : 유효한 평가 결과를 저장했다. 판정이 pass라는 뜻은 아니다(quiz_judge_result.aggregation).
--     FAILED    : 결과가 확정된 실패. failure_code가 있다.
--     UNKNOWN   : 시간 초과·중단·오래된 RUNNING으로 결과를 확정할 수 없다. failure_code가 있다.
--   허용 전이: PENDING → RUNNING·FAILED, RUNNING → SUCCEEDED·FAILED·UNKNOWN. 모두 조건부 UPDATE나 행 잠금으로 한다.
--   실행 기록(요청·응답 모델, HTTP 요청 수, 사용량 관측 여부, 추정 비용)은 끝난 뒤에 남긴다. 단가를 설정하지 않았으면
--   비용 컬럼은 모두 NULL이고, 계산할 수 있는 요청이 없으면 estimated_cost_usd만 NULL이다.
--   active_creator_id는 PENDING·RUNNING일 때만 creator_id인 생성 컬럼이라 Creator당 진행 중 Job을 하나로 제한한다.
-- quiz_judge_job_call: Job의 HTTP 요청 하나(최대 2). 재시도 비용이 사라지지 않도록 응답이 없던 요청도 남긴다.
--   Provider 응답 원문·API key는 저장하지 않는다.
-- quiz_judge_result: SUCCEEDED Job의 불변 평가 결과. Job·series마다 하나이며 결과가 생기면 series는 끝난다.
-- quiz_judge_result_item: 결과의 문항 × rubric 판정. 문항마다 7개 항목이 모두 있는지는 저장 전에 검증한다.
--
-- 복합 FK로 series·Job·결과가 같은 revision을 가리키게 한다.
-- quiz_revision_decision.judge_evaluation_id는 Job이 아니라 불변 결과(result_id)를 가리킨다. 이를 강제하는
-- (revision_id, judge_evaluation_id) → quiz_judge_result (revision_id, result_id) FK는 마지막 ALTER에서 추가한다.
-- uk_quiz_judge_result_revision이 그 참조 대상이다.
CREATE TABLE quiz_judge_series (
    series_id            BIGINT       NOT NULL AUTO_INCREMENT,
    quiz_id              BIGINT       NOT NULL,
    revision_id          BIGINT       NOT NULL,
    content_text_sha256  CHAR(64)     NOT NULL,
    revision_sha256      CHAR(64)     NOT NULL,
    input_sha256         CHAR(64)     NOT NULL,
    settings_fingerprint CHAR(64)     NOT NULL,
    model                VARCHAR(100) NOT NULL,
    reasoning_effort     VARCHAR(20)  NOT NULL,
    max_output_tokens    INT          NOT NULL,
    reason_max_length    INT          NOT NULL,
    prompt_version       VARCHAR(40)  NOT NULL,
    rubric_version       VARCHAR(40)  NOT NULL,
    schema_version       VARCHAR(40)  NOT NULL,
    policy_version       VARCHAR(40)  NOT NULL,
    instructions_sha256  CHAR(64)     NOT NULL,
    schema_sha256        CHAR(64)     NOT NULL,
    allocated_attempts   INT          NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (series_id),
    CONSTRAINT uk_quiz_judge_series_revision UNIQUE (revision_id),
    CONSTRAINT uk_quiz_judge_series_series_revision UNIQUE (series_id, revision_id),
    CONSTRAINT fk_quiz_judge_series_revision FOREIGN KEY (quiz_id, revision_id)
        REFERENCES quiz_revision (quiz_id, revision_id),
    CONSTRAINT ck_quiz_judge_series_attempts CHECK (allocated_attempts BETWEEN 0 AND 2),
    CONSTRAINT ck_quiz_judge_series_limits CHECK (max_output_tokens > 0 AND reason_max_length > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


CREATE TABLE quiz_judge_job (
    job_id                 BIGINT         NOT NULL AUTO_INCREMENT,
    series_id              BIGINT         NOT NULL,
    quiz_id                BIGINT         NOT NULL,
    revision_id            BIGINT         NOT NULL,
    creator_id             BIGINT         NOT NULL,
    requested_by_member_id BIGINT         NOT NULL,
    request_id             VARCHAR(36)    NOT NULL,
    attempt_no             INT            NOT NULL,
    status                 VARCHAR(20)    NOT NULL,
    failure_code           VARCHAR(40)    NULL,
    requested_at           DATETIME(6)    NOT NULL,
    started_at             DATETIME(6)    NULL,
    completed_at           DATETIME(6)    NULL,
    updated_at             DATETIME(6)    NOT NULL,
    requested_model        VARCHAR(100)   NULL,
    response_model         VARCHAR(100)   NULL,
    http_request_count     INT            NULL,
    usage_complete         BOOLEAN        NULL,
    estimated_cost_usd     DECIMAL(20, 10) NULL,
    pricing_version        VARCHAR(40)    NULL,
    cost_complete          BOOLEAN        NULL,
    active_creator_id      BIGINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING', 'RUNNING') THEN creator_id ELSE NULL END) VIRTUAL,
    PRIMARY KEY (job_id),
    CONSTRAINT uk_quiz_judge_job_request UNIQUE (creator_id, request_id),
    CONSTRAINT uk_quiz_judge_job_attempt UNIQUE (series_id, attempt_no),
    CONSTRAINT uk_quiz_judge_job_active_creator UNIQUE (active_creator_id),
    CONSTRAINT uk_quiz_judge_job_series UNIQUE (job_id, series_id),
    INDEX idx_quiz_judge_job_creator_status (creator_id, status, requested_at),
    CONSTRAINT fk_quiz_judge_job_series FOREIGN KEY (series_id, revision_id)
        REFERENCES quiz_judge_series (series_id, revision_id),
    CONSTRAINT fk_quiz_judge_job_revision FOREIGN KEY (quiz_id, revision_id)
        REFERENCES quiz_revision (quiz_id, revision_id),
    CONSTRAINT fk_quiz_judge_job_creator FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    CONSTRAINT fk_quiz_judge_job_member FOREIGN KEY (requested_by_member_id) REFERENCES member (member_id),
    CONSTRAINT ck_quiz_judge_job_attempt CHECK (attempt_no BETWEEN 1 AND 2),
    CONSTRAINT ck_quiz_judge_job_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_quiz_judge_job_failure CHECK ((status IN ('FAILED', 'UNKNOWN')) = (failure_code IS NOT NULL)),
    CONSTRAINT ck_quiz_judge_job_started CHECK (status IN ('PENDING', 'FAILED') OR started_at IS NOT NULL),
    CONSTRAINT ck_quiz_judge_job_completed
        CHECK ((status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')) = (completed_at IS NOT NULL)),
    CONSTRAINT ck_quiz_judge_job_requests CHECK (http_request_count IS NULL OR http_request_count BETWEEN 0 AND 2),
    CONSTRAINT ck_quiz_judge_job_pricing CHECK ((pricing_version IS NULL) = (cost_complete IS NULL)),
    CONSTRAINT ck_quiz_judge_job_cost CHECK (
        (estimated_cost_usd IS NULL OR (pricing_version IS NOT NULL AND estimated_cost_usd >= 0))
            AND (estimated_cost_usd IS NOT NULL OR cost_complete IS NULL OR cost_complete = FALSE))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE quiz_judge_job_call (
    call_id             BIGINT       NOT NULL AUTO_INCREMENT,
    job_id              BIGINT       NOT NULL,
    call_no             INT          NOT NULL,
    http_status         INT          NULL,
    response_id         VARCHAR(100) NULL,
    response_model      VARCHAR(100) NULL,
    input_tokens        BIGINT       NULL,
    cached_input_tokens BIGINT       NULL,
    output_tokens       BIGINT       NULL,
    reasoning_tokens    BIGINT       NULL,
    total_tokens        BIGINT       NULL,
    PRIMARY KEY (call_id),
    CONSTRAINT uk_quiz_judge_job_call UNIQUE (job_id, call_no),
    CONSTRAINT fk_quiz_judge_job_call_job FOREIGN KEY (job_id) REFERENCES quiz_judge_job (job_id),
    CONSTRAINT ck_quiz_judge_job_call_no CHECK (call_no BETWEEN 1 AND 2),
    CONSTRAINT ck_quiz_judge_job_call_tokens CHECK (
        (input_tokens IS NULL OR input_tokens >= 0) AND (cached_input_tokens IS NULL OR cached_input_tokens >= 0)
            AND (output_tokens IS NULL OR output_tokens >= 0) AND (reasoning_tokens IS NULL OR reasoning_tokens >= 0)
            AND (total_tokens IS NULL OR total_tokens >= 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE quiz_judge_result (
    result_id            BIGINT      NOT NULL AUTO_INCREMENT,
    job_id               BIGINT      NOT NULL,
    series_id            BIGINT      NOT NULL,
    revision_id          BIGINT      NOT NULL,
    input_sha256         CHAR(64)    NOT NULL,
    settings_fingerprint CHAR(64)    NOT NULL,
    policy_version       VARCHAR(40) NOT NULL,
    aggregation          VARCHAR(20) NOT NULL,
    question_count       INT         NOT NULL,
    result_sha256        CHAR(64)    NOT NULL,
    completed_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (result_id),
    CONSTRAINT uk_quiz_judge_result_job UNIQUE (job_id),
    CONSTRAINT uk_quiz_judge_result_series UNIQUE (series_id),
    CONSTRAINT uk_quiz_judge_result_revision UNIQUE (revision_id, result_id),
    CONSTRAINT fk_quiz_judge_result_job FOREIGN KEY (job_id, series_id) REFERENCES quiz_judge_job (job_id, series_id),
    CONSTRAINT fk_quiz_judge_result_series FOREIGN KEY (series_id, revision_id)
        REFERENCES quiz_judge_series (series_id, revision_id),
    CONSTRAINT ck_quiz_judge_result_aggregation CHECK (aggregation IN ('ALL_PASS', 'HAS_FAIL', 'UNCERTAIN_ONLY')),
    CONSTRAINT ck_quiz_judge_result_questions CHECK (question_count >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE quiz_judge_result_item (
    item_id     BIGINT        NOT NULL AUTO_INCREMENT,
    result_id   BIGINT        NOT NULL,
    question_no INT           NOT NULL,
    rubric      VARCHAR(40)   NOT NULL,
    verdict     VARCHAR(20)   NOT NULL,
    reason      VARCHAR(1000) NOT NULL,
    PRIMARY KEY (item_id),
    CONSTRAINT uk_quiz_judge_result_item UNIQUE (result_id, question_no, rubric),
    CONSTRAINT fk_quiz_judge_result_item_result FOREIGN KEY (result_id) REFERENCES quiz_judge_result (result_id),
    CONSTRAINT ck_quiz_judge_result_item_question CHECK (question_no >= 0),
    CONSTRAINT ck_quiz_judge_result_item_rubric CHECK (rubric IN ('TEXT_ANSWER_CORRECT', 'UNIQUE_ANSWER',
        'EVIDENCE_SUPPORTS_ANSWER', 'QUESTION_CLARITY', 'KOREAN_QUALITY', 'DISTRACTOR_QUALITY', 'TEXT_FAITHFULNESS')),
    CONSTRAINT ck_quiz_judge_result_item_verdict CHECK (verdict IN ('PASS', 'FAIL', 'UNCERTAIN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 승인 근거는 해당 revision의 불변 결과 ID다. 거절 결정의 NULL 근거는 허용한다.
ALTER TABLE quiz_revision_decision
    ADD CONSTRAINT fk_quiz_revision_decision_judge_result
        FOREIGN KEY (revision_id, judge_evaluation_id)
        REFERENCES quiz_judge_result (revision_id, result_id);

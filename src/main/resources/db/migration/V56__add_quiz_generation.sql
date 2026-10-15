-- YouTube URL 기반 AI Quiz 비동기 생성 작업 (이슈 #525)
--
-- quiz_generation: Creator의 Quiz 생성 요청 하나. 영상 Grounding → Quiz 생성 → Quiz 저장을 비동기로 처리한다.
--   status
--     PENDING   : 접수했고 Worker가 아직 점유하지 않았다.
--     RUNNING   : Worker가 점유해 Provider를 호출하고 있다.
--     SUCCEEDED : Quiz와 첫 revision을 저장했다. quiz_id·revision_id가 있다.
--     FAILED    : 실패했다. failure_code가 있다.
--     UNKNOWN   : 시간 초과 등으로 Provider 결과를 확인할 수 없다. failure_code가 있다. 이후 결과를 저장하지 않는다.
--   허용 전이: PENDING → RUNNING·FAILED, RUNNING → SUCCEEDED·FAILED·UNKNOWN. 모두 조건부 UPDATE나 행 잠금으로 한다.
--   request_fingerprint는 videoId·제목·문항 수·보기 수의 SHA-256이다. 같은 requestId에 다른 입력이면 거부한다.
--
-- uk_quiz_generation_request는 Creator별 requestId 멱등성을 보장한다.
-- active_creator_id는 PENDING·RUNNING일 때만 creator_id이고 그 외에는 NULL인 생성 컬럼이다. UNIQUE는 NULL을 여러 개
--   허용하므로 uk_quiz_generation_active_creator가 Creator당 진행 중 작업을 하나로 제한한다. 동시 요청도 DB가 막는다.
-- fk_quiz_generation_revision은 결과 revision이 결과 Quiz의 revision인지 (quiz_id, revision_id)로 확인한다.
-- idx_quiz_generation_creator_requested는 Creator별 작업 조회를 지원한다.
CREATE TABLE quiz_generation (
    generation_id          BIGINT       NOT NULL AUTO_INCREMENT,
    creator_id             BIGINT       NOT NULL,
    requested_by_member_id BIGINT       NOT NULL,
    request_id             VARCHAR(36)  NOT NULL,
    request_fingerprint    CHAR(64)     NOT NULL,
    video_id               VARCHAR(20)  NOT NULL,
    title                  VARCHAR(100) NOT NULL,
    question_count         INT          NOT NULL,
    option_count           INT          NOT NULL,
    status                 VARCHAR(20)  NOT NULL,
    failure_code           VARCHAR(40)  NULL,
    quiz_id                BIGINT       NULL,
    revision_id            BIGINT       NULL,
    requested_at           DATETIME(6)  NOT NULL,
    started_at             DATETIME(6)  NULL,
    completed_at           DATETIME(6)  NULL,
    updated_at             DATETIME(6)  NOT NULL,
    active_creator_id      BIGINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING', 'RUNNING') THEN creator_id ELSE NULL END) VIRTUAL,
    PRIMARY KEY (generation_id),
    CONSTRAINT uk_quiz_generation_request UNIQUE (creator_id, request_id),
    CONSTRAINT uk_quiz_generation_active_creator UNIQUE (active_creator_id),
    INDEX idx_quiz_generation_creator_requested (creator_id, requested_at, generation_id),
    CONSTRAINT fk_quiz_generation_creator FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    CONSTRAINT fk_quiz_generation_member FOREIGN KEY (requested_by_member_id) REFERENCES member (member_id),
    CONSTRAINT fk_quiz_generation_quiz FOREIGN KEY (quiz_id) REFERENCES quiz (quiz_id),
    CONSTRAINT fk_quiz_generation_revision FOREIGN KEY (quiz_id, revision_id)
        REFERENCES quiz_revision (quiz_id, revision_id),
    CONSTRAINT ck_quiz_generation_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_quiz_generation_counts CHECK (question_count IN (3, 5) AND option_count = 4),
    CONSTRAINT ck_quiz_generation_result CHECK (
        (status = 'SUCCEEDED') = (quiz_id IS NOT NULL AND revision_id IS NOT NULL)
            AND (quiz_id IS NULL) = (revision_id IS NULL)),
    CONSTRAINT ck_quiz_generation_failure CHECK (
        (status IN ('FAILED', 'UNKNOWN')) = (failure_code IS NOT NULL)),
    CONSTRAINT ck_quiz_generation_started CHECK (status = 'PENDING' OR status = 'FAILED' OR started_at IS NOT NULL),
    CONSTRAINT ck_quiz_generation_completed CHECK (
        (status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')) = (completed_at IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

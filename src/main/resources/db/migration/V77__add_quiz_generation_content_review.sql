-- Quiz 생성 전 Grounding 원문 검토의 데이터 기반 (이슈 #602). V56의 컬럼·CHECK·active_creator_id는 바꾸지 않는다.
--
-- review_stage: 원문 검토 흐름의 세부 단계. status 5종은 그대로 쓴다.
--   NULL            : 기존 흐름(LEGACY). Grounding과 Quiz 생성을 한 작업에서 이어서 한다. 신규 컬럼은 모두 NULL이다.
--   GROUNDING       : 영상 Grounding 대기·진행 중이거나 그 단계에서 끝났다. AI 원문이 아직 없다.
--   AWAITING_REVIEW : AI 원문과 facts를 저장했고 Creator 확정을 기다린다. status는 RUNNING이라 active_creator_id가
--                     Creator당 진행·검토 대기 작업을 계속 하나로 제한한다. 취소하면 FAILED(CANCELLED)다.
--   GENERATING      : Creator가 확정한 원문으로 Quiz를 생성한다. generation_started_at은 Worker가 생성을 시작한 시각이다.
--
-- ai_content_text·grounding_facts·grounding_model·grounding_prompt_version은 Grounding 결과이며 함께 기록한다.
--   grounding_facts는 [{fact, evidenceType, evidence, timestampStart, timestampEnd}] 배열이고 최대 200개다.
--   직렬화 크기(512KB)는 서버가 검증한다.
-- reviewed_content_text는 Creator가 확정한 원문을 정규화 없이 그대로 저장한다. reviewed_content_sha256은
--   quiz.content_text_sha256과 같은 방식(QuizRevisionFingerprint.contentTextSha256)으로 계산한다. 확정 원문·해시·
--   확정자·확정 시각은 함께 기록한다. 원문 수정 여부는 저장하지 않고 AI 원문과 확정 원문을 비교해 계산한다.
--
-- ck_quiz_generation_review_grounding·confirmation·generation_started는 단계와 Grounding 결과·확정 원문·생성 시작
--   시각의 존재를 맞춘다. 단계가 NULL이면 세 CHECK에 의해 신규 컬럼이 모두 NULL이어야 한다.
-- ck_quiz_generation_review_status는 단계별로 가능한 status를 제한한다. 이전 버전 서버의 오래된 작업 정리
--   (PENDING → FAILED, RUNNING → UNKNOWN)는 review_stage를 보지 않으므로 어느 단계에서도 그 전이를 막지 않는다.
--   이를 막으면 이전 버전으로 되돌렸을 때 정리 UPDATE 전체가 실패해 해당 Creator의 생성 요청이 막힌다.
-- MySQL CHECK는 FALSE만 거부하고 UNKNOWN(NULL 비교)은 통과시키므로 nullable 값은 IS NULL·IS NOT NULL로 먼저 가른다.
-- 컬럼·FK·CHECK를 한 ALTER 문장으로 추가해 중간 실패 시 일부만 남지 않게 한다.

ALTER TABLE quiz_generation
    ADD COLUMN review_stage                   VARCHAR(20)  NULL AFTER failure_code,
    ADD COLUMN ai_content_text                MEDIUMTEXT   NULL AFTER review_stage,
    ADD COLUMN grounding_facts                JSON         NULL AFTER ai_content_text,
    ADD COLUMN grounding_model                VARCHAR(100) NULL AFTER grounding_facts,
    ADD COLUMN grounding_prompt_version       VARCHAR(50)  NULL AFTER grounding_model,
    ADD COLUMN reviewed_content_text          MEDIUMTEXT   NULL AFTER grounding_prompt_version,
    ADD COLUMN reviewed_content_sha256        CHAR(64)     NULL AFTER reviewed_content_text,
    ADD COLUMN content_confirmed_by_member_id BIGINT       NULL AFTER reviewed_content_sha256,
    ADD COLUMN content_confirmed_at           DATETIME(6)  NULL AFTER content_confirmed_by_member_id,
    ADD COLUMN generation_started_at          DATETIME(6)  NULL AFTER content_confirmed_at,
    ADD CONSTRAINT fk_quiz_generation_content_confirmer FOREIGN KEY (content_confirmed_by_member_id)
        REFERENCES member (member_id),
    ADD CONSTRAINT ck_quiz_generation_review_status CHECK (
        review_stage IS NULL
        OR (review_stage = 'GROUNDING' AND status <> 'SUCCEEDED')
        OR (review_stage = 'AWAITING_REVIEW' AND status IN ('RUNNING', 'FAILED', 'UNKNOWN'))
        OR (review_stage = 'GENERATING' AND status <> 'PENDING'
            AND (status <> 'SUCCEEDED' OR generation_started_at IS NOT NULL))
    ),
    ADD CONSTRAINT ck_quiz_generation_review_grounding CHECK (
        (review_stage IS NOT NULL AND review_stage <> 'GROUNDING') = (ai_content_text IS NOT NULL)
        AND (ai_content_text IS NULL) = (grounding_facts IS NULL)
        AND (ai_content_text IS NULL) = (grounding_model IS NULL)
        AND (ai_content_text IS NULL) = (grounding_prompt_version IS NULL)
        AND (grounding_facts IS NULL
            OR (JSON_TYPE(grounding_facts) = 'ARRAY' AND JSON_LENGTH(grounding_facts) <= 200))
    ),
    ADD CONSTRAINT ck_quiz_generation_review_confirmation CHECK (
        (review_stage IS NOT NULL AND review_stage = 'GENERATING') = (reviewed_content_text IS NOT NULL)
        AND (reviewed_content_text IS NULL) = (reviewed_content_sha256 IS NULL)
        AND (reviewed_content_text IS NULL) = (content_confirmed_by_member_id IS NULL)
        AND (reviewed_content_text IS NULL) = (content_confirmed_at IS NULL)
    ),
    ADD CONSTRAINT ck_quiz_generation_review_generation_started CHECK (
        generation_started_at IS NULL OR (review_stage IS NOT NULL AND review_stage = 'GENERATING')
    );

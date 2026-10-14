-- Creator Quiz 편집 Revision·책임 승인·게시 고정의 데이터 기반 (이슈 #581). V55/V58/V66은 바꾸지 않는다.
--
-- quiz_revision
--   origin AI_GENERATED      : 기존과 같은 AI 생성 revision. quiz-revision-v1 해시, 부모·편집자 없음.
--   origin CREATOR_COMPOSED  : Creator가 부모 revision에서 문항을 선택·수정·추가해 만든 새 revision. quiz-revision-v2
--                              해시, 부모 revision ID와 그 해시, 편집 Member·시각이 있고 문항은 1~5개·보기 4개다.
--   fk_quiz_revision_parent는 부모가 같은 Quiz의 revision인지, fk_quiz_revision_parent_hash는 기록한 부모 해시가
--   부모 행의 실제 해시인지 확인한다. uk_quiz_revision_hash는 해시를 함께 고정하는 FK들의 참조 대상이다.
--   기존 행은 AI_GENERATED·quiz-revision-v1로 채우고 revision_sha256은 다시 계산하지 않는다.
--
-- quiz_question
--   change_type NULL           : AI 생성 revision의 원본 문항. 원본·직전 참조가 없다.
--   AI_UNCHANGED·CREATOR_EDITED : 원본 AI 문항(origin_question_id)과 부모 revision의 직전 문항(previous_question_id)을
--                                 모두 가진다. 원본과 내용이 같으면 AI_UNCHANGED, 다르면 CREATOR_EDITED다.
--   CREATOR_ADDED              : Creator가 추가한 문항. 원본 AI 문항이 없다.
--   같은 Quiz 소속과 내용 일치는 서버(QuizRevisionReader)가 검증한다. 빈 source_evidence는 근거 없음이다.
--
-- quiz_revision_decision
--   승인 정책 LEGACY_JUDGE_V1  : 기존 승인. Judge 결과 ID·해시(V58 result FK 유지)와 승인한 revision 해시를 고정한다.
--   승인 정책 CREATOR_REVIEW_V1: Creator 책임 승인. Judge 근거 없이 책임 확인과 승인한 revision 해시를 고정한다.
--   REJECTED는 정책·Judge 근거를 요구하지 않는다. 기존 승인 행은 실제 revision 해시와 LEGACY_JUDGE_V1로 채운다.
--   ck_quiz_revision_decision_judge는 같은 이름으로 정책별 조건으로 넓힌다.
--   fk_quiz_revision_decision_revision_hash는 승인한 해시가 그 revision의 실제 해시인지 확인한다.
--
-- quiz 게시 고정
--   첫 게시 때 content_locked와 고정 revision ID·해시를 함께 기록하고 게시 취소로 지우지 않는다. 이후 같은 고정
--   revision만 다시 게시한다. 기존 행은 미게시(NOT_PUBLISHED)가 아니거나 published_at이 있으면 잠그고 상태는 바꾸지
--   않는다. V55가 허용한 NOT_PUBLISHED·published_at 있음 행은 게시 revision을 확인할 수 없어 고정하지 않는다.
--   PUBLISHED는 현재 게시 revision으로 고정한다. UNPUBLISHED는 게시 사실이 저장 기록으로 직접 확인될 때만 고정한다. 그 revision으로 제출된 Fan 응시가
--   있고(응시는 그 revision이 게시 중일 때만 저장된다) 그 Quiz의 revision이 그것 하나뿐이어서 다른 revision이 먼저
--   게시됐을 수 없을 때다. revision 수나 승인 여부만으로는 고정하지 않는다. 확인할 수 없으면 잠금만 두고 고정 revision을
--   비운다(재게시 차단, 별도 확인 대상).
--   확인 대상 조회: SELECT quiz_id FROM quiz WHERE content_locked = TRUE AND locked_revision_id IS NULL;
--
-- MySQL CHECK는 FALSE만 거부하고 UNKNOWN(NULL 비교)은 통과시키므로, 조건에 쓰는 nullable 값은 IS NOT NULL을 함께 건다.
--
-- MySQL DDL은 문장마다 암묵적으로 커밋되므로 이 파일은 원자적으로 적용되지 않고 실패해도 자동으로 rollback되지 않는다.
-- 중간에 실패하면 앞 문장의 변경이 남고 Flyway 이력에 실패로 기록된다. repair는 실패 이력만 지우고 수동으로 적용한 SQL을
-- 성공으로 등록하지 않는다. 복구 순서: (1) 실패 문장과 이미 적용된 DDL·UPDATE를 확인한다. (2) 부분 적용된 변경을 적용 전
-- 상태로 되돌린다. 되돌릴 수 있는지는 남은 컬럼·제약·데이터를 보고 먼저 확인한다. (3) flyway repair. (4) flyway migrate를
-- 다시 실행해 V73 전체를 적용한다.

ALTER TABLE quiz_revision
    ADD COLUMN origin                 VARCHAR(20) NOT NULL DEFAULT 'AI_GENERATED' AFTER revision_sha256,
    ADD COLUMN hash_version           VARCHAR(20) NOT NULL DEFAULT 'quiz-revision-v1' AFTER origin,
    ADD COLUMN parent_revision_id     BIGINT      NULL AFTER hash_version,
    ADD COLUMN parent_revision_sha256 CHAR(64)    NULL AFTER parent_revision_id,
    ADD COLUMN composed_by_member_id  BIGINT      NULL AFTER parent_revision_sha256,
    ADD COLUMN composed_at            DATETIME(6) NULL AFTER composed_by_member_id;

ALTER TABLE quiz_revision
    ALTER COLUMN origin DROP DEFAULT,
    ALTER COLUMN hash_version DROP DEFAULT,
    ADD CONSTRAINT uk_quiz_revision_hash UNIQUE (revision_id, revision_sha256),
    ADD CONSTRAINT fk_quiz_revision_parent FOREIGN KEY (quiz_id, parent_revision_id)
        REFERENCES quiz_revision (quiz_id, revision_id),
    ADD CONSTRAINT fk_quiz_revision_parent_hash FOREIGN KEY (parent_revision_id, parent_revision_sha256)
        REFERENCES quiz_revision (revision_id, revision_sha256),
    ADD CONSTRAINT fk_quiz_revision_composer FOREIGN KEY (composed_by_member_id) REFERENCES member (member_id),
    ADD CONSTRAINT ck_quiz_revision_origin CHECK (
        (origin = 'AI_GENERATED' AND hash_version = 'quiz-revision-v1'
            AND parent_revision_id IS NULL AND parent_revision_sha256 IS NULL
            AND composed_by_member_id IS NULL AND composed_at IS NULL)
        OR (origin = 'CREATOR_COMPOSED' AND hash_version = 'quiz-revision-v2'
            AND parent_revision_id IS NOT NULL AND parent_revision_sha256 IS NOT NULL
            AND composed_by_member_id IS NOT NULL AND composed_at IS NOT NULL
            AND question_count BETWEEN 1 AND 5 AND option_count = 4)
    );

ALTER TABLE quiz_question
    ADD COLUMN change_type          VARCHAR(20) NULL,
    ADD COLUMN origin_question_id   BIGINT      NULL,
    ADD COLUMN previous_question_id BIGINT      NULL,
    ADD CONSTRAINT fk_quiz_question_origin FOREIGN KEY (origin_question_id) REFERENCES quiz_question (question_id),
    ADD CONSTRAINT fk_quiz_question_previous FOREIGN KEY (previous_question_id) REFERENCES quiz_question (question_id),
    ADD CONSTRAINT ck_quiz_question_lineage CHECK (
        (change_type IS NULL AND origin_question_id IS NULL AND previous_question_id IS NULL)
        OR (change_type IS NOT NULL AND change_type IN ('AI_UNCHANGED', 'CREATOR_EDITED')
            AND origin_question_id IS NOT NULL AND previous_question_id IS NOT NULL)
        OR (change_type IS NOT NULL AND change_type = 'CREATOR_ADDED' AND origin_question_id IS NULL)
    );

ALTER TABLE quiz_revision_decision
    ADD COLUMN approval_policy         VARCHAR(30) NULL AFTER judge_evaluation_sha256,
    ADD COLUMN approved_revision_sha256 CHAR(64)   NULL AFTER approval_policy,
    ADD COLUMN responsibility_confirmed BOOLEAN    NULL AFTER approved_revision_sha256;

UPDATE quiz_revision_decision d
    JOIN quiz_revision r ON r.revision_id = d.revision_id
SET d.approval_policy = 'LEGACY_JUDGE_V1',
    d.approved_revision_sha256 = r.revision_sha256
WHERE d.decision = 'APPROVED';

ALTER TABLE quiz_revision_decision
    DROP CHECK ck_quiz_revision_decision_judge;

ALTER TABLE quiz_revision_decision
    ADD CONSTRAINT ck_quiz_revision_decision_judge CHECK (
        (decision = 'REJECTED' AND approval_policy IS NULL AND approved_revision_sha256 IS NULL
            AND responsibility_confirmed IS NULL)
        OR (decision = 'APPROVED' AND approval_policy IS NOT NULL AND approval_policy = 'LEGACY_JUDGE_V1'
            AND judge_evaluation_id IS NOT NULL AND judge_evaluation_sha256 IS NOT NULL
            AND approved_revision_sha256 IS NOT NULL AND responsibility_confirmed IS NULL)
        OR (decision = 'APPROVED' AND approval_policy IS NOT NULL AND approval_policy = 'CREATOR_REVIEW_V1'
            AND judge_evaluation_id IS NULL AND judge_evaluation_sha256 IS NULL
            AND approved_revision_sha256 IS NOT NULL
            AND responsibility_confirmed IS NOT NULL AND responsibility_confirmed = TRUE)
    ),
    ADD CONSTRAINT fk_quiz_revision_decision_revision_hash FOREIGN KEY (revision_id, approved_revision_sha256)
        REFERENCES quiz_revision (revision_id, revision_sha256);

ALTER TABLE quiz
    ADD COLUMN content_locked         BOOLEAN  NOT NULL DEFAULT FALSE AFTER unpublished_at,
    ADD COLUMN locked_revision_id     BIGINT   NULL AFTER content_locked,
    ADD COLUMN locked_revision_sha256 CHAR(64) NULL AFTER locked_revision_id;

UPDATE quiz SET content_locked = TRUE WHERE publication_status <> 'NOT_PUBLISHED' OR published_at IS NOT NULL;

UPDATE quiz q
    JOIN quiz_revision r ON r.quiz_id = q.quiz_id AND r.revision_id = q.published_revision_id
SET q.locked_revision_id = r.revision_id,
    q.locked_revision_sha256 = r.revision_sha256
WHERE q.publication_status = 'PUBLISHED';

UPDATE quiz q
    JOIN (SELECT quiz_id, MIN(revision_id) AS revision_id
          FROM quiz_attempt
          GROUP BY quiz_id
          HAVING COUNT(DISTINCT revision_id) = 1) attempted ON attempted.quiz_id = q.quiz_id
    JOIN (SELECT quiz_id
          FROM quiz_revision
          GROUP BY quiz_id
          HAVING COUNT(*) = 1) only_revision ON only_revision.quiz_id = q.quiz_id
    JOIN quiz_revision r ON r.quiz_id = q.quiz_id AND r.revision_id = attempted.revision_id
SET q.locked_revision_id = r.revision_id,
    q.locked_revision_sha256 = r.revision_sha256
WHERE q.publication_status = 'UNPUBLISHED';

ALTER TABLE quiz
    ADD CONSTRAINT fk_quiz_locked_revision FOREIGN KEY (quiz_id, locked_revision_id)
        REFERENCES quiz_revision (quiz_id, revision_id),
    ADD CONSTRAINT fk_quiz_locked_revision_hash FOREIGN KEY (locked_revision_id, locked_revision_sha256)
        REFERENCES quiz_revision (revision_id, revision_sha256),
    ADD CONSTRAINT ck_quiz_lock_pair CHECK ((locked_revision_id IS NULL) = (locked_revision_sha256 IS NULL)),
    ADD CONSTRAINT ck_quiz_lock_published CHECK (
        published_revision_id IS NULL
        OR (content_locked = TRUE AND locked_revision_id IS NOT NULL AND locked_revision_id = published_revision_id)
    ),
    ADD CONSTRAINT ck_quiz_lock_unpublished CHECK (publication_status <> 'UNPUBLISHED' OR content_locked = TRUE),
    ADD CONSTRAINT ck_quiz_lock_not_published CHECK (
        publication_status <> 'NOT_PUBLISHED'
        OR (locked_revision_id IS NULL AND (content_locked = FALSE OR published_at IS NOT NULL))
    );

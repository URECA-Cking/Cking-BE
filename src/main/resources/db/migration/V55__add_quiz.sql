-- AI Quiz 저장·검토·게시 (이슈 #521)
--
-- quiz: Creator가 소유한 Quiz. revision이 바뀌어도 quiz_id는 그대로다. 원본 YouTube videoId와 Quiz 생성 입력인
--   content_text, 그 SHA-256은 생성 후 바꾸지 않는다.
--   publication_status
--     NOT_PUBLISHED : 한 번도 게시하지 않았다.
--     PUBLISHED     : published_revision_id가 가리키는 revision을 공개 중이다.
--     UNPUBLISHED   : 게시를 취소했다. revision과 결정 기록은 남는다.
--   ck_quiz_publication은 "PUBLISHED ⇔ published_revision_id 있음"을 강제한다.
--   fk_quiz_published_revision은 게시 revision이 같은 Quiz의 revision인지 (quiz_id, revision_id)로 확인한다.
--   idx_quiz_creator_created는 Creator 본인 목록을 지원한다.
--     WHERE creator_id = ? ORDER BY created_at DESC, quiz_id DESC
--   idx_quiz_creator_published는 공개 목록을 지원한다.
--     WHERE creator_id = ? AND publication_status = 'PUBLISHED' ORDER BY published_at DESC, quiz_id DESC
--
-- quiz_revision · quiz_question · quiz_option: 한 번 INSERT하면 바꾸지 않는다. 수정은 새 revision으로 만든다.
--   revision_no는 Quiz 안에서 1부터 증가한다. 게시할 수 있는 revision은 revision_no가 가장 큰 revision뿐이다.
--   revision_sha256은 저장 내용과 quiz.content_text_sha256으로 계산한다(docs/domains/quiz/README.md#revision-해시).
--   question_no·option_no는 0부터 시작하는 표시 순서이고, correct_option_index는 0-based option_no다.
--   길이 상한: 제목 100, 질문 500, 보기 200, 해설 1000, 근거 1000자.
--
-- quiz_revision_decision: revision 하나에 Creator 결정 하나. 결정은 바꾸지 않는다.
--   decision : APPROVED, REJECTED
--   ck_quiz_revision_decision_judge는 승인에 Judge 평가 근거(judge_evaluation_id·judge_evaluation_sha256)를 요구한다.
--   Judge 평가 테이블이 생기면 judge_evaluation_id에 FK를 추가한다.
--
-- 이후 풀이 기록은 quiz_id와 revision_id를 참조하고 (member_id, quiz_id)로 1회 제출을 보장한다. revision과 결정은
-- 삭제하지 않으므로 수정·게시 취소가 풀이·응모권 기록에 영향을 주지 않는다.
CREATE TABLE quiz (
    quiz_id               BIGINT       NOT NULL AUTO_INCREMENT,
    creator_id            BIGINT       NOT NULL,
    source_video_id       VARCHAR(20)  NOT NULL,
    content_text          MEDIUMTEXT   NOT NULL,
    content_text_sha256   CHAR(64)     NOT NULL,
    publication_status    VARCHAR(20)  NOT NULL,
    published_revision_id BIGINT       NULL,
    published_at          DATETIME(6)  NULL,
    unpublished_at        DATETIME(6)  NULL,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (quiz_id),
    INDEX idx_quiz_creator_created (creator_id, created_at, quiz_id),
    INDEX idx_quiz_creator_published (creator_id, publication_status, published_at, quiz_id),
    CONSTRAINT fk_quiz_creator FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    CONSTRAINT ck_quiz_publication_status
        CHECK (publication_status IN ('NOT_PUBLISHED', 'PUBLISHED', 'UNPUBLISHED')),
    CONSTRAINT ck_quiz_publication
        CHECK ((publication_status = 'PUBLISHED') = (published_revision_id IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE quiz_revision (
    revision_id     BIGINT       NOT NULL AUTO_INCREMENT,
    quiz_id         BIGINT       NOT NULL,
    revision_no     INT          NOT NULL,
    title           VARCHAR(100) NOT NULL,
    prompt_version  VARCHAR(50)  NOT NULL,
    question_count  INT          NOT NULL,
    option_count    INT          NOT NULL,
    revision_sha256 CHAR(64)     NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (revision_id),
    CONSTRAINT uk_quiz_revision_no UNIQUE (quiz_id, revision_no),
    CONSTRAINT uk_quiz_revision_quiz_revision UNIQUE (quiz_id, revision_id),
    CONSTRAINT fk_quiz_revision_quiz FOREIGN KEY (quiz_id) REFERENCES quiz (quiz_id),
    CONSTRAINT ck_quiz_revision_no CHECK (revision_no >= 1),
    CONSTRAINT ck_quiz_revision_counts CHECK (question_count >= 1 AND option_count >= 2)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE quiz
    ADD CONSTRAINT fk_quiz_published_revision FOREIGN KEY (quiz_id, published_revision_id)
        REFERENCES quiz_revision (quiz_id, revision_id);

CREATE TABLE quiz_question (
    question_id          BIGINT        NOT NULL AUTO_INCREMENT,
    revision_id          BIGINT        NOT NULL,
    question_no          INT           NOT NULL,
    question_text        VARCHAR(500)  NOT NULL,
    correct_option_index INT           NOT NULL,
    explanation          VARCHAR(1000) NOT NULL,
    source_evidence      VARCHAR(1000) NOT NULL,
    PRIMARY KEY (question_id),
    CONSTRAINT uk_quiz_question_no UNIQUE (revision_id, question_no),
    CONSTRAINT fk_quiz_question_revision FOREIGN KEY (revision_id) REFERENCES quiz_revision (revision_id),
    CONSTRAINT ck_quiz_question_no CHECK (question_no >= 0),
    CONSTRAINT ck_quiz_question_correct_option CHECK (correct_option_index >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE quiz_option (
    option_id   BIGINT       NOT NULL AUTO_INCREMENT,
    question_id BIGINT       NOT NULL,
    option_no   INT          NOT NULL,
    option_text VARCHAR(200) NOT NULL,
    PRIMARY KEY (option_id),
    CONSTRAINT uk_quiz_option_no UNIQUE (question_id, option_no),
    CONSTRAINT fk_quiz_option_question FOREIGN KEY (question_id) REFERENCES quiz_question (question_id),
    CONSTRAINT ck_quiz_option_no CHECK (option_no >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE quiz_revision_decision (
    decision_id             BIGINT       NOT NULL AUTO_INCREMENT,
    revision_id             BIGINT       NOT NULL,
    decision                VARCHAR(20)  NOT NULL,
    decided_by_member_id    BIGINT       NOT NULL,
    reason                  VARCHAR(500) NULL,
    judge_evaluation_id     BIGINT       NULL,
    judge_evaluation_sha256 CHAR(64)     NULL,
    decided_at              DATETIME(6)  NOT NULL,
    PRIMARY KEY (decision_id),
    CONSTRAINT uk_quiz_revision_decision_revision UNIQUE (revision_id),
    CONSTRAINT fk_quiz_revision_decision_revision FOREIGN KEY (revision_id) REFERENCES quiz_revision (revision_id),
    CONSTRAINT fk_quiz_revision_decision_member FOREIGN KEY (decided_by_member_id) REFERENCES member (member_id),
    CONSTRAINT ck_quiz_revision_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_quiz_revision_decision_judge CHECK (
        decision = 'REJECTED'
            OR (judge_evaluation_id IS NOT NULL AND judge_evaluation_sha256 IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

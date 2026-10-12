-- Fan Quiz 응시·서버 채점 스냅샷 (#535). V59~V63 선행 적용 후 배포한다.
-- Ticket 지급/Outbox는 포함하지 않는다. 양수 점수의 reward_request_id는 후속 연동에서도 유지한다.
ALTER TABLE quiz ADD CONSTRAINT uk_quiz_creator_lineage UNIQUE (quiz_id, creator_id);

CREATE TABLE quiz_attempt (
    attempt_id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    creator_id BIGINT NOT NULL,
    quiz_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    revision_sha256 VARCHAR(64) NOT NULL,
    snapshot_sha256 VARCHAR(64) NOT NULL,
    title VARCHAR(100) NOT NULL,
    video_id VARCHAR(20) NOT NULL,
    question_count INT NOT NULL,
    correct_count INT NOT NULL,
    reward_amount BIGINT NOT NULL,
    reward_request_id VARCHAR(36) NULL,
    reward_status VARCHAR(20) NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attempt_id),
    CONSTRAINT uk_quiz_attempt_member_quiz UNIQUE (member_id, quiz_id),
    CONSTRAINT uk_quiz_attempt_member_request UNIQUE (member_id, request_id),
    CONSTRAINT uk_quiz_attempt_reward_request UNIQUE (reward_request_id),
    CONSTRAINT fk_quiz_attempt_member FOREIGN KEY (member_id) REFERENCES member(member_id),
    CONSTRAINT fk_quiz_attempt_creator FOREIGN KEY (creator_id) REFERENCES creator(creator_id),
    CONSTRAINT fk_quiz_attempt_quiz_creator FOREIGN KEY (quiz_id, creator_id) REFERENCES quiz(quiz_id, creator_id),
    CONSTRAINT fk_quiz_attempt_revision FOREIGN KEY (quiz_id, revision_id) REFERENCES quiz_revision(quiz_id, revision_id),
    CONSTRAINT ck_quiz_attempt_score CHECK (question_count > 0 AND correct_count >= 0 AND correct_count <= question_count),
    CONSTRAINT ck_quiz_attempt_reward CHECK (reward_amount = correct_count AND
        ((correct_count = 0 AND reward_request_id IS NULL AND reward_status = 'NOT_ELIGIBLE') OR
         (correct_count > 0 AND reward_request_id IS NOT NULL AND reward_status = 'NOT_INTEGRATED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE quiz_attempt_answer (
    answer_id BIGINT NOT NULL AUTO_INCREMENT,
    attempt_id BIGINT NOT NULL,
    question_no INT NOT NULL,
    question_text VARCHAR(500) NOT NULL,
    options_json TEXT NOT NULL,
    option_count INT NOT NULL,
    selected_option_index INT NOT NULL,
    correct_option_index INT NOT NULL,
    correct BOOLEAN NOT NULL,
    explanation VARCHAR(1000) NOT NULL,
    source_evidence VARCHAR(1000) NOT NULL,
    PRIMARY KEY (answer_id),
    CONSTRAINT uk_quiz_attempt_answer_no UNIQUE (attempt_id, question_no),
    CONSTRAINT fk_quiz_attempt_answer FOREIGN KEY (attempt_id) REFERENCES quiz_attempt(attempt_id),
    CONSTRAINT ck_quiz_attempt_answer_index CHECK (question_no >= 0 AND option_count >= 2
        AND selected_option_index >= 0 AND selected_option_index < option_count
        AND correct_option_index >= 0 AND correct_option_index < option_count),
    CONSTRAINT ck_quiz_attempt_answer_correct CHECK (correct = (selected_option_index = correct_option_index)),
    CONSTRAINT ck_quiz_attempt_answer_options CHECK (JSON_VALID(options_json) AND JSON_TYPE(options_json) = 'ARRAY'
        AND JSON_LENGTH(options_json) = option_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

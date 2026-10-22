-- #582: 편집 Revision과 같은 트랜잭션에서만 성공 접수를 저장한다. V73 선행.
CREATE TABLE quiz_composition_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    quiz_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    revision_id BIGINT NOT NULL,
    requested_by_member_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_quiz_composition_request UNIQUE (quiz_id, request_id),
    CONSTRAINT uk_quiz_composition_revision UNIQUE (revision_id),
    CONSTRAINT fk_quiz_composition_revision FOREIGN KEY (quiz_id, revision_id) REFERENCES quiz_revision (quiz_id, revision_id),
    CONSTRAINT fk_quiz_composition_member FOREIGN KEY (requested_by_member_id) REFERENCES member (member_id),
    CONSTRAINT ck_quiz_composition_payload CHECK (payload_sha256 REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

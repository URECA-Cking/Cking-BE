-- 단일 크리에이터 유사 추천 결과 저장·활성 세대 전환 (이슈 #393)
--
-- generation은 LLM이 만든 완결된 후보 묶음의 메타데이터를 보존한다.
-- candidate는 세대별 순위 결과이며 과거 세대도 삭제하지 않는다.
-- state의 current_generation_id만 같은 Transaction에서 교체하므로 공개 조회는
-- 이전 완성 세대 또는 새 완성 세대만 보고, 일부 후보만 저장된 중간 상태를 보지 않는다.
CREATE TABLE creator_similarity_generation (
    generation_id  BIGINT       NOT NULL AUTO_INCREMENT,
    creator_id     BIGINT       NOT NULL,
    method         VARCHAR(20)  NOT NULL,
    model_version  VARCHAR(255) NOT NULL,
    input_hash     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (generation_id),
    CONSTRAINT uk_creator_similarity_generation_input UNIQUE (creator_id, input_hash),
    CONSTRAINT uk_creator_similarity_generation_owner UNIQUE (creator_id, generation_id),
    INDEX idx_creator_similarity_generation_created (creator_id, created_at, generation_id),
    CONSTRAINT fk_creator_similarity_generation_creator
        FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    CONSTRAINT ck_creator_similarity_generation_hash
        CHECK (input_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE creator_similarity_candidate (
    candidate_id         BIGINT         NOT NULL AUTO_INCREMENT,
    generation_id       BIGINT         NOT NULL,
    similar_creator_id  BIGINT         NOT NULL,
    score               DECIMAL(12, 8) NOT NULL,
    rank_no             INT            NOT NULL,
    PRIMARY KEY (candidate_id),
    CONSTRAINT uk_creator_similarity_candidate_rank UNIQUE (generation_id, rank_no),
    CONSTRAINT uk_creator_similarity_candidate_creator UNIQUE (generation_id, similar_creator_id),
    INDEX idx_creator_similarity_candidate_creator (similar_creator_id),
    CONSTRAINT fk_creator_similarity_candidate_generation
        FOREIGN KEY (generation_id) REFERENCES creator_similarity_generation (generation_id),
    CONSTRAINT fk_creator_similarity_candidate_creator
        FOREIGN KEY (similar_creator_id) REFERENCES creator (creator_id),
    CONSTRAINT ck_creator_similarity_candidate_rank CHECK (rank_no > 0),
    CONSTRAINT ck_creator_similarity_candidate_score CHECK (score >= -1.0 AND score <= 2.0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE creator_similarity_state (
    creator_id             BIGINT NOT NULL,
    current_generation_id  BIGINT NOT NULL,
    PRIMARY KEY (creator_id),
    CONSTRAINT uk_creator_similarity_state_generation UNIQUE (current_generation_id),
    CONSTRAINT fk_creator_similarity_state_creator
        FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    CONSTRAINT fk_creator_similarity_state_generation_owner
        FOREIGN KEY (creator_id, current_generation_id)
        REFERENCES creator_similarity_generation (creator_id, generation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

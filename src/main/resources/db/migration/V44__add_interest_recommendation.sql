-- 관심 분야별 추천 후보 저장·활성 세대 전환 (이슈 #438)
--
-- 구조는 creator_similarity_*(V40)와 같고 기준축만 (taxonomy_version, interest_code)다.
-- generation은 LLM이 만든 분야별 완결 후보 묶음의 메타데이터를, candidate는 세대별 순위 결과를 보존하며 과거 세대도
-- 삭제하지 않는다. state의 current_generation_id만 같은 Transaction에서 교체하므로 조회는 이전 완성 세대 또는 새
-- 완성 세대만 보고 일부 후보만 저장된 중간 상태를 보지 않는다.
CREATE TABLE interest_recommendation_generation (
    generation_id     BIGINT       NOT NULL AUTO_INCREMENT,
    taxonomy_version  VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    interest_code     VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    method            VARCHAR(20)  NOT NULL,
    model_version     VARCHAR(255) NOT NULL,
    input_hash        CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (generation_id),
    CONSTRAINT uk_interest_recommendation_generation_input
        UNIQUE (taxonomy_version, interest_code, input_hash),
    CONSTRAINT uk_interest_recommendation_generation_owner
        UNIQUE (taxonomy_version, interest_code, generation_id),
    CONSTRAINT fk_interest_recommendation_generation_category
        FOREIGN KEY (taxonomy_version, interest_code)
        REFERENCES interest_category (taxonomy_version, interest_code),
    CONSTRAINT ck_interest_recommendation_generation_hash
        CHECK (input_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE interest_recommendation_candidate (
    candidate_id   BIGINT         NOT NULL AUTO_INCREMENT,
    generation_id  BIGINT         NOT NULL,
    creator_id     BIGINT         NOT NULL,
    score          DECIMAL(12, 8) NOT NULL,
    rank_no        INT            NOT NULL,
    PRIMARY KEY (candidate_id),
    CONSTRAINT uk_interest_recommendation_candidate_rank UNIQUE (generation_id, rank_no),
    CONSTRAINT uk_interest_recommendation_candidate_creator UNIQUE (generation_id, creator_id),
    INDEX idx_interest_recommendation_candidate_creator (creator_id),
    CONSTRAINT fk_interest_recommendation_candidate_generation
        FOREIGN KEY (generation_id) REFERENCES interest_recommendation_generation (generation_id),
    CONSTRAINT fk_interest_recommendation_candidate_creator
        FOREIGN KEY (creator_id) REFERENCES creator (creator_id),
    CONSTRAINT ck_interest_recommendation_candidate_rank CHECK (rank_no > 0),
    CONSTRAINT ck_interest_recommendation_candidate_score CHECK (score >= -1.0 AND score <= 2.0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE interest_recommendation_state (
    taxonomy_version       VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    interest_code          VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_generation_id  BIGINT NOT NULL,
    PRIMARY KEY (taxonomy_version, interest_code),
    CONSTRAINT uk_interest_recommendation_state_generation UNIQUE (current_generation_id),
    CONSTRAINT fk_interest_recommendation_state_category
        FOREIGN KEY (taxonomy_version, interest_code)
        REFERENCES interest_category (taxonomy_version, interest_code),
    -- 포인터는 자기 분야의 세대만 가리킬 수 있다.
    CONSTRAINT fk_interest_recommendation_state_generation_owner
        FOREIGN KEY (taxonomy_version, interest_code, current_generation_id)
        REFERENCES interest_recommendation_generation (taxonomy_version, interest_code, generation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

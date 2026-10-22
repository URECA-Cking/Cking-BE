-- 기존 추천의 생성 근거만 독립 보존한다.
CREATE TABLE recommendation_eligibility (
    source_kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation_id BIGINT NOT NULL,
    proof JSON NOT NULL,
    PRIMARY KEY(source_kind, generation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

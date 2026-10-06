-- 관심 분야 분류체계 저장 (이슈 #429)
--
-- 정본은 Cking-LLM의 분류 CSV이며, 등록한 버전의 행과 해시는 수정하지 않는다(내용이 바뀌면 새 버전을 등록).
-- taxonomy_hash는 CSV를 정규화한 canonical JSON의 SHA-256이다(InterestTaxonomyHash).
-- 17개 시드는 LLM의 categories_v2.csv가 병합된 뒤 별도 마이그레이션으로 넣는다.
CREATE TABLE interest_taxonomy (
    taxonomy_version  VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    taxonomy_hash     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active            BOOLEAN      NOT NULL DEFAULT FALSE,
    -- 활성 분류체계는 최대 1개다. MySQL은 부분 UNIQUE가 없어, 활성일 때만 값이 생기는 칼럼에 UNIQUE를 건다.
    active_marker     TINYINT GENERATED ALWAYS AS (IF(active, 1, NULL)) STORED,
    created_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (taxonomy_version),
    CONSTRAINT uk_interest_taxonomy_hash UNIQUE (taxonomy_hash),
    CONSTRAINT uk_interest_taxonomy_active UNIQUE (active_marker),
    CONSTRAINT ck_interest_taxonomy_hash CHECK (taxonomy_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE interest_category (
    taxonomy_version  VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    interest_code     VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name              VARCHAR(50)  NOT NULL,
    description       VARCHAR(500) NOT NULL,
    display_order     INT          NOT NULL,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (taxonomy_version, interest_code),
    CONSTRAINT uk_interest_category_order UNIQUE (taxonomy_version, display_order),
    CONSTRAINT fk_interest_category_taxonomy
        FOREIGN KEY (taxonomy_version) REFERENCES interest_taxonomy (taxonomy_version),
    CONSTRAINT ck_interest_category_order CHECK (display_order > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

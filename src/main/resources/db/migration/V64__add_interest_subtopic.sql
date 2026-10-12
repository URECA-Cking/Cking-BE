-- 세부 관심 주제 저장 (이슈 #537)
--
-- 상위 분류체계(interest_taxonomy, v0.2)는 건드리지 않고 세부 주제를 별도 버전 세트로 등록해 상위 분야에 연결한다.
-- 등록한 세트의 행과 해시는 수정하지 않는다(내용이 바뀌면 새 버전을 등록). 상위당 3개 상한은 DB로 강제할 수 없어
-- 서비스가 회원 행을 잠근 채 검증한다.
CREATE TABLE interest_subtopic_set (
    subtopic_version         VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parent_taxonomy_version  VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subtopic_hash            CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active                   BOOLEAN      NOT NULL DEFAULT FALSE,
    -- 활성 세트는 최대 1개다. 부분 UNIQUE가 없는 MySQL이라 활성일 때만 값이 생기는 칼럼에 UNIQUE를 건다.
    active_marker            TINYINT GENERATED ALWAYS AS (IF(active, 1, NULL)) STORED,
    created_at               DATETIME(6)  NOT NULL,
    PRIMARY KEY (subtopic_version),
    -- interest_subtopic이 세트와 같은 상위 분류체계 버전을 갖도록 묶는 복합 FK의 대상이다.
    CONSTRAINT uk_interest_subtopic_set_parent UNIQUE (subtopic_version, parent_taxonomy_version),
    CONSTRAINT uk_interest_subtopic_set_hash UNIQUE (subtopic_hash),
    CONSTRAINT uk_interest_subtopic_set_active UNIQUE (active_marker),
    CONSTRAINT fk_interest_subtopic_set_taxonomy
        FOREIGN KEY (parent_taxonomy_version) REFERENCES interest_taxonomy (taxonomy_version),
    CONSTRAINT ck_interest_subtopic_set_hash CHECK (subtopic_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE interest_subtopic (
    subtopic_version         VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subtopic_code            VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parent_taxonomy_version  VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parent_interest_code     VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name                     VARCHAR(50)  NOT NULL,
    description              VARCHAR(500) NOT NULL,
    display_order            INT          NOT NULL,
    active                   BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (subtopic_version, subtopic_code),
    CONSTRAINT uk_interest_subtopic_order UNIQUE (subtopic_version, display_order),
    CONSTRAINT fk_interest_subtopic_set
        FOREIGN KEY (subtopic_version, parent_taxonomy_version)
        REFERENCES interest_subtopic_set (subtopic_version, parent_taxonomy_version),
    CONSTRAINT fk_interest_subtopic_parent
        FOREIGN KEY (parent_taxonomy_version, parent_interest_code)
        REFERENCES interest_category (taxonomy_version, interest_code),
    CONSTRAINT ck_interest_subtopic_order CHECK (display_order > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE member_interest_subtopic (
    member_id         BIGINT       NOT NULL,
    subtopic_version  VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subtopic_code     VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    selected_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (member_id, subtopic_version, subtopic_code),
    CONSTRAINT fk_member_interest_subtopic_member
        FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_member_interest_subtopic_subtopic
        FOREIGN KEY (subtopic_version, subtopic_code)
        REFERENCES interest_subtopic (subtopic_version, subtopic_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

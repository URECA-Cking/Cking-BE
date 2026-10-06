-- 회원 관심 분야 선택 (이슈 #430)
--
-- 한 회원은 같은 분류체계 버전에서 분야를 0~3개 고른다. 3개 상한은 DB로 강제할 수 없어 서비스가 회원 행을 잠근 채
-- 검증한다. 분야는 (taxonomy_version, interest_code) 복합 FK로 분류체계에 묶여, 같은 코드라도 버전이 다르면
-- 다른 분야다.
CREATE TABLE member_interest (
    member_id         BIGINT       NOT NULL,
    taxonomy_version  VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    interest_code     VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    selected_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (member_id, taxonomy_version, interest_code),
    CONSTRAINT fk_member_interest_member
        FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_member_interest_category
        FOREIGN KEY (taxonomy_version, interest_code)
        REFERENCES interest_category (taxonomy_version, interest_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

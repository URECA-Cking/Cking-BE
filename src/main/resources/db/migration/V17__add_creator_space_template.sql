-- 관리자 기본 크리에이터 스페이스 템플릿 (이슈 #242)
--
-- active_marker는 활성 템플릿에서만 1을 갖고, 그 외에는 NULL이다.
-- MySQL UNIQUE 제약은 NULL끼리 서로 다르게 취급하므로, 이 컬럼에 UNIQUE를 걸면
-- "활성 템플릿은 최대 하나"를 DB가 최종 안전망으로 강제한다. 애플리케이션 레벨의
-- advisory lock(GET_LOCK)은 동시 활성화 요청을 직렬화하는 역할만 하고, 실제 유일성
-- 보장은 이 제약이 담당한다.
CREATE TABLE creator_space_template (
    template_id        BIGINT       NOT NULL AUTO_INCREMENT,
    intro_text         VARCHAR(500) NOT NULL,
    profile_image_url  VARCHAR(500) NOT NULL,
    banner_image_url   VARCHAR(500) NOT NULL,
    slug_rule          VARCHAR(100) NOT NULL,
    home_tab_enabled     TINYINT(1) NOT NULL,
    missions_tab_enabled TINYINT(1) NOT NULL,
    posts_tab_enabled    TINYINT(1) NOT NULL,
    events_tab_enabled   TINYINT(1) NOT NULL,
    active_marker      INT          NULL COMMENT '활성 템플릿만 1, 그 외 NULL',
    created_by         BIGINT       NOT NULL,
    updated_by         BIGINT       NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (template_id),
    CONSTRAINT uk_creator_space_template_active UNIQUE (active_marker),
    CONSTRAINT fk_creator_space_template_created_by FOREIGN KEY (created_by) REFERENCES member (member_id),
    CONSTRAINT fk_creator_space_template_updated_by FOREIGN KEY (updated_by) REFERENCES member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

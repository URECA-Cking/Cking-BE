-- Creator 승인 시 활성 기본 템플릿을 복사해 생성하는 Creator Space (이슈 #270)
--
-- creator_id UNIQUE 제약이 크리에이터당 Space 하나만 존재하도록 최종 보장한다.
-- slug UNIQUE 제약은 slug_rule에 {creatorId}가 빠진 값으로 여러 Space가 같은 slug를
-- 갖게 되는 것을 DB 레벨에서 막는다. 템플릿의 값은 생성 시점에 복사만 하며 템플릿을
-- 참조하지 않으므로, 이후 템플릿이 바뀌어도 이미 생성된 Space는 영향받지 않는다.
CREATE TABLE creator_space (
    space_id             BIGINT       NOT NULL AUTO_INCREMENT,
    creator_id           BIGINT       NOT NULL,
    intro_text           VARCHAR(500) NOT NULL,
    profile_image_url    VARCHAR(500) NOT NULL,
    banner_image_url     VARCHAR(500) NOT NULL,
    slug                 VARCHAR(100) NOT NULL,
    home_tab_enabled     TINYINT(1)   NOT NULL,
    missions_tab_enabled TINYINT(1)   NOT NULL,
    posts_tab_enabled    TINYINT(1)   NOT NULL,
    events_tab_enabled   TINYINT(1)   NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (space_id),
    CONSTRAINT uk_creator_space_creator UNIQUE (creator_id),
    CONSTRAINT uk_creator_space_slug UNIQUE (slug),
    CONSTRAINT fk_creator_space_creator FOREIGN KEY (creator_id) REFERENCES creator (creator_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

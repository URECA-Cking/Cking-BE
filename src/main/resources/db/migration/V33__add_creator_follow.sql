-- 크리에이터 팔로우 (이슈 #328)
--
-- 사용자(member)가 크리에이터(creator)를 팔로우한 관계다. 같은 사용자·크리에이터 쌍은
-- uk_creator_follow_member_creator로 하나만 존재하며, 팔로우는 이 제약에 기대어 멱등하게 추가하고
-- 언팔로우는 행을 하드 삭제한다(이력 없음).
--
-- uk_creator_follow_member_creator는 팔로우 여부 조회(member_id, creator_id)도 지원한다.
-- idx_creator_follow_member_created는 내 팔로우 목록을 지원한다.
--   WHERE member_id = ? ORDER BY created_at DESC, follow_id DESC
-- idx_creator_follow_creator는 크리에이터 기준 조회(팔로워 수 등)와 FK를 지원한다.
CREATE TABLE creator_follow (
    follow_id   BIGINT      NOT NULL AUTO_INCREMENT,
    member_id   BIGINT      NOT NULL,
    creator_id  BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (follow_id),
    CONSTRAINT uk_creator_follow_member_creator UNIQUE (member_id, creator_id),
    INDEX idx_creator_follow_member_created (member_id, created_at, follow_id),
    INDEX idx_creator_follow_creator (creator_id),
    CONSTRAINT fk_creator_follow_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_creator_follow_creator FOREIGN KEY (creator_id) REFERENCES creator (creator_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

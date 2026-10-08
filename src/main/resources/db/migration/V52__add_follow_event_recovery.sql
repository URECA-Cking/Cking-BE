-- 부모 회원 행의 FK 잠금과 팔로우/수집 직렬화를 분리한다.
CREATE TABLE member_activity_lock (
    member_id BIGINT NOT NULL PRIMARY KEY,
    FOREIGN KEY (member_id) REFERENCES member (member_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 팔로우와 같은 트랜잭션에 기록한다. 언팔로우에도 원본 시각을 보존해 큐 유실을 복구한다.
CREATE TABLE creator_follow_event (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    member_id BIGINT NOT NULL,
    creator_id BIGINT NOT NULL,
    followed_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6),
    next_attempt_at DATETIME(6) NOT NULL,
    INDEX idx_follow_event_pending (processed_at, next_attempt_at, event_id),
    INDEX idx_follow_event_retention (followed_at, event_id),
    FOREIGN KEY (member_id) REFERENCES member (member_id) ON DELETE CASCADE,
    FOREIGN KEY (creator_id) REFERENCES creator (creator_id) ON DELETE CASCADE
) ENGINE=InnoDB;

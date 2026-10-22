-- 추천 응답 스냅샷과 승인 영수증/고유 행동/신규 팔로우 전환을 분리한다.
CREATE TABLE creator_recommendation_request (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    member_id BIGINT NOT NULL,
    policy_version VARCHAR(60) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (request_id),
    UNIQUE KEY uk_rec_request_member (request_id, member_id),
    INDEX idx_rec_request_member_time (member_id, created_at),
    INDEX idx_rec_request_retention (created_at, request_id),
    FOREIGN KEY (member_id) REFERENCES member (member_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE creator_recommendation_card (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    creator_id BIGINT NOT NULL,
    rank_no INT NOT NULL,
    PRIMARY KEY (request_id, creator_id),
    UNIQUE KEY uk_rec_card_rank (request_id, rank_no),
    INDEX idx_rec_card_creator (creator_id, request_id),
    FOREIGN KEY (request_id) REFERENCES creator_recommendation_request (request_id) ON DELETE CASCADE,
    FOREIGN KEY (creator_id) REFERENCES creator (creator_id) ON DELETE CASCADE,
    CHECK (rank_no > 0)
) ENGINE=InnoDB;

CREATE TABLE creator_recommendation_source (
    source_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    creator_id BIGINT NOT NULL,
    source_type VARCHAR(10) NOT NULL,
    source_key VARCHAR(30) NOT NULL,
    taxonomy_version VARCHAR(20),
    generation_id BIGINT NOT NULL,
    method VARCHAR(20) NOT NULL,
    model_version VARCHAR(255) NOT NULL,
    source_rank INT NOT NULL,
    FOREIGN KEY (request_id, creator_id) REFERENCES creator_recommendation_card (request_id, creator_id) ON DELETE CASCADE,
    CHECK (source_type IN ('FOLLOW', 'INTEREST')),
    CHECK (source_rank > 0)
) ENGINE=InnoDB;

-- 동일 카드에 새 eventId를 써도 모든 승인 ID를 남겨 ID 재사용 충돌을 검증한다.
CREATE TABLE creator_recommendation_event_receipt (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    creator_id BIGINT NOT NULL,
    event_type VARCHAR(10) NOT NULL,
    member_id BIGINT NOT NULL,
    received_at DATETIME(6) NOT NULL,
    INDEX idx_rec_receipt_last_click (member_id, creator_id, event_type, received_at, event_id),
    FOREIGN KEY (request_id, creator_id) REFERENCES creator_recommendation_card (request_id, creator_id) ON DELETE CASCADE,
    FOREIGN KEY (request_id, member_id) REFERENCES creator_recommendation_request (request_id, member_id) ON DELETE CASCADE,
    CHECK (event_type IN ('IMPRESSION', 'CLICK'))
) ENGINE=InnoDB;

CREATE TABLE creator_recommendation_interaction (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    creator_id BIGINT NOT NULL,
    event_type VARCHAR(10) NOT NULL,
    member_id BIGINT NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    received_at DATETIME(6) NOT NULL,
    PRIMARY KEY (request_id, creator_id, event_type),
    UNIQUE KEY uk_rec_interaction_event (event_id),
    INDEX idx_rec_interaction_last_click (member_id, creator_id, event_type, received_at, event_id),
    INDEX idx_rec_interaction_time (event_type, received_at),
    FOREIGN KEY (request_id, creator_id) REFERENCES creator_recommendation_card (request_id, creator_id) ON DELETE CASCADE,
    FOREIGN KEY (request_id, member_id) REFERENCES creator_recommendation_request (request_id, member_id) ON DELETE CASCADE,
    FOREIGN KEY (event_id) REFERENCES creator_recommendation_event_receipt (event_id) ON DELETE CASCADE,
    CHECK (event_type IN ('IMPRESSION', 'CLICK'))
) ENGINE=InnoDB;

CREATE TABLE creator_recommendation_conversion (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    creator_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    click_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    followed_at DATETIME(6) NOT NULL,
    attribution_policy VARCHAR(30) NOT NULL,
    attribution_window_seconds BIGINT NOT NULL,
    PRIMARY KEY (request_id, creator_id),
    INDEX idx_rec_conversion_member_creator_time (member_id, creator_id, followed_at),
    INDEX idx_rec_conversion_time (followed_at),
    FOREIGN KEY (request_id, creator_id) REFERENCES creator_recommendation_card (request_id, creator_id) ON DELETE CASCADE,
    FOREIGN KEY (request_id, member_id) REFERENCES creator_recommendation_request (request_id, member_id) ON DELETE CASCADE,
    FOREIGN KEY (click_event_id) REFERENCES creator_recommendation_event_receipt (event_id) ON DELETE CASCADE,
    CHECK (attribution_window_seconds > 0)
) ENGINE=InnoDB;

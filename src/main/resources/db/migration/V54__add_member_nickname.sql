-- OAuth Provider의 원본 이름과 서비스 공개 활동명(닉네임)을 분리한다.
-- 기존 회원의 공개 이름은 자동 이관하지 않으며, nickname NULL은 아직 설정하지 않았음을 뜻한다.
ALTER TABLE member
    ADD COLUMN nickname VARCHAR(20) NULL AFTER name,
    ADD COLUMN nickname_normalized VARCHAR(20) NULL AFTER nickname,
    ADD COLUMN nickname_changed_at DATETIME(6) NULL AFTER nickname_normalized,
    ADD COLUMN previous_nickname VARCHAR(20) NULL AFTER nickname_changed_at,
    ADD COLUMN previous_nickname_normalized VARCHAR(20) NULL AFTER previous_nickname,
    ADD CONSTRAINT uk_member_nickname_normalized UNIQUE (nickname_normalized);

-- 닉네임을 변경하며 버린 값은 14일 동안 기존 회원만 복귀할 수 있게 잡아 둔다.
CREATE TABLE member_nickname_reservation (
    reservation_id BIGINT NOT NULL AUTO_INCREMENT,
    nickname VARCHAR(20) NOT NULL,
    nickname_normalized VARCHAR(20) NOT NULL,
    member_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reservation_id),
    CONSTRAINT uk_member_nickname_reservation_normalized UNIQUE (nickname_normalized),
    CONSTRAINT fk_member_nickname_reservation_member
        FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

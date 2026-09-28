-- Creator Space 이전 slug 예약 (이슈 #301)
--
-- Creator가 slug를 바꾸면 이전 slug를 일정 기간(14일) 예약해, 이미 퍼진 공유 링크가 곧바로
-- 다른 Creator의 Space를 열지 않게 한다. 예약한 Creator 본인은 기간 안에 되돌릴 수 있다.
-- 만료는 expires_at과 현재 시각을 비교해 판단하며, 만료된 행은 같은 slug의 다음 예약이나
-- 사용 시점에 덮어쓰거나 지운다(별도 정리 배치 없음).
CREATE TABLE creator_space_slug_reservation (
    reservation_id BIGINT       NOT NULL AUTO_INCREMENT,
    slug           VARCHAR(100) NOT NULL,
    creator_id     BIGINT       NOT NULL,
    expires_at     DATETIME(6)  NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (reservation_id),
    CONSTRAINT uk_creator_space_slug_reservation_slug UNIQUE (slug),
    CONSTRAINT fk_creator_space_slug_reservation_creator FOREIGN KEY (creator_id) REFERENCES creator (creator_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

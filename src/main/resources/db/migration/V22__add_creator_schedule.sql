-- 크리에이터 캘린더 일정 (이슈 #293)
--
-- 기존 추첨용 event와는 완전히 분리된 테이블이다. Snapshot·Drawing·Winner로 이어지는
-- 하류 감사 이력이 없어 소프트 삭제 없이 하드 삭제하며, 참조하는 member_calendar_entry도
-- 함께 삭제한다(후속 이슈에서 cascade 적용).
--
-- idx_creator_schedule_creator_start는 다음 조회를 지원한다.
--   WHERE creator_id = ? AND start_at < :to AND end_at > :from
--   ORDER BY start_at ASC, schedule_id ASC
-- end_at 조건은 인덱스만으로 전부 걸러지지 않는 residual condition이라 creator_id 동등조건과
-- start_at 범위조건, 정렬까지만 인덱스로 처리한다.
CREATE TABLE creator_schedule (
    schedule_id    BIGINT       NOT NULL AUTO_INCREMENT,
    creator_id     BIGINT       NOT NULL,
    schedule_type  VARCHAR(30)  NOT NULL,
    title          VARCHAR(100) NOT NULL,
    description    VARCHAR(1000) NULL,
    start_at       DATETIME(6)  NOT NULL,
    end_at         DATETIME(6)  NOT NULL,
    time_zone      VARCHAR(50)  NOT NULL,
    location       VARCHAR(200) NULL,
    image_url      VARCHAR(500) NULL,
    external_url   VARCHAR(500) NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (schedule_id),
    INDEX idx_creator_schedule_creator_start (creator_id, start_at, schedule_id),
    CONSTRAINT fk_creator_schedule_creator FOREIGN KEY (creator_id) REFERENCES creator (creator_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 사용자 개인 캘린더 담기 (이슈 #319)
--
-- creator_schedule을 그대로 참조만 하고 일정 내용을 복사하지 않는다. 크리에이터가 일정을
-- 수정하면 개인 캘린더에도 최신 값이 그대로 보인다.
--
-- creator_schedule은 하드 삭제되므로(V22 참고) 참조가 끊긴 행이 남지 않도록
-- ON DELETE CASCADE로 함께 삭제한다.
--
-- uk_member_calendar_entry_member_schedule(member_id, schedule_id)는 중복 담기를 막는
-- 멱등성 제약이며, 왼쪽 접두사 member_id로 개인 캘린더 목록 조회도 지원해 별도 인덱스가 필요 없다.
CREATE TABLE member_calendar_entry (
    entry_id    BIGINT      NOT NULL AUTO_INCREMENT,
    member_id   BIGINT      NOT NULL,
    schedule_id BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (entry_id),
    UNIQUE KEY uk_member_calendar_entry_member_schedule (member_id, schedule_id),
    CONSTRAINT fk_member_calendar_entry_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_member_calendar_entry_schedule FOREIGN KEY (schedule_id) REFERENCES creator_schedule (schedule_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

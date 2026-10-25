-- 종일 일정 여부. 종일이면 start_at·end_at은 time_zone 기준 첫날 0시와 마지막 날 다음 날 0시다(end_at은 포함하지 않는다).
-- 기본값이 있어 롤링 배포 중 함께 도는 옛 버전 서버의 INSERT·조회도 그대로 동작한다.
ALTER TABLE creator_schedule
    ADD COLUMN all_day BOOLEAN NOT NULL DEFAULT FALSE AFTER end_at;

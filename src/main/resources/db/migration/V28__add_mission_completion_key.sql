-- SHARE는 Creator별 평생 1회, LIKE는 기존 일일 1회를 유지하기 위한 완료 이력 키다.
ALTER TABLE mission_completion
    ADD COLUMN completion_key VARCHAR(10) NULL AFTER period_key;

UPDATE mission_completion
SET completion_key = period_key
WHERE completion_key IS NULL;

ALTER TABLE mission_completion
    MODIFY COLUMN completion_key VARCHAR(10) NOT NULL COMMENT 'DAILY는 YYYY-MM-DD, ONCE는 ONCE';

ALTER TABLE mission_completion
    DROP INDEX uk_completion_business,
    ADD CONSTRAINT uk_completion_business UNIQUE (member_id, creator_id, mission_id, completion_key);

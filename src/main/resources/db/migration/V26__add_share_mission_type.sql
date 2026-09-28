-- Creator별 SHARE 미션 유형 추가 (이슈 #309)
--
-- mission.type은 JPA EnumType.STRING과 호환되는 VARCHAR라 새 enum 값을 위한 타입 변경은
-- 필요 없다. 운영 DB 메타데이터의 허용 값 설명을 최신화하고, 기존 Creator에는
-- 누락된 SHARE 기본 미션을 멱등하게 시딩한다.
ALTER TABLE mission
    MODIFY COLUMN type VARCHAR(30) NOT NULL COMMENT 'ATTENDANCE, LIKE, SHARE, YOUTUBE_SUBSCRIPTION';

INSERT INTO mission (creator_id, type, reward_amount, active_from, active_to)
SELECT creator.creator_id, 'SHARE', 1, NULL, NULL
FROM creator
WHERE NOT EXISTS (
    SELECT 1
    FROM mission
    WHERE mission.creator_id = creator.creator_id
      AND mission.type = 'SHARE'
);

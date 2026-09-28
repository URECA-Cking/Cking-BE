-- 기존 Creator의 누락된 SHARE 기본 미션을 멱등하게 시딩한다 (이슈 #309).
-- V26은 이미 실행된 환경이 있을 수 있으므로 변경하지 않고, 데이터 변경은 전진 전용
-- 마이그레이션으로 분리한다.
INSERT INTO mission (creator_id, type, reward_amount, active_from, active_to)
SELECT creator.creator_id, 'SHARE', 1, NULL, NULL
FROM creator
WHERE NOT EXISTS (
    SELECT 1
    FROM mission
    WHERE mission.creator_id = creator.creator_id
      AND mission.type = 'SHARE'
);

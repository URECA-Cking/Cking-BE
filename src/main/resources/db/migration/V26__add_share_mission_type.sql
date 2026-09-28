-- Creator별 SHARE 미션 유형 추가 (이슈 #309)
--
-- mission.type은 JPA EnumType.STRING과 호환되는 VARCHAR라 새 enum 값을 위한 타입 변경은
-- 필요 없다. 운영 DB 메타데이터의 허용 값 설명만 최신화한다.
ALTER TABLE mission
    MODIFY COLUMN type VARCHAR(30) NOT NULL COMMENT 'ATTENDANCE, LIKE, SHARE, YOUTUBE_SUBSCRIPTION';

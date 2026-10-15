-- 실행권의 만료 기준을 별도로 기록해 같은 요청의 안전한 조건부 회수를 허용한다.
ALTER TABLE ticket_common_once_earn_request
    ADD COLUMN executing_at DATETIME(6) NULL COMMENT 'EXECUTING 실행권 발급 시각',
    MODIFY COLUMN status VARCHAR(20) NOT NULL COMMENT 'PENDING, EXECUTING, ACCEPTED';

-- 기존 EXECUTING 행은 최초 선점 시각부터 오래된 요청으로 취급한다.
UPDATE ticket_common_once_earn_request
SET executing_at = created_at
WHERE status = 'EXECUTING';

-- EARN 재전달 멱등성 판정을 위한 payload fingerprint 추가
--
-- 리뷰 지적: verifySameRequest()가 userId/creatorId/missionId/periodKey/amount만
-- 비교하고 missionType/missionKey는 비교하지 않아, 이 두 필드만 다른 재전달을
-- 정상 재전달로 오판할 수 있었다. EntrySpendServiceImpl의 SPEND 쪽과 동일하게
-- 전체 payload의 SHA-256 fingerprint를 저장·비교하는 방식으로 교체한다.

ALTER TABLE mission_completion
    ADD COLUMN payload_fingerprint CHAR(64) NOT NULL COMMENT 'EARN 요청 전체 payload의 SHA-256 hex' AFTER request_id;

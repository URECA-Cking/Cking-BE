-- 비정상 행동 탐지 결과 (이슈 #381)
--
-- 탐지 원본과 관리자 검토 이력을 함께 보관한다. Evidence는 타입별 scope, 집계 feature,
-- threshold와 매칭 규칙만 저장하며 요청 본문·토큰·개인정보는 저장하지 않는다.
--
-- 세 인덱스는 각각 회원별 이력, 미검토/검토 상태별 운영 목록, 탐지 유형별 분석 조회를
-- detected_at 내림차순 정렬 기준으로 지원한다.
CREATE TABLE abuse_detection (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    member_id    BIGINT       NOT NULL,
    abuse_type   VARCHAR(64)  NOT NULL,
    status       VARCHAR(32)  NOT NULL,
    detected_at  DATETIME(6)  NOT NULL,
    reviewed_at  DATETIME(6)  NULL,
    reviewed_by  BIGINT       NULL,
    evidence     JSON         NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_abuse_detection_member
        FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_abuse_detection_reviewer
        FOREIGN KEY (reviewed_by) REFERENCES member (member_id),
    CONSTRAINT ck_abuse_detection_status
        CHECK (status IN ('DETECTED', 'CONFIRMED', 'FALSE_POSITIVE')),
    INDEX idx_abuse_detection_member_detected (member_id, detected_at),
    INDEX idx_abuse_detection_status_detected (status, detected_at),
    INDEX idx_abuse_detection_type_detected (abuse_type, detected_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

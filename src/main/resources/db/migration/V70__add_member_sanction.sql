-- 회원 제재 (이슈 #569)
--
-- 관리자가 신고된 댓글을 숨기면 작성자에게 경고가 쌓이고, 최근 90일 안의 경고 횟수에 따라 댓글 작성이 기간제로
-- 제한된다. 관리자가 직접 제한을 걸 수도 있다. 제한 기록은 삭제하지 않고 집계 기간만 적용한다.
--
-- source
--   AUTOMATIC : 관리자의 댓글 숨김으로 자동 생성된 경고 겸 제한. 경고 횟수 집계 대상이다.
--   MANUAL    : 관리자가 직접 건 제한. 경고 횟수에 세지 않는다.
-- scope / type
--   지금은 PLATFORM(플랫폼 전체) / COMMENT_RESTRICTION(댓글 작성·수정 제한)만 쓴다. 특정 스페이스 범위 제한은
--   후속 이슈에서 같은 테이블에 확장한다.
-- cause_comment_id
--   AUTOMATIC의 원인 댓글. 같은 댓글로 경고가 두 번 쌓이지 않도록 UNIQUE를 둔다. 댓글이 삭제돼도 제재 기록은 남아야
--   하므로 FK는 걸지 않는다(MANUAL은 NULL이라 여러 건 가능).
-- ends_at / released_at
--   제한은 started_at <= now < ends_at 동안 유효하다. 관리자가 해제하면 released_at에 해제 시각을 남기며 그 시각부터
--   무효다. 해제해도 AUTOMATIC 경고는 집계에 남는다.
--
-- idx_member_sanction_member_started: 최근 N일 경고 횟수 집계(member_id, source, started_at 범위).
-- idx_member_sanction_member_ends   : 현재 유효한 제한 조회(member_id, ends_at 범위). 댓글 작성·수정마다 확인한다.
-- idx_member_sanction_started       : 관리자 목록의 최신순 정렬.
CREATE TABLE member_sanction (
    sanction_id      BIGINT       NOT NULL AUTO_INCREMENT,
    member_id        BIGINT       NOT NULL,
    scope            VARCHAR(20)  NOT NULL,
    type             VARCHAR(30)  NOT NULL,
    source           VARCHAR(20)  NOT NULL,
    cause_comment_id BIGINT       NULL,
    reason           VARCHAR(200) NULL,
    started_at       DATETIME(6)  NOT NULL,
    ends_at          DATETIME(6)  NOT NULL,
    created_by       BIGINT       NOT NULL,
    released_at      DATETIME(6)  NULL,
    released_by      BIGINT       NULL,
    PRIMARY KEY (sanction_id),
    CONSTRAINT uk_member_sanction_cause_comment UNIQUE (cause_comment_id),
    CONSTRAINT fk_member_sanction_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_member_sanction_created_by FOREIGN KEY (created_by) REFERENCES member (member_id),
    CONSTRAINT fk_member_sanction_released_by FOREIGN KEY (released_by) REFERENCES member (member_id),
    CONSTRAINT ck_member_sanction_period CHECK (ends_at > started_at),
    CONSTRAINT ck_member_sanction_release CHECK ((released_at IS NULL) = (released_by IS NULL)),
    INDEX idx_member_sanction_member_started (member_id, source, started_at),
    INDEX idx_member_sanction_member_ends (member_id, ends_at),
    INDEX idx_member_sanction_started (started_at, sanction_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

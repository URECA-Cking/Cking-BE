-- Creator Space 게시글 댓글 신고 (이슈 #485)
--
-- 로그인한 사용자가 댓글을 신고하면 한 건을 저장한다. 신고는 댓글의 노출 상태나 필터 판정을 바꾸지 않으며, 관리자가
-- 신고된 댓글을 조회하는 데만 쓴다. 신고자 정보는 댓글 작성자와 Creator에게 노출하지 않는다.
--
-- reason : ABUSE(욕설·혐오), SPAM(스팸·홍보), PRIVACY(개인정보 노출), OTHER(기타)
-- detail : reason이 OTHER일 때만 받는 짧은 설명이다. 그 밖의 사유에서는 NULL이다.
--
-- uk_comment_report_comment_reporter는 같은 사용자가 같은 댓글을 두 번 신고하지 못하게 하고, 관리자 목록이
--   GROUP BY comment_id 로 댓글별 신고를 모을 때의 인덱스로도 쓰인다.
-- idx_comment_report_reporter_created는 신고자별 반복 신고 제한을 지원한다.
--   WHERE reporter_member_id = ? AND created_at >= ?
--
-- 댓글(게시글 삭제 포함)이 하드 삭제되면 그 댓글의 신고도 함께 지워지도록 ON DELETE CASCADE를 둔다. 게시글 삭제는
-- JPQL 일괄 DELETE로 댓글을 지우므로 애플리케이션에서 신고를 따로 지우지 않아도 된다.
CREATE TABLE creator_post_comment_report (
    report_id          BIGINT       NOT NULL AUTO_INCREMENT,
    comment_id         BIGINT       NOT NULL,
    reporter_member_id BIGINT       NOT NULL,
    reason             VARCHAR(20)  NOT NULL,
    detail             VARCHAR(200) NULL,
    created_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (report_id),
    CONSTRAINT uk_comment_report_comment_reporter UNIQUE (comment_id, reporter_member_id),
    INDEX idx_comment_report_reporter_created (reporter_member_id, created_at),
    CONSTRAINT fk_comment_report_comment FOREIGN KEY (comment_id)
        REFERENCES creator_post_comment (comment_id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_report_reporter FOREIGN KEY (reporter_member_id) REFERENCES member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

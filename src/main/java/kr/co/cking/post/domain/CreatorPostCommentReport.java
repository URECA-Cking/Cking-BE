package kr.co.cking.post.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * 게시글 댓글 신고(이슈 #485). 신고는 댓글의 노출 상태나 필터 판정을 바꾸지 않는다. 같은 신고자는 같은 댓글을 한 번만
 * 신고할 수 있고, 댓글이 삭제되면 DB가 함께 지운다.
 */
@Entity
@Table(name = "creator_post_comment_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorPostCommentReport {

    public static final int MAX_DETAIL_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Column(name = "reporter_member_id", nullable = false)
    private Long reporterMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20)
    private CommentReportReason reason;

    @Column(name = "detail", length = MAX_DETAIL_LENGTH)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public CreatorPostCommentReport(
            Long commentId, Long reporterMemberId, CommentReportReason reason, String detail, Instant now) {
        this.commentId = Objects.requireNonNull(commentId, "commentId");
        this.reporterMemberId = Objects.requireNonNull(reporterMemberId, "reporterMemberId");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.detail = detail;
        this.createdAt = Objects.requireNonNull(now, "now");
    }
}

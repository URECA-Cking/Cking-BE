package kr.co.cking.post.repository;

import kr.co.cking.post.domain.CreatorPostCommentReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CreatorPostCommentReportRepository extends JpaRepository<CreatorPostCommentReport, Long> {

    Optional<CreatorPostCommentReport> findByCommentIdAndReporterMemberId(Long commentId, Long reporterMemberId);

    /** 신고자의 반복 신고를 제한하려고 최근 신고 수를 센다. */
    long countByReporterMemberIdAndCreatedAtGreaterThanEqual(Long reporterMemberId, Instant since);

    /** 신고된 댓글을 가장 최근에 신고된 순서(tie-breaker는 댓글 ID 내림차순)로 모아 가져온다. */
    @Query(value = """
            select new kr.co.cking.post.repository.CommentReportSummary(
                       r.commentId, count(r), max(r.createdAt))
              from CreatorPostCommentReport r
             group by r.commentId
             order by max(r.createdAt) desc, r.commentId desc
            """,
            countQuery = "select count(distinct r.commentId) from CreatorPostCommentReport r")
    Page<CommentReportSummary> findSummaries(Pageable pageable);

    /** 목록에 나온 댓글들의 신고를 사유별로 센다. */
    @Query("""
            select new kr.co.cking.post.repository.CommentReportReasonCount(r.commentId, r.reason, count(r))
              from CreatorPostCommentReport r
             where r.commentId in :commentIds
             group by r.commentId, r.reason
            """)
    List<CommentReportReasonCount> countReasons(@Param("commentIds") Collection<Long> commentIds);
}

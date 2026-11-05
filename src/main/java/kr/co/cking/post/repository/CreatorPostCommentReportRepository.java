package kr.co.cking.post.repository;

import kr.co.cking.post.domain.CommentReportStatus;
import kr.co.cking.post.domain.CreatorPostCommentReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /** 신고된 댓글 목록 쿼리가 공유하는 조각. 댓글별로 모은 신고 수, 최근 신고 시각, 접수 건수를 고른다. */
    String SUMMARY_SELECT = """
            select new kr.co.cking.post.repository.CommentReportSummary(
                       r.commentId, count(r), max(r.createdAt),
                       sum(case when r.status = kr.co.cking.post.domain.CommentReportStatus.PENDING then 1L else 0L end))
              from CreatorPostCommentReport r
            """;
    String SUMMARY_ORDER = """
             group by r.commentId
             order by max(r.createdAt) desc, r.commentId desc
            """;
    /** 그 댓글에 접수(PENDING) 신고가 있다. */
    String HAS_PENDING = """
            exists (select 1 from CreatorPostCommentReport p
                     where p.commentId = r.commentId
                       and p.status = kr.co.cking.post.domain.CommentReportStatus.PENDING)
            """;
    /** 그 댓글을 관리자가 숨겼다. */
    String IS_HIDDEN = """
            exists (select 1 from CreatorPostComment c
                     where c.commentId = r.commentId and c.adminHiddenAt is not null)
            """;

    /** 신고된 댓글을 가장 최근에 신고된 순서(tie-breaker는 댓글 ID 내림차순)로 모아 가져온다. */
    @Query(value = SUMMARY_SELECT + SUMMARY_ORDER,
            countQuery = "select count(distinct r.commentId) from CreatorPostCommentReport r")
    Page<CommentReportSummary> findSummaries(Pageable pageable);

    /** 접수(PENDING) 신고가 남아 있는 댓글만 모은다. */
    @Query(value = SUMMARY_SELECT + " where " + HAS_PENDING + SUMMARY_ORDER,
            countQuery = "select count(distinct r.commentId) from CreatorPostCommentReport r where " + HAS_PENDING)
    Page<CommentReportSummary> findPendingSummaries(Pageable pageable);

    /** 접수 신고가 모두 처리됐고 관리자가 숨긴 댓글(처리 완료)만 모은다. */
    @Query(value = SUMMARY_SELECT + " where not " + HAS_PENDING + " and " + IS_HIDDEN + SUMMARY_ORDER,
            countQuery = "select count(distinct r.commentId) from CreatorPostCommentReport r where not "
                    + HAS_PENDING + " and " + IS_HIDDEN)
    Page<CommentReportSummary> findResolvedSummaries(Pageable pageable);

    /** 접수 신고가 모두 처리됐고 숨기지 않은 댓글(반려)만 모은다. */
    @Query(value = SUMMARY_SELECT + " where not " + HAS_PENDING + " and not " + IS_HIDDEN + SUMMARY_ORDER,
            countQuery = "select count(distinct r.commentId) from CreatorPostCommentReport r where not "
                    + HAS_PENDING + " and not " + IS_HIDDEN)
    Page<CommentReportSummary> findRejectedSummaries(Pageable pageable);

    /** 그 댓글에 접수된 신고(처리 여부와 무관)의 전체 수. */
    long countByCommentId(Long commentId);

    /**
     * 그 댓글의 접수(PENDING) 신고를 모두 같은 상태로 처리한다. 처리한 신고 수를 돌려주고, 접수 신고가 없으면 0이다.
     * 일괄 갱신이라 영속성 컨텍스트의 엔티티는 갱신하지 않으므로 이 뒤에 같은 신고 엔티티를 읽지 않는다.
     */
    @Modifying
    @Query("""
            update CreatorPostCommentReport r
               set r.status = :status, r.handledBy = :adminId, r.handledAt = :now
             where r.commentId = :commentId
               and r.status = kr.co.cking.post.domain.CommentReportStatus.PENDING
            """)
    int markHandled(@Param("commentId") Long commentId, @Param("status") CommentReportStatus status,
                    @Param("adminId") Long adminId, @Param("now") Instant now);

    /** 목록에 나온 댓글들의 신고를 사유별로 센다. */
    @Query("""
            select new kr.co.cking.post.repository.CommentReportReasonCount(r.commentId, r.reason, count(r))
              from CreatorPostCommentReport r
             where r.commentId in :commentIds
             group by r.commentId, r.reason
            """)
    List<CommentReportReasonCount> countReasons(@Param("commentIds") Collection<Long> commentIds);

    /**
     * 목록에 나온 댓글들의 기타 신고 설명을 댓글별로 최근 {@code limit}건까지 읽는다(이슈 #811). 신고가 몰린 댓글의 설명을
     * 전부 서버로 가져오지 않도록 DB에서 자른다. 신고자는 읽지 않는다.
     */
    @Query(value = """
            select ranked.comment_id as commentId, ranked.detail as detail
              from (select r.comment_id, r.detail, r.created_at, r.report_id,
                           row_number() over (partition by r.comment_id
                                              order by r.created_at desc, r.report_id desc) as row_num
                      from creator_post_comment_report r
                     where r.comment_id in (:commentIds)
                       and r.detail is not null) ranked
             where ranked.row_num <= :limit
             order by ranked.created_at desc, ranked.report_id desc
            """, nativeQuery = true)
    List<CommentReportDetail> findDetails(
            @Param("commentIds") Collection<Long> commentIds, @Param("limit") int limit);

    /** 목록에 나온 댓글들을 처리한 관리자별 마지막 처리 시각을 읽는다(이슈 #811). */
    @Query("""
            select new kr.co.cking.post.repository.CommentReportHandling(r.commentId, r.handledBy, max(r.handledAt))
              from CreatorPostCommentReport r
             where r.commentId in :commentIds
               and r.handledAt is not null
             group by r.commentId, r.handledBy
            """)
    List<CommentReportHandling> findHandlings(@Param("commentIds") Collection<Long> commentIds);
}

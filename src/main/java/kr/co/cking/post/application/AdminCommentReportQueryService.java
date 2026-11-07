package kr.co.cking.post.application;

import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.post.application.dto.AdminCommentDisplay;
import kr.co.cking.post.application.dto.AdminCommentReportView;
import kr.co.cking.post.domain.CommentReportReason;
import kr.co.cking.post.domain.CommentReportStatus;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.repository.CommentReportDetail;
import kr.co.cking.post.repository.CommentReportHandling;
import kr.co.cking.post.repository.CommentReportReasonCount;
import kr.co.cking.post.repository.CommentReportSummary;
import kr.co.cking.post.repository.CreatorPostCommentReportRepository;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자가 신고된 댓글을 모아 조회한다. 관리자 업무 권한을 검증한 뒤 댓글별 신고를 최근 신고 순으로 돌려준다.
 * 처리 상태로 거를 수 있다(이슈 #499): PENDING은 접수 신고가 남은 댓글, RESOLVED는 관리자가 숨긴 댓글, REJECTED는
 * 접수 신고가 모두 반려된 댓글이다.
 */
@Service
@RequiredArgsConstructor
public class AdminCommentReportQueryService {

    /** 댓글 하나에 내려주는 기타 신고 설명의 최대 개수. 전체 건수는 사유별 신고 수로 알 수 있다. */
    static final int MAX_REPORT_DETAILS = 20;

    private final MemberQueryService memberQueryService;
    private final CreatorPostCommentReportRepository reportRepository;
    private final CreatorPostCommentRepository commentRepository;
    private final AdminCommentDisplayReader displayReader;

    @Transactional(readOnly = true)
    public Page<AdminCommentReportView> findReportedComments(
            Long adminId, CommentReportStatus status, Pageable pageable) {
        memberQueryService.validateAdmin(adminId);

        Page<CommentReportSummary> summaries = findSummaries(status, pageable);
        List<Long> commentIds = summaries.stream().map(CommentReportSummary::commentId).toList();
        if (commentIds.isEmpty()) {
            // 신고된 댓글이 있어도 마지막 페이지를 넘겨 조회하면 요청한 페이지만 비어 있다. 전체 건수는 유지한다.
            return new PageImpl<>(List.of(), pageable, summaries.getTotalElements());
        }

        Map<Long, CreatorPostComment> comments = commentRepository.findAllById(commentIds).stream()
                .collect(Collectors.toMap(CreatorPostComment::getCommentId, Function.identity()));
        Map<Long, AdminCommentDisplay> displays = displayReader.readByCommentId(comments.values());
        Map<Long, Map<CommentReportReason, Long>> reasonCounts = reasonCountsByComment(commentIds);
        Map<Long, List<String>> details = detailsByComment(commentIds);
        Map<Long, CommentReportHandling> handlings = latestHandlingByComment(commentIds);

        return summaries.map(summary -> {
            CreatorPostComment comment = comments.get(summary.commentId());
            AdminCommentDisplay display = displays.get(summary.commentId());
            CommentReportHandling handling = handlings.get(summary.commentId());
            long pendingCount = summary.pendingCount() == null ? 0 : summary.pendingCount();
            return new AdminCommentReportView(
                    comment.getCommentId(),
                    comment.getPostId(),
                    display.postPreview(),
                    display.creatorId(),
                    display.creatorName(),
                    comment.getMemberId(),
                    display.authorName(),
                    comment.getContent(),
                    comment.isBlocked(),
                    comment.blockReasons(),
                    comment.getFilterStatus(),
                    comment.isHiddenByAdmin(),
                    statusOf(pendingCount, comment),
                    summary.reportCount(),
                    pendingCount,
                    reasonCounts.getOrDefault(summary.commentId(), Map.of()),
                    details.getOrDefault(summary.commentId(), List.of()),
                    handling == null ? null : handling.handledBy(),
                    handling == null ? null : handling.handledAt(),
                    summary.latestReportedAt());
        });
    }

    private Page<CommentReportSummary> findSummaries(CommentReportStatus status, Pageable pageable) {
        if (status == null) {
            return reportRepository.findSummaries(pageable);
        }
        return switch (status) {
            case PENDING -> reportRepository.findPendingSummaries(pageable);
            case RESOLVED -> reportRepository.findResolvedSummaries(pageable);
            case REJECTED -> reportRepository.findRejectedSummaries(pageable);
        };
    }

    /** 접수 신고가 남아 있으면 PENDING, 모두 처리됐으면 숨김 여부로 RESOLVED(숨김)와 REJECTED(반려)를 가른다. */
    private CommentReportStatus statusOf(long pendingCount, CreatorPostComment comment) {
        if (pendingCount > 0) {
            return CommentReportStatus.PENDING;
        }
        return comment.isHiddenByAdmin() ? CommentReportStatus.RESOLVED : CommentReportStatus.REJECTED;
    }

    private Map<Long, Map<CommentReportReason, Long>> reasonCountsByComment(List<Long> commentIds) {
        Map<Long, Map<CommentReportReason, Long>> result = new HashMap<>();
        for (CommentReportReasonCount row : reportRepository.countReasons(commentIds)) {
            result.computeIfAbsent(row.commentId(), id -> new EnumMap<>(CommentReportReason.class))
                    .put(row.reason(), row.count());
        }
        return result;
    }

    /** 댓글별 기타 신고 설명을 최근 신고 순으로 {@value #MAX_REPORT_DETAILS}건까지 모은다. */
    private Map<Long, List<String>> detailsByComment(List<Long> commentIds) {
        Map<Long, List<String>> result = new HashMap<>();
        for (CommentReportDetail row : reportRepository.findDetails(commentIds, MAX_REPORT_DETAILS)) {
            result.computeIfAbsent(row.getCommentId(), id -> new ArrayList<>()).add(row.getDetail());
        }
        return result;
    }

    /** 댓글별 가장 최근 처리를 고른다. 접수 신고는 한 번에 처리되므로 처리는 시각 단위로 하나다. */
    private Map<Long, CommentReportHandling> latestHandlingByComment(List<Long> commentIds) {
        return reportRepository.findHandlings(commentIds).stream()
                .collect(Collectors.toMap(CommentReportHandling::commentId, Function.identity(),
                        (left, right) -> left.handledAt().isAfter(right.handledAt()) ? left : right));
    }
}

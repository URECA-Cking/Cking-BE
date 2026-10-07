package kr.co.cking.post.application;

import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.post.application.dto.AdminCommentReportView;
import kr.co.cking.post.domain.CommentReportReason;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.repository.CommentReportReasonCount;
import kr.co.cking.post.repository.CommentReportSummary;
import kr.co.cking.post.repository.CreatorPostCommentReportRepository;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 관리자가 신고된 댓글을 모아 조회한다. 관리자 업무 권한을 검증한 뒤 댓글별 신고를 최근 신고 순으로 돌려준다. */
@Service
@RequiredArgsConstructor
public class AdminCommentReportQueryService {

    private final MemberQueryService memberQueryService;
    private final CreatorPostCommentReportRepository reportRepository;
    private final CreatorPostCommentRepository commentRepository;
    private final CreatorPostRepository postRepository;

    @Transactional(readOnly = true)
    public Page<AdminCommentReportView> findReportedComments(Long adminId, Pageable pageable) {
        memberQueryService.validateAdmin(adminId);

        Page<CommentReportSummary> summaries = reportRepository.findSummaries(pageable);
        List<Long> commentIds = summaries.stream().map(CommentReportSummary::commentId).toList();
        if (commentIds.isEmpty()) {
            return Page.empty(pageable);
        }

        Map<Long, CreatorPostComment> comments = commentRepository.findAllById(commentIds).stream()
                .collect(Collectors.toMap(CreatorPostComment::getCommentId, Function.identity()));
        Map<Long, Long> creatorIdsByPost = postRepository.findAllById(
                        comments.values().stream().map(CreatorPostComment::getPostId).distinct().toList()).stream()
                .collect(Collectors.toMap(CreatorPost::getPostId, CreatorPost::getCreatorId));
        Map<Long, Map<CommentReportReason, Long>> reasonCounts = reasonCountsByComment(commentIds);

        return summaries.map(summary -> {
            CreatorPostComment comment = comments.get(summary.commentId());
            return new AdminCommentReportView(
                    comment.getCommentId(),
                    comment.getPostId(),
                    creatorIdsByPost.get(comment.getPostId()),
                    comment.getMemberId(),
                    comment.getContent(),
                    comment.isBlocked(),
                    summary.reportCount(),
                    reasonCounts.getOrDefault(summary.commentId(), Map.of()),
                    summary.latestReportedAt());
        });
    }

    private Map<Long, Map<CommentReportReason, Long>> reasonCountsByComment(List<Long> commentIds) {
        Map<Long, Map<CommentReportReason, Long>> result = new HashMap<>();
        for (CommentReportReasonCount row : reportRepository.countReasons(commentIds)) {
            result.computeIfAbsent(row.commentId(), id -> new EnumMap<>(CommentReportReason.class))
                    .put(row.reason(), row.count());
        }
        return result;
    }
}

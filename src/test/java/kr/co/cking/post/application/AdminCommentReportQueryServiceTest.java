package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.post.application.dto.AdminCommentReportView;
import kr.co.cking.post.domain.CommentFilterAction;
import kr.co.cking.post.domain.CommentReportReason;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.domain.PostVisibility;
import kr.co.cking.post.repository.CommentReportReasonCount;
import kr.co.cking.post.repository.CommentReportSummary;
import kr.co.cking.post.repository.CreatorPostCommentReportRepository;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static kr.co.cking.post.PostFixtures.NOW;
import static kr.co.cking.post.PostFixtures.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AdminCommentReportQueryServiceTest {

    private static final PageRequest PAGE = PageRequest.of(0, 20);

    private final MemberQueryService memberQueryService = mock(MemberQueryService.class);
    private final CreatorPostCommentReportRepository reportRepository = mock(CreatorPostCommentReportRepository.class);
    private final CreatorPostCommentRepository commentRepository = mock(CreatorPostCommentRepository.class);
    private final CreatorPostRepository postRepository = mock(CreatorPostRepository.class);
    private final AdminCommentReportQueryService service = new AdminCommentReportQueryService(
            memberQueryService, reportRepository, commentRepository, postRepository);

    @Test
    void 신고된_댓글을_원문_차단_여부_신고_수_사유별_수와_함께_돌려준다() {
        Instant latest = NOW.plusSeconds(30);
        given(reportRepository.findSummaries(PAGE)).willReturn(new PageImpl<>(
                List.of(new CommentReportSummary(501L, 3L, latest), new CommentReportSummary(500L, 1L, NOW)),
                PAGE, 2));
        given(commentRepository.findAllById(List.of(501L, 500L)))
                .willReturn(List.of(comment(500L, 100L, "일반 댓글"), blocked(501L, 101L, "차단된 댓글")));
        given(postRepository.findAllById(any())).willReturn(List.of(
                post(100L, 1L, PostVisibility.PUBLIC), post(101L, 2L, PostVisibility.FOLLOWERS)));
        given(reportRepository.countReasons(List.of(501L, 500L))).willReturn(List.of(
                new CommentReportReasonCount(501L, CommentReportReason.ABUSE, 2L),
                new CommentReportReasonCount(501L, CommentReportReason.SPAM, 1L),
                new CommentReportReasonCount(500L, CommentReportReason.OTHER, 1L)));

        List<AdminCommentReportView> views = service.findReportedComments(1L, PAGE).getContent();

        verify(memberQueryService).validateAdmin(1L);
        assertThat(views).extracting(AdminCommentReportView::commentId).containsExactly(501L, 500L);
        AdminCommentReportView first = views.get(0);
        assertThat(first.postId()).isEqualTo(101L);
        assertThat(first.creatorId()).isEqualTo(2L);
        assertThat(first.authorMemberId()).isEqualTo(20L);
        assertThat(first.content()).isEqualTo("차단된 댓글");
        assertThat(first.blocked()).isTrue();
        assertThat(first.reportCount()).isEqualTo(3L);
        assertThat(first.reasonCounts()).isEqualTo(Map.of(CommentReportReason.ABUSE, 2L, CommentReportReason.SPAM, 1L));
        assertThat(first.latestReportedAt()).isEqualTo(latest);
        assertThat(views.get(1).blocked()).isFalse();
        assertThat(views.get(1).creatorId()).isEqualTo(1L);
    }

    @Test
    void 신고가_없으면_빈_페이지를_돌려주고_댓글을_조회하지_않는다() {
        given(reportRepository.findSummaries(PAGE)).willReturn(Page.empty(PAGE));

        assertThat(service.findReportedComments(1L, PAGE).getContent()).isEmpty();
        verify(commentRepository, never()).findAllById(any());
    }

    @Test
    void 관리자가_아니면_조회하지_않는다() {
        willThrow(new BusinessException(CommonErrorCode.FORBIDDEN)).given(memberQueryService).validateAdmin(7L);

        assertThatThrownBy(() -> service.findReportedComments(7L, PAGE))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));
        verify(reportRepository, never()).findSummaries(any());
    }

    private CreatorPostComment comment(Long commentId, Long postId, String content) {
        CreatorPostComment comment = new CreatorPostComment(postId, 20L, content, NOW);
        ReflectionTestUtils.setField(comment, "commentId", commentId);
        return comment;
    }

    private CreatorPostComment blocked(Long commentId, Long postId, String content) {
        CreatorPostComment comment = comment(commentId, postId, content);
        comment.markFiltered(CommentFilterAction.BLOCK, List.of("profanity:test"), "rule-1", "model-1", NOW);
        return comment;
    }
}

package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.post.application.dto.AdminCommentReportActionView;
import kr.co.cking.post.domain.CommentReportStatus;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.domain.PostVisibility;
import kr.co.cking.post.repository.CreatorPostCommentReportRepository;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import kr.co.cking.sanction.application.SanctionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static kr.co.cking.post.PostFixtures.NOW;
import static kr.co.cking.post.PostFixtures.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AdminCommentReportCommandServiceTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long COMMENT_ID = 500L;
    private static final Long POST_ID = 100L;
    private static final Long AUTHOR_ID = 20L;
    private static final Instant LATER = NOW.plusSeconds(60);

    private final MemberQueryService memberQueryService = mock(MemberQueryService.class);
    private final CreatorPostRepository postRepository = mock(CreatorPostRepository.class);
    private final CreatorPostCommentRepository commentRepository = mock(CreatorPostCommentRepository.class);
    private final CreatorPostCommentReportRepository reportRepository = mock(CreatorPostCommentReportRepository.class);
    private final SanctionService sanctionService = mock(SanctionService.class);
    private final AdminCommentReportCommandService service = new AdminCommentReportCommandService(
            memberQueryService, postRepository, commentRepository, reportRepository, sanctionService,
            Clock.fixed(LATER, ZoneOffset.UTC));

    private CreatorPostComment comment;

    @BeforeEach
    void setUp() {
        comment = new CreatorPostComment(POST_ID, AUTHOR_ID, "신고된 댓글", NOW);
        ReflectionTestUtils.setField(comment, "commentId", COMMENT_ID);
        given(commentRepository.findPostIdByCommentId(COMMENT_ID)).willReturn(Optional.of(POST_ID));
        given(commentRepository.findMemberIdByCommentId(COMMENT_ID)).willReturn(Optional.of(AUTHOR_ID));
        given(postRepository.findByIdForShare(POST_ID))
                .willReturn(Optional.of(post(POST_ID, 1L, PostVisibility.PUBLIC)));
        given(commentRepository.findByIdForUpdate(COMMENT_ID)).willReturn(Optional.of(comment));
        given(reportRepository.countByCommentId(COMMENT_ID)).willReturn(3L);
    }

    @Test
    void 숨기면_댓글을_숨김으로_표시하고_접수_신고를_모두_처리_완료로_바꾼다() {
        given(reportRepository.markHandled(COMMENT_ID, CommentReportStatus.RESOLVED, ADMIN_ID, LATER)).willReturn(3);

        AdminCommentReportActionView view = service.hide(ADMIN_ID, COMMENT_ID);

        assertThat(view.status()).isEqualTo(CommentReportStatus.RESOLVED);
        assertThat(view.handledReportCount()).isEqualTo(3);
        assertThat(comment.isHiddenByAdmin()).isTrue();
        assertThat(comment.getAdminHiddenAt()).isEqualTo(LATER);
        assertThat(comment.getAdminHiddenBy()).isEqualTo(ADMIN_ID);
        // 필터 판정은 건드리지 않는다.
        assertThat(comment.isBlocked()).isFalse();
        // 작성자에게 경고가 기록된다.
        verify(sanctionService).recordCommentHide(AUTHOR_ID, COMMENT_ID, ADMIN_ID, LATER);
    }

    @Test
    void 이미_숨긴_댓글을_다시_숨기면_아무것도_바꾸지_않고_성공한다() {
        comment.hideByAdmin(9L, NOW);

        AdminCommentReportActionView view = service.hide(ADMIN_ID, COMMENT_ID);

        assertThat(view.status()).isEqualTo(CommentReportStatus.RESOLVED);
        assertThat(view.handledReportCount()).isZero();
        assertThat(comment.getAdminHiddenBy()).isEqualTo(9L);
        assertThat(comment.getAdminHiddenAt()).isEqualTo(NOW);
        verify(reportRepository, never()).markHandled(anyLong(), any(), anyLong(), any());
        // 이미 숨긴 댓글을 다시 숨겨도 경고를 새로 쌓지 않는다.
        verify(sanctionService, never()).recordCommentHide(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void 접수_신고가_없고_숨기지도_않은_댓글은_숨길_수_없다() {
        given(reportRepository.markHandled(COMMENT_ID, CommentReportStatus.RESOLVED, ADMIN_ID, LATER)).willReturn(0);

        assertError(() -> service.hide(ADMIN_ID, COMMENT_ID), PostErrorCode.COMMENT_REPORT_ALREADY_HANDLED);
        assertThat(comment.isHiddenByAdmin()).isFalse();
        verify(sanctionService, never()).recordCommentHide(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void 반려하면_댓글은_그대로_두고_접수_신고만_반려로_바꾼다() {
        given(reportRepository.markHandled(COMMENT_ID, CommentReportStatus.REJECTED, ADMIN_ID, LATER)).willReturn(2);

        AdminCommentReportActionView view = service.reject(ADMIN_ID, COMMENT_ID);

        assertThat(view.status()).isEqualTo(CommentReportStatus.REJECTED);
        assertThat(view.handledReportCount()).isEqualTo(2);
        assertThat(comment.isHiddenByAdmin()).isFalse();
        // 반려는 경고를 쌓지 않고 작성자 행도 잠그지 않는다.
        verify(sanctionService, never()).lockSubject(anyLong());
        verify(sanctionService, never()).recordCommentHide(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void 이미_모두_반려한_댓글을_다시_반려하면_처리한_수_0으로_성공한다() {
        given(reportRepository.markHandled(COMMENT_ID, CommentReportStatus.REJECTED, ADMIN_ID, LATER)).willReturn(0);

        AdminCommentReportActionView view = service.reject(ADMIN_ID, COMMENT_ID);

        assertThat(view.status()).isEqualTo(CommentReportStatus.REJECTED);
        assertThat(view.handledReportCount()).isZero();
    }

    @Test
    void 이미_숨긴_댓글은_반려할_수_없다() {
        comment.hideByAdmin(9L, NOW);

        assertError(() -> service.reject(ADMIN_ID, COMMENT_ID), PostErrorCode.COMMENT_REPORT_ALREADY_HANDLED);
        verify(reportRepository, never()).markHandled(anyLong(), any(), anyLong(), any());
    }

    @Test
    void 신고가_하나도_없는_댓글과_없는_댓글은_404다() {
        given(reportRepository.countByCommentId(COMMENT_ID)).willReturn(0L);
        given(commentRepository.findPostIdByCommentId(999L)).willReturn(Optional.empty());

        assertError(() -> service.hide(ADMIN_ID, COMMENT_ID), CommonErrorCode.RESOURCE_NOT_FOUND);
        assertError(() -> service.reject(ADMIN_ID, COMMENT_ID), CommonErrorCode.RESOURCE_NOT_FOUND);
        assertError(() -> service.hide(ADMIN_ID, 999L), CommonErrorCode.RESOURCE_NOT_FOUND);
        verify(reportRepository, never()).markHandled(anyLong(), any(), anyLong(), any());
    }

    @Test
    void 게시글이_삭제돼_잠금_조회에서_사라졌으면_404다() {
        given(postRepository.findByIdForShare(POST_ID)).willReturn(Optional.empty());

        assertError(() -> service.hide(ADMIN_ID, COMMENT_ID), CommonErrorCode.RESOURCE_NOT_FOUND);
        verify(commentRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void 관리자가_아니면_아무것도_읽거나_바꾸지_않는다() {
        willThrow(new BusinessException(CommonErrorCode.FORBIDDEN)).given(memberQueryService).validateAdmin(7L);

        assertError(() -> service.hide(7L, COMMENT_ID), CommonErrorCode.FORBIDDEN);
        assertError(() -> service.reject(7L, COMMENT_ID), CommonErrorCode.FORBIDDEN);
        verify(commentRepository, never()).findPostIdByCommentId(anyLong());
        verify(commentRepository, never()).findMemberIdByCommentId(anyLong());
        verify(sanctionService, never()).lockSubject(anyLong());
        verify(reportRepository, never()).markHandled(anyLong(), any(), anyLong(), any());
    }

    @Test
    void 숨김의_잠금은_작성자_다음_게시글_공유_잠금_다음_댓글_쓰기_잠금_순서다() {
        given(reportRepository.markHandled(COMMENT_ID, CommentReportStatus.RESOLVED, ADMIN_ID, LATER)).willReturn(1);

        service.hide(ADMIN_ID, COMMENT_ID);

        InOrder order = inOrder(
                memberQueryService, commentRepository, sanctionService, postRepository, reportRepository);
        order.verify(memberQueryService).validateAdmin(ADMIN_ID);
        order.verify(commentRepository).findMemberIdByCommentId(COMMENT_ID);
        order.verify(sanctionService).lockSubject(AUTHOR_ID);
        order.verify(commentRepository).findPostIdByCommentId(COMMENT_ID);
        order.verify(postRepository).findByIdForShare(POST_ID);
        order.verify(commentRepository).findByIdForUpdate(COMMENT_ID);
        order.verify(reportRepository).markHandled(COMMENT_ID, CommentReportStatus.RESOLVED, ADMIN_ID, LATER);
        order.verify(sanctionService).recordCommentHide(AUTHOR_ID, COMMENT_ID, ADMIN_ID, LATER);
    }

    @Test
    void 반려의_잠금은_게시글_공유_잠금_다음_댓글_쓰기_잠금_순서다() {
        given(reportRepository.markHandled(COMMENT_ID, CommentReportStatus.REJECTED, ADMIN_ID, LATER)).willReturn(1);

        service.reject(ADMIN_ID, COMMENT_ID);

        InOrder order = inOrder(memberQueryService, commentRepository, postRepository, reportRepository);
        order.verify(memberQueryService).validateAdmin(ADMIN_ID);
        order.verify(commentRepository).findPostIdByCommentId(COMMENT_ID);
        order.verify(postRepository).findByIdForShare(POST_ID);
        order.verify(commentRepository).findByIdForUpdate(COMMENT_ID);
        order.verify(reportRepository).markHandled(COMMENT_ID, CommentReportStatus.REJECTED, ADMIN_ID, LATER);
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, ErrorCode expected) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}

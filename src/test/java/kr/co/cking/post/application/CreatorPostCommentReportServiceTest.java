package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.dto.CommentReportView;
import kr.co.cking.post.domain.CommentReportReason;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.domain.CreatorPostCommentReport;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.domain.PostVisibility;
import kr.co.cking.post.repository.CreatorPostCommentReportRepository;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static kr.co.cking.post.PostFixtures.NOW;
import static kr.co.cking.post.PostFixtures.creator;
import static kr.co.cking.post.PostFixtures.member;
import static kr.co.cking.post.PostFixtures.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CreatorPostCommentReportServiceTest {

    private static final Long CREATOR_ID = 1L;
    private static final Long OWNER_MEMBER_ID = 10L;
    private static final Long AUTHOR_MEMBER_ID = 20L;
    private static final Long REPORTER_MEMBER_ID = 30L;
    private static final Long PUBLIC_POST_ID = 100L;
    private static final Long FOLLOWERS_POST_ID = 101L;
    private static final Long COMMENT_ID = 500L;
    private static final Instant LATER = NOW.plusSeconds(60);

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorPostRepository postRepository = mock(CreatorPostRepository.class);
    private final CreatorFollowQueryService followQueryService = mock(CreatorFollowQueryService.class);
    private final CreatorPostCommentRepository commentRepository = mock(CreatorPostCommentRepository.class);
    private final CreatorPostCommentReportRepository reportRepository = mock(CreatorPostCommentReportRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorPostCommentReportService service = new CreatorPostCommentReportService(
            new PostAccessPolicy(creatorRepository, postRepository, followQueryService),
            commentRepository, reportRepository, memberRepository, Clock.fixed(LATER, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.of(creator(CREATOR_ID, OWNER_MEMBER_ID)));
        for (Long postId : java.util.List.of(PUBLIC_POST_ID, FOLLOWERS_POST_ID)) {
            PostVisibility visibility = postId.equals(PUBLIC_POST_ID) ? PostVisibility.PUBLIC : PostVisibility.FOLLOWERS;
            given(postRepository.findByIdForShare(postId))
                    .willReturn(Optional.of(post(postId, CREATOR_ID, visibility)));
        }
        given(memberRepository.findByIdForUpdate(REPORTER_MEMBER_ID))
                .willReturn(Optional.of(member(REPORTER_MEMBER_ID)));
        given(commentRepository.findByIdForUpdate(COMMENT_ID))
                .willReturn(Optional.of(comment(PUBLIC_POST_ID, AUTHOR_MEMBER_ID)));
        given(reportRepository.save(any(CreatorPostCommentReport.class))).willAnswer(invocation -> {
            CreatorPostCommentReport saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "reportId", 900L);
            return saved;
        });
    }

    @Test
    void 신고자_행을_먼저_잠가_같은_사용자의_동시_신고를_직렬화한다() {
        service.report(REPORTER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, COMMENT_ID, CommentReportReason.ABUSE, null);

        InOrder order = inOrder(memberRepository, postRepository, commentRepository, reportRepository);
        order.verify(memberRepository).findByIdForUpdate(REPORTER_MEMBER_ID);
        order.verify(postRepository).findByIdForShare(PUBLIC_POST_ID);
        order.verify(commentRepository).findByIdForUpdate(COMMENT_ID);
        order.verify(reportRepository).countByReporterMemberIdAndCreatedAtGreaterThanEqual(
                REPORTER_MEMBER_ID, LATER.minus(CreatorPostCommentReportService.REPORT_LIMIT_WINDOW));
        order.verify(reportRepository).save(any(CreatorPostCommentReport.class));
    }

    @Test
    void 팔로우하지_않아도_전체_공개_게시글의_댓글을_신고할_수_있다() {
        CommentReportView view = service.report(
                REPORTER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, COMMENT_ID, CommentReportReason.ABUSE, null);

        assertThat(view.reportId()).isEqualTo(900L);
        assertThat(view.commentId()).isEqualTo(COMMENT_ID);
        assertThat(view.reason()).isEqualTo(CommentReportReason.ABUSE);
        assertThat(view.createdAt()).isEqualTo(LATER);
        ArgumentCaptor<CreatorPostCommentReport> saved = ArgumentCaptor.forClass(CreatorPostCommentReport.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getReporterMemberId()).isEqualTo(REPORTER_MEMBER_ID);
        assertThat(saved.getValue().getDetail()).isNull();
    }

    @Test
    void 기타_사유는_설명을_다듬어_저장한다() {
        service.report(REPORTER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, COMMENT_ID,
                CommentReportReason.OTHER, "  위협으로 느껴집니다  ");

        ArgumentCaptor<CreatorPostCommentReport> saved = ArgumentCaptor.forClass(CreatorPostCommentReport.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getDetail()).isEqualTo("위협으로 느껴집니다");
    }

    @Test
    void 기타_사유는_설명이_없거나_200자를_넘으면_거부한다() {
        assertError(() -> service.report(REPORTER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, COMMENT_ID,
                CommentReportReason.OTHER, null), CommonErrorCode.VALIDATION_FAILED);
        assertError(() -> service.report(REPORTER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, COMMENT_ID,
                CommentReportReason.OTHER, "   "), CommonErrorCode.VALIDATION_FAILED);
        assertError(() -> service.report(REPORTER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, COMMENT_ID,
                CommentReportReason.OTHER, "a".repeat(201)), CommonErrorCode.VALIDATION_FAILED);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void 기타가_아닌_사유에_설명이_오면_거부한다() {
        assertError(() -> service.report(REPORTER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, COMMENT_ID,
                CommentReportReason.SPAM, "설명"), CommonErrorCode.VALIDATION_FAILED);
        assertError(() -> service.report(REPORTER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, COMMENT_ID,
                null, null), CommonErrorCode.VALIDATION_FAILED);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void 이미_신고한_댓글을_다시_신고하면_기존_신고를_돌려주고_새로_저장하지_않는다() {
        CreatorPostCommentReport existing = new CreatorPostCommentReport(
                COMMENT_ID, REPORTER_MEMBER_ID, CommentReportReason.SPAM, null, NOW);
        ReflectionTestUtils.setField(existing, "reportId", 777L);
        given(reportRepository.findByCommentIdAndReporterMemberId(COMMENT_ID, REPORTER_MEMBER_ID))
                .willReturn(Optional.of(existing));

        CommentReportView view = service.report(
                REPORTER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, COMMENT_ID, CommentReportReason.ABUSE, null);

        assertThat(view.reportId()).isEqualTo(777L);
        assertThat(view.reason()).isEqualTo(CommentReportReason.SPAM);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void 본인_댓글은_신고할_수_없다() {
        given(commentRepository.findByIdForUpdate(COMMENT_ID))
                .willReturn(Optional.of(comment(PUBLIC_POST_ID, REPORTER_MEMBER_ID)));

        assertError(() -> service.report(REPORTER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, COMMENT_ID,
                CommentReportReason.ABUSE, null), PostErrorCode.COMMENT_REPORT_OWN_COMMENT);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void 볼_수_없는_팔로워_공개_게시글의_댓글은_신고할_수_없다() {
        assertError(() -> service.report(REPORTER_MEMBER_ID, CREATOR_ID, FOLLOWERS_POST_ID, COMMENT_ID,
                CommentReportReason.ABUSE, null), PostErrorCode.POST_FOLLOWERS_ONLY);
        verify(commentRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void 없는_댓글이나_다른_게시글의_댓글은_404다() {
        given(commentRepository.findByIdForUpdate(999L)).willReturn(Optional.empty());

        assertError(() -> service.report(REPORTER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 999L,
                CommentReportReason.ABUSE, null), CommonErrorCode.RESOURCE_NOT_FOUND);
        given(commentRepository.findByIdForUpdate(COMMENT_ID))
                .willReturn(Optional.of(comment(FOLLOWERS_POST_ID, AUTHOR_MEMBER_ID)));
        assertError(() -> service.report(REPORTER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, COMMENT_ID,
                CommentReportReason.ABUSE, null), CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 없는_회원이나_Creator는_404다() {
        assertError(() -> service.report(999L, CREATOR_ID, PUBLIC_POST_ID, COMMENT_ID,
                CommentReportReason.ABUSE, null), CommonErrorCode.RESOURCE_NOT_FOUND);
        assertError(() -> service.report(REPORTER_MEMBER_ID, 999L, PUBLIC_POST_ID, COMMENT_ID,
                CommentReportReason.ABUSE, null), CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 제한_시간_안에_신고_수가_한도에_닿으면_새_신고를_거부한다() {
        given(reportRepository.countByReporterMemberIdAndCreatedAtGreaterThanEqual(
                REPORTER_MEMBER_ID, LATER.minus(CreatorPostCommentReportService.REPORT_LIMIT_WINDOW)))
                .willReturn((long) CreatorPostCommentReportService.REPORT_LIMIT);

        assertError(() -> service.report(REPORTER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, COMMENT_ID,
                CommentReportReason.ABUSE, null), PostErrorCode.COMMENT_REPORT_LIMIT_EXCEEDED);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void 한도에_닿아도_이미_신고한_댓글은_기존_신고를_돌려준다() {
        CreatorPostCommentReport existing = new CreatorPostCommentReport(
                COMMENT_ID, REPORTER_MEMBER_ID, CommentReportReason.ABUSE, null, NOW);
        ReflectionTestUtils.setField(existing, "reportId", 777L);
        given(reportRepository.findByCommentIdAndReporterMemberId(COMMENT_ID, REPORTER_MEMBER_ID))
                .willReturn(Optional.of(existing));
        given(reportRepository.countByReporterMemberIdAndCreatedAtGreaterThanEqual(anyLong(), any()))
                .willReturn((long) CreatorPostCommentReportService.REPORT_LIMIT);

        assertThat(service.report(REPORTER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, COMMENT_ID,
                CommentReportReason.ABUSE, null).reportId()).isEqualTo(777L);
    }

    private CreatorPostComment comment(Long postId, Long authorMemberId) {
        CreatorPostComment comment = new CreatorPostComment(postId, authorMemberId, "댓글", NOW);
        ReflectionTestUtils.setField(comment, "commentId", COMMENT_ID);
        return comment;
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, ErrorCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}

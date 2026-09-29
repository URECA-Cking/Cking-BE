package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.dto.CreatorPostCommentView;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.domain.PostVisibility;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static kr.co.cking.post.PostFixtures.NOW;
import static kr.co.cking.post.PostFixtures.creator;
import static kr.co.cking.post.PostFixtures.member;
import static kr.co.cking.post.PostFixtures.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CreatorPostCommentServiceTest {

    private static final Long CREATOR_ID = 1L;
    private static final Long OWNER_MEMBER_ID = 10L;
    private static final Long FAN_MEMBER_ID = 20L;
    private static final Long STRANGER_MEMBER_ID = 30L;
    private static final Long PUBLIC_POST_ID = 100L;
    private static final Long FOLLOWERS_POST_ID = 101L;
    private static final Instant LATER = NOW.plusSeconds(60);
    private static final PageRequest PAGE = PageRequest.of(0, 20);

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorPostRepository postRepository = mock(CreatorPostRepository.class);
    private final CreatorFollowQueryService followQueryService = mock(CreatorFollowQueryService.class);
    private final CreatorPostCommentRepository commentRepository = mock(CreatorPostCommentRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorPostCommentService service = new CreatorPostCommentService(
            new PostAccessPolicy(creatorRepository, postRepository, followQueryService),
            commentRepository, memberRepository, Clock.fixed(LATER, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.of(creator(CREATOR_ID, OWNER_MEMBER_ID)));
        for (Long postId : List.of(PUBLIC_POST_ID, FOLLOWERS_POST_ID)) {
            PostVisibility visibility = postId.equals(PUBLIC_POST_ID) ? PostVisibility.PUBLIC : PostVisibility.FOLLOWERS;
            given(postRepository.findById(postId)).willReturn(Optional.of(post(postId, CREATOR_ID, visibility)));
            given(postRepository.findByIdForShare(postId)).willReturn(Optional.of(post(postId, CREATOR_ID, visibility)));
        }
        given(followQueryService.isFollowing(FAN_MEMBER_ID, CREATOR_ID)).willReturn(true);
        for (Long memberId : List.of(OWNER_MEMBER_ID, FAN_MEMBER_ID, STRANGER_MEMBER_ID)) {
            given(memberRepository.findById(memberId)).willReturn(Optional.of(member(memberId)));
        }
        given(commentRepository.save(any(CreatorPostComment.class))).willAnswer(invocation -> {
            CreatorPostComment saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "commentId", 500L);
            return saved;
        });
    }

    @Test
    void 비로그인도_전체_공개_게시글의_댓글을_작성_순으로_보고_Creator_댓글을_구분한다() {
        given(commentRepository.findByPostIdOldestFirst(PUBLIC_POST_ID, PAGE)).willReturn(new PageImpl<>(List.of(
                comment(1L, PUBLIC_POST_ID, FAN_MEMBER_ID), comment(2L, PUBLIC_POST_ID, OWNER_MEMBER_ID)), PAGE, 2));
        given(memberRepository.findAllById(Set.of(FAN_MEMBER_ID, OWNER_MEMBER_ID)))
                .willReturn(List.of(member(FAN_MEMBER_ID), member(OWNER_MEMBER_ID)));

        List<CreatorPostCommentView> comments =
                service.findByPost(CREATOR_ID, PUBLIC_POST_ID, null, PAGE).getContent();

        assertThat(comments).extracting(CreatorPostCommentView::commentId).containsExactly(1L, 2L);
        assertThat(comments).extracting(CreatorPostCommentView::authorName)
                .containsExactly("member-" + FAN_MEMBER_ID, "member-" + OWNER_MEMBER_ID);
        assertThat(comments).extracting(CreatorPostCommentView::writtenByCreator).containsExactly(false, true);
    }

    @Test
    void 볼_수_없는_팔로워_공개_게시글의_댓글은_게시글과_같이_POST_FOLLOWERS_ONLY다() {
        assertError(() -> service.findByPost(CREATOR_ID, FOLLOWERS_POST_ID, null, PAGE),
                PostErrorCode.POST_FOLLOWERS_ONLY);
        assertError(() -> service.findByPost(CREATOR_ID, FOLLOWERS_POST_ID, STRANGER_MEMBER_ID, PAGE),
                PostErrorCode.POST_FOLLOWERS_ONLY);
        verify(commentRepository, never()).findByPostIdOldestFirst(any(), any());
    }

    @Test
    void 팔로워는_댓글을_작성한다() {
        CreatorPostCommentView created = service.create(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, "응원해요");

        assertThat(created.commentId()).isEqualTo(500L);
        assertThat(created.authorMemberId()).isEqualTo(FAN_MEMBER_ID);
        assertThat(created.authorName()).isEqualTo("member-" + FAN_MEMBER_ID);
        assertThat(created.writtenByCreator()).isFalse();
        assertThat(created.content()).isEqualTo("응원해요");
        assertThat(created.createdAt()).isEqualTo(LATER);
    }

    @Test
    void 댓글_작성_수정_삭제는_게시글을_공유_잠금으로_읽고_목록은_잠그지_않는다() {
        given(commentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(comment(1L, PUBLIC_POST_ID, FAN_MEMBER_ID)));
        given(commentRepository.findByPostIdOldestFirst(PUBLIC_POST_ID, PAGE))
                .willReturn(new PageImpl<>(List.of(), PAGE, 0));

        service.create(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, "응원해요");
        service.update(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L, "수정");
        service.delete(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L);
        service.findByPost(CREATOR_ID, PUBLIC_POST_ID, null, PAGE);

        verify(postRepository, times(3)).findByIdForShare(PUBLIC_POST_ID);
        verify(postRepository, times(1)).findById(PUBLIC_POST_ID);
        verify(commentRepository, times(2)).findByIdForUpdate(1L);
        verify(commentRepository, never()).findById(any());
    }

    @Test
    void 댓글_수정은_게시글_공유_잠금을_먼저_잡고_댓글_쓰기_잠금을_잡는다() {
        given(commentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(comment(1L, PUBLIC_POST_ID, FAN_MEMBER_ID)));

        service.update(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L, "수정");

        InOrder order = inOrder(postRepository, commentRepository);
        order.verify(postRepository).findByIdForShare(PUBLIC_POST_ID);
        order.verify(commentRepository).findByIdForUpdate(1L);
    }

    @Test
    void 먼저_삭제돼_잠금_조회에서_사라진_댓글의_수정_삭제는_404다() {
        given(commentRepository.findByIdForUpdate(1L)).willReturn(Optional.empty());

        assertError(() -> service.update(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L, "수정"),
                CommonErrorCode.RESOURCE_NOT_FOUND);
        assertError(() -> service.delete(OWNER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L),
                CommonErrorCode.RESOURCE_NOT_FOUND);
        verify(commentRepository, never()).delete(any());
    }

    @Test
    void 게시글이_삭제돼_잠금_조회에서_사라졌으면_댓글_작성은_404다() {
        given(postRepository.findByIdForShare(PUBLIC_POST_ID)).willReturn(Optional.empty());

        assertError(() -> service.create(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, "응원해요"),
                CommonErrorCode.RESOURCE_NOT_FOUND);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void 게시글을_작성한_Creator는_팔로우하지_않아도_댓글을_작성한다() {
        CreatorPostCommentView created =
                service.create(OWNER_MEMBER_ID, CREATOR_ID, FOLLOWERS_POST_ID, "고마워요");

        assertThat(created.writtenByCreator()).isTrue();
        verify(followQueryService, never()).isFollowing(OWNER_MEMBER_ID, CREATOR_ID);
    }

    @Test
    void 전체_공개_게시글도_팔로우하지_않으면_댓글을_작성할_수_없다() {
        assertError(() -> service.create(STRANGER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, "안녕하세요"),
                PostErrorCode.COMMENT_FOLLOWERS_ONLY);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void 볼_수_없는_팔로워_공개_게시글에는_게시글_오류로_거부한다() {
        assertError(() -> service.create(STRANGER_MEMBER_ID, CREATOR_ID, FOLLOWERS_POST_ID, "안녕하세요"),
                PostErrorCode.POST_FOLLOWERS_ONLY);
    }

    @Test
    void 빈_댓글과_500자를_넘는_댓글은_거부한다() {
        assertError(() -> service.create(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, "  "),
                CommonErrorCode.VALIDATION_FAILED);
        assertError(() -> service.create(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, "a".repeat(501)),
                CommonErrorCode.VALIDATION_FAILED);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void 다른_크리에이터의_게시글_ID로는_댓글을_작성할_수_없다() {
        given(postRepository.findByIdForShare(200L)).willReturn(Optional.of(post(200L, 2L, PostVisibility.PUBLIC)));

        assertError(() -> service.create(FAN_MEMBER_ID, CREATOR_ID, 200L, "안녕하세요"),
                CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 작성자는_본인_댓글을_수정한다() {
        CreatorPostComment comment = comment(1L, PUBLIC_POST_ID, FAN_MEMBER_ID);
        given(commentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(comment));

        CreatorPostCommentView updated = service.update(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L, "수정");

        assertThat(updated.content()).isEqualTo("수정");
        assertThat(updated.updatedAt()).isEqualTo(LATER);
        assertThat(comment.getContent()).isEqualTo("수정");
    }

    @Test
    void 다른_사람의_댓글은_Creator도_수정할_수_없다() {
        given(commentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(comment(1L, PUBLIC_POST_ID, FAN_MEMBER_ID)));

        assertError(() -> service.update(OWNER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L, "수정"),
                CommonErrorCode.FORBIDDEN);
    }

    @Test
    void 팔로우를_끊은_작성자는_댓글을_수정할_수_없다() {
        given(commentRepository.findByIdForUpdate(1L))
                .willReturn(Optional.of(comment(1L, PUBLIC_POST_ID, STRANGER_MEMBER_ID)));

        assertError(() -> service.update(STRANGER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L, "수정"),
                PostErrorCode.COMMENT_FOLLOWERS_ONLY);
    }

    @Test
    void 다른_게시글의_댓글_ID는_없는_댓글이다() {
        given(commentRepository.findByIdForUpdate(1L))
                .willReturn(Optional.of(comment(1L, FOLLOWERS_POST_ID, FAN_MEMBER_ID)));

        assertError(() -> service.update(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L, "수정"),
                CommonErrorCode.RESOURCE_NOT_FOUND);
        assertError(() -> service.delete(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L),
                CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 팔로우를_끊은_작성자도_팔로워_공개_게시글의_본인_댓글을_삭제한다() {
        CreatorPostComment comment = comment(1L, FOLLOWERS_POST_ID, STRANGER_MEMBER_ID);
        given(commentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(comment));

        service.delete(STRANGER_MEMBER_ID, CREATOR_ID, FOLLOWERS_POST_ID, 1L);

        verify(commentRepository).delete(comment);
    }

    @Test
    void 게시글을_작성한_Creator는_다른_사람의_댓글을_삭제한다() {
        CreatorPostComment comment = comment(1L, PUBLIC_POST_ID, FAN_MEMBER_ID);
        given(commentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(comment));

        service.delete(OWNER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L);

        verify(commentRepository).delete(comment);
    }

    @Test
    void 작성자도_게시글_Creator도_아니면_댓글을_삭제할_수_없다() {
        given(commentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(comment(1L, PUBLIC_POST_ID, FAN_MEMBER_ID)));

        assertError(() -> service.delete(STRANGER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L),
                CommonErrorCode.FORBIDDEN);
        verify(commentRepository, never()).delete(any());
    }

    private CreatorPostComment comment(Long commentId, Long postId, Long memberId) {
        CreatorPostComment comment = new CreatorPostComment(postId, memberId, "댓글 " + commentId, NOW);
        ReflectionTestUtils.setField(comment, "commentId", commentId);
        return comment;
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, ErrorCode expected) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}

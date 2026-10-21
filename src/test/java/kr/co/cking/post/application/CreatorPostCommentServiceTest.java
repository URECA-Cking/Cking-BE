package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.creator.application.CreatorPublicProfileQueryService;
import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.creator.application.dto.CreatorPublicProfile;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import kr.co.cking.member.application.ProfileImageUrlResolver;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.dto.CreatorPostCommentView;
import kr.co.cking.post.domain.CommentFilterAction;
import kr.co.cking.post.domain.CommentFilterStatus;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.domain.PostVisibility;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import kr.co.cking.sanction.application.SanctionQueryService;
import kr.co.cking.sanction.domain.SanctionErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
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
import static org.mockito.BDDMockito.willThrow;
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
    private final CreatorPublicProfileQueryService creatorPublicProfileQueryService = mock(CreatorPublicProfileQueryService.class);
    private final CreatorQueryService creatorQueryService = mock(CreatorQueryService.class);
    private final ProfileImageUrlResolver profileImageUrlResolver = mock(ProfileImageUrlResolver.class);
    private final SanctionQueryService sanctionQueryService = mock(SanctionQueryService.class);
    private final CreatorPostCommentService service = new CreatorPostCommentService(
            new PostAccessPolicy(creatorRepository, postRepository, followQueryService),
            commentRepository, memberRepository, creatorPublicProfileQueryService, creatorQueryService, profileImageUrlResolver,
            Clock.fixed(LATER, ZoneOffset.UTC), mock(ApplicationEventPublisher.class), sanctionQueryService);

    @BeforeEach
    void setUp() {
        given(creatorPublicProfileQueryService.findByMemberIds(any())).willReturn(java.util.Map.of());
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.of(creator(CREATOR_ID, OWNER_MEMBER_ID)));
        for (Long postId : List.of(PUBLIC_POST_ID, FOLLOWERS_POST_ID)) {
            PostVisibility visibility = postId.equals(PUBLIC_POST_ID) ? PostVisibility.PUBLIC : PostVisibility.FOLLOWERS;
            given(postRepository.findById(postId)).willReturn(Optional.of(post(postId, CREATOR_ID, visibility)));
            given(postRepository.findByIdForShare(postId)).willReturn(Optional.of(post(postId, CREATOR_ID, visibility)));
        }
        given(followQueryService.isFollowing(FAN_MEMBER_ID, CREATOR_ID)).willReturn(true);
        for (Long memberId : List.of(OWNER_MEMBER_ID, FAN_MEMBER_ID, STRANGER_MEMBER_ID)) {
            given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(member(memberId)));
        }
        given(commentRepository.save(any(CreatorPostComment.class))).willAnswer(invocation -> {
            CreatorPostComment saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "commentId", 500L);
            return saved;
        });
    }

    @Test
    void 비로그인도_전체_공개_게시글의_댓글을_작성_순으로_보고_Creator_댓글을_구분한다() {
        given(commentRepository.findVisibleByPostIdOldestFirst(PUBLIC_POST_ID, null, PAGE)).willReturn(new PageImpl<>(List.of(
                comment(1L, PUBLIC_POST_ID, FAN_MEMBER_ID), comment(2L, PUBLIC_POST_ID, OWNER_MEMBER_ID)), PAGE, 2));
        given(memberRepository.findAllById(Set.of(FAN_MEMBER_ID, OWNER_MEMBER_ID)))
                .willReturn(List.of(member(FAN_MEMBER_ID), member(OWNER_MEMBER_ID)));
        given(creatorPublicProfileQueryService.findByMemberIds(Set.of(FAN_MEMBER_ID, OWNER_MEMBER_ID)))
                .willReturn(java.util.Map.of(OWNER_MEMBER_ID,
                        creatorProfile(OWNER_MEMBER_ID, CREATOR_ID, "creator-" + CREATOR_ID, null)));

        List<CreatorPostCommentView> comments =
                service.findByPost(CREATOR_ID, PUBLIC_POST_ID, null, PAGE).getContent();

        assertThat(comments).extracting(CreatorPostCommentView::commentId).containsExactly(1L, 2L);
        assertThat(comments).extracting(CreatorPostCommentView::authorName)
                .containsExactly("member-" + FAN_MEMBER_ID, "creator-" + CREATOR_ID);
        assertThat(comments).extracting(CreatorPostCommentView::writtenByCreator).containsExactly(false, true);
    }

    @Test
    void 볼_수_없는_팔로워_공개_게시글의_댓글은_게시글과_같이_POST_FOLLOWERS_ONLY다() {
        assertError(() -> service.findByPost(CREATOR_ID, FOLLOWERS_POST_ID, null, PAGE),
                PostErrorCode.POST_FOLLOWERS_ONLY);
        assertError(() -> service.findByPost(CREATOR_ID, FOLLOWERS_POST_ID, STRANGER_MEMBER_ID, PAGE),
                PostErrorCode.POST_FOLLOWERS_ONLY);
        verify(commentRepository, never()).findByPostIdOldestFirst(any(), any());
        verify(commentRepository, never()).findVisibleByPostIdOldestFirst(any(), any(), any());
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
    void 댓글_작성_제한_중인_회원은_작성과_수정이_COMMENT_RESTRICTED로_막히고_삭제는_할_수_있다() {
        willThrow(new BusinessException(SanctionErrorCode.COMMENT_RESTRICTED))
                .given(sanctionQueryService).requireCommentWriteAllowed(FAN_MEMBER_ID, CREATOR_ID);
        given(commentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(comment(1L, PUBLIC_POST_ID, FAN_MEMBER_ID)));

        assertError(() -> service.create(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, "응원해요"),
                SanctionErrorCode.COMMENT_RESTRICTED);
        assertError(() -> service.update(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L, "수정"),
                SanctionErrorCode.COMMENT_RESTRICTED);
        verify(commentRepository, never()).save(any(CreatorPostComment.class));

        service.delete(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L);

        verify(commentRepository).delete(any(CreatorPostComment.class));
    }

    @Test
    void 작성과_수정은_작성자_행을_잠근_다음_제한을_확인하고_그_다음_게시글_댓글_순으로_읽는다() {
        given(commentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(comment(1L, PUBLIC_POST_ID, FAN_MEMBER_ID)));

        service.create(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, "응원해요");
        service.update(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L, "수정");

        InOrder order = inOrder(memberRepository, sanctionQueryService, postRepository, commentRepository);
        order.verify(memberRepository).findByIdForUpdate(FAN_MEMBER_ID);
        order.verify(sanctionQueryService).requireCommentWriteAllowed(FAN_MEMBER_ID, CREATOR_ID);
        order.verify(postRepository).findByIdForShare(PUBLIC_POST_ID);
        order.verify(commentRepository).save(any(CreatorPostComment.class));
        order.verify(memberRepository).findByIdForUpdate(FAN_MEMBER_ID);
        order.verify(sanctionQueryService).requireCommentWriteAllowed(FAN_MEMBER_ID, CREATOR_ID);
        order.verify(postRepository).findByIdForShare(PUBLIC_POST_ID);
        order.verify(commentRepository).findByIdForUpdate(1L);
    }

    @Test
    void 삭제는_작성자_행을_잠그지도_제한을_확인하지도_않는다() {
        given(commentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(comment(1L, PUBLIC_POST_ID, FAN_MEMBER_ID)));

        service.delete(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L);

        verify(memberRepository, never()).findByIdForUpdate(any());
        verify(sanctionQueryService, never()).requireCommentWriteAllowed(any(), any());
    }

    @Test
    void 제한은_다른_회원의_작성을_막지_않는다() {
        willThrow(new BusinessException(SanctionErrorCode.COMMENT_RESTRICTED))
                .given(sanctionQueryService).requireCommentWriteAllowed(STRANGER_MEMBER_ID, CREATOR_ID);

        CreatorPostCommentView created = service.create(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, "응원해요");

        assertThat(created.commentId()).isEqualTo(500L);
    }

    @Test
    void 댓글_작성_수정_삭제는_게시글을_공유_잠금으로_읽고_목록은_잠그지_않는다() {
        given(commentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(comment(1L, PUBLIC_POST_ID, FAN_MEMBER_ID)));
        given(commentRepository.findVisibleByPostIdOldestFirst(PUBLIC_POST_ID, null, PAGE))
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
    void 활동명이_설정된_Creator는_Member_닉네임이_없어도_활동명_프로필로_댓글을_작성한다() {
        Member creatorMember = new Member("OAuth 이름", null, null, kr.co.cking.member.domain.MemberRole.USER);
        ReflectionTestUtils.setField(creatorMember, "memberId", OWNER_MEMBER_ID);
        given(memberRepository.findByIdForUpdate(OWNER_MEMBER_ID)).willReturn(Optional.of(creatorMember));
        given(creatorQueryService.requireActivityNameConfiguredForCreator(OWNER_MEMBER_ID)).willReturn(true);
        given(creatorPublicProfileQueryService.findByMemberIds(Set.of(OWNER_MEMBER_ID)))
                .willReturn(java.util.Map.of(OWNER_MEMBER_ID,
                        creatorProfile(OWNER_MEMBER_ID, CREATOR_ID, "공개활동명", "https://cdn.example.com/space/1.png")));

        CreatorPostCommentView created =
                service.create(OWNER_MEMBER_ID, CREATOR_ID, FOLLOWERS_POST_ID, "고마워요");

        assertThat(created.authorName()).isEqualTo("공개활동명");
        assertThat(created.authorProfileImageUrl()).isEqualTo("https://cdn.example.com/space/1.png");
        assertThat(created.writtenByCreator()).isTrue();
    }

    @Test
    void 활동명_미설정_Creator의_댓글_작성은_차단한다() {
        Member creatorMember = new Member("OAuth 이름", null, null, kr.co.cking.member.domain.MemberRole.USER);
        ReflectionTestUtils.setField(creatorMember, "memberId", OWNER_MEMBER_ID);
        given(memberRepository.findByIdForUpdate(OWNER_MEMBER_ID)).willReturn(Optional.of(creatorMember));
        willThrow(new BusinessException(kr.co.cking.creator.domain.CreatorErrorCode.ACTIVITY_NAME_SETUP_REQUIRED))
                .given(creatorQueryService).requireActivityNameConfiguredForCreator(OWNER_MEMBER_ID);

        assertError(() -> service.create(OWNER_MEMBER_ID, CREATOR_ID, FOLLOWERS_POST_ID, "고마워요"),
                kr.co.cking.creator.domain.CreatorErrorCode.ACTIVITY_NAME_SETUP_REQUIRED);
        verify(commentRepository, never()).save(any());
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

    @Test
    void BLOCK된_댓글은_스페이스_주인에게_원문과_판정을_빼고_filtered로_내려준다() {
        stubCommentPage(blocked(1L, FAN_MEMBER_ID, "profanity:병신"), passed(2L, FAN_MEMBER_ID));

        List<CreatorPostCommentView> views = service.findByPost(CREATOR_ID, PUBLIC_POST_ID, OWNER_MEMBER_ID, PAGE).getContent();

        assertThat(views.get(0).filtered()).isTrue();
        assertThat(views.get(0).revealable()).isTrue();
        assertThat(views.get(0).content()).isNull();
        assertThat(views.get(1).filtered()).isFalse();
        assertThat(views.get(1).content()).isEqualTo("댓글 2");
    }

    @Test
    void BLOCK된_댓글을_수정하면_재판정_중에도_스페이스_주인에게_가려진_채_미판정으로_돌아간다() {
        CreatorPostComment comment = blocked(1L, FAN_MEMBER_ID, "profanity:병신");
        given(commentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(comment));

        service.update(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 1L, "수정");

        assertThat(comment.getFilterStatus()).isEqualTo(CommentFilterStatus.PENDING);
        stubCommentPage(comment);
        assertThat(service.findByPost(CREATOR_ID, PUBLIC_POST_ID, OWNER_MEMBER_ID, PAGE).getContent().get(0).filtered())
                .isTrue();
    }

    @Test
    void 재판정이_PASS면_수정된_BLOCK_댓글이_다시_보이고_BLOCK이면_계속_가려진다() {
        CreatorPostComment comment = blocked(1L, FAN_MEMBER_ID, "profanity:병신");
        comment.update("고침", LATER);

        comment.markFiltered(CommentFilterAction.PASS, List.of(), "rule-2", "model-2", LATER);
        assertThat(comment.isBlocked()).isFalse();
        assertThat(comment.getFilterStatus()).isEqualTo(CommentFilterStatus.DONE);

        comment.update("또 고침", LATER);
        comment.markFiltered(CommentFilterAction.BLOCK, List.of("spam:link"), "rule-2", "model-2", LATER);
        assertThat(comment.isBlocked()).isTrue();
    }

    @Test
    void 수정한_BLOCK_댓글의_재판정이_실패해도_이전_BLOCK을_유지한다() {
        CreatorPostComment comment = blocked(1L, FAN_MEMBER_ID, "profanity:병신");
        comment.update("고침", LATER);

        comment.markFilterFailed();

        assertThat(comment.getFilterStatus()).isEqualTo(CommentFilterStatus.FAILED);
        assertThat(comment.isBlocked()).isTrue();
    }

    @Test
    void 판정받은_적_없는_댓글이나_PASS_댓글을_수정해도_가려지지_않는다() {
        CreatorPostComment fresh = comment(1L, PUBLIC_POST_ID, FAN_MEMBER_ID);
        CreatorPostComment pass = passed(2L, FAN_MEMBER_ID);

        fresh.update("수정", LATER);
        pass.update("수정", LATER);

        assertThat(fresh.isBlocked()).isFalse();
        assertThat(pass.isBlocked()).isFalse();
        assertThat(pass.getFilterStatus()).isEqualTo(CommentFilterStatus.PENDING);
    }

    @Test
    void 가려진_댓글은_스페이스_주인만_전체_조회로_받고_다른_조회자는_가려진_댓글을_뺀_조회로_받는다() {
        stubCommentPage(blocked(1L, FAN_MEMBER_ID, "spam:link"));
        stubVisibleCommentPage(null);
        stubVisibleCommentPage(STRANGER_MEMBER_ID);

        assertThat(service.findByPost(CREATOR_ID, PUBLIC_POST_ID, OWNER_MEMBER_ID, PAGE).getContent())
                .extracting(CreatorPostCommentView::filtered).containsExactly(true);
        assertThat(service.findByPost(CREATOR_ID, PUBLIC_POST_ID, null, PAGE).getContent()).isEmpty();
        assertThat(service.findByPost(CREATOR_ID, PUBLIC_POST_ID, STRANGER_MEMBER_ID, PAGE).getContent()).isEmpty();
        verify(commentRepository, times(1)).findByPostIdOldestFirst(PUBLIC_POST_ID, PAGE);
        verify(commentRepository).findVisibleByPostIdOldestFirst(PUBLIC_POST_ID, null, PAGE);
        verify(commentRepository).findVisibleByPostIdOldestFirst(PUBLIC_POST_ID, STRANGER_MEMBER_ID, PAGE);
    }

    @Test
    void BLOCK된_댓글도_작성자_본인에게는_원문이_그대로_보이고_필터링_표시가_없다() {
        stubVisibleCommentPage(FAN_MEMBER_ID, blocked(1L, FAN_MEMBER_ID, "profanity:병신"));

        CreatorPostCommentView view = service.findByPost(CREATOR_ID, PUBLIC_POST_ID, FAN_MEMBER_ID, PAGE).getContent().get(0);

        assertThat(view.filtered()).isFalse();
        assertThat(view.revealable()).isFalse();
        assertThat(view.content()).isEqualTo("댓글 1");
    }

    @Test
    void 개인정보로_막힌_댓글은_가려지고_원문_보기도_허용하지_않는다() {
        stubCommentPage(blocked(1L, FAN_MEMBER_ID, "classifier,privacy:phone"));

        CreatorPostCommentView view = service.findByPost(CREATOR_ID, PUBLIC_POST_ID, OWNER_MEMBER_ID, PAGE).getContent().get(0);

        assertThat(view.filtered()).isTrue();
        assertThat(view.revealable()).isFalse();
        assertThat(view.content()).isNull();
    }

    @Test
    void 관리자가_숨긴_댓글은_스페이스_주인에게도_가려지고_원문_보기도_허용하지_않는다() {
        CreatorPostComment hidden = passed(1L, FAN_MEMBER_ID);
        hidden.hideByAdmin(9L, NOW);
        stubCommentPage(hidden);

        CreatorPostCommentView view = service.findByPost(CREATOR_ID, PUBLIC_POST_ID, OWNER_MEMBER_ID, PAGE).getContent().get(0);

        assertThat(view.filtered()).isTrue();
        assertThat(view.revealable()).isFalse();
        assertThat(view.content()).isNull();
    }

    @Test
    void 관리자가_숨긴_댓글도_작성자_본인에게는_원문이_그대로_보인다() {
        CreatorPostComment hidden = passed(1L, FAN_MEMBER_ID);
        hidden.hideByAdmin(9L, NOW);
        stubVisibleCommentPage(FAN_MEMBER_ID, hidden);

        CreatorPostCommentView view = service.findByPost(CREATOR_ID, PUBLIC_POST_ID, FAN_MEMBER_ID, PAGE).getContent().get(0);

        assertThat(view.filtered()).isFalse();
        assertThat(view.content()).isEqualTo("댓글 1");
    }

    @Test
    void 관리자가_숨긴_댓글의_원문은_스페이스_주인에게도_403이고_작성자_본인은_404다() {
        CreatorPostComment hidden = passed(1L, FAN_MEMBER_ID);
        hidden.hideByAdmin(9L, NOW);
        given(commentRepository.findById(1L)).willReturn(Optional.of(hidden));

        assertError(() -> service.findOriginal(CREATOR_ID, PUBLIC_POST_ID, 1L, OWNER_MEMBER_ID),
                PostErrorCode.COMMENT_NOT_REVEALABLE);
        assertError(() -> service.findOriginal(CREATOR_ID, PUBLIC_POST_ID, 1L, FAN_MEMBER_ID),
                CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 관리자가_숨긴_댓글은_본문을_수정하고_재판정을_받아도_계속_숨겨진다() {
        CreatorPostComment hidden = passed(1L, FAN_MEMBER_ID);
        hidden.hideByAdmin(9L, NOW);

        hidden.update("고친 댓글", LATER);
        hidden.markFiltered(CommentFilterAction.PASS, List.of(), "rule-2", "model-2", LATER);

        assertThat(hidden.isHiddenByAdmin()).isTrue();
        assertThat(hidden.isConcealed()).isTrue();
    }

    @Test
    void 회원_프로필_사진이_있으면_공개_URL을_없으면_null을_작성자_사진으로_내려준다() {
        Member withPhoto = member(FAN_MEMBER_ID);
        withPhoto.updateProfileImageKey("member-profile-images/20/a.jpg");
        given(profileImageUrlResolver.resolve("member-profile-images/20/a.jpg"))
                .willReturn("https://cdn.example.com/member-profile-images/20/a.jpg");
        given(commentRepository.findVisibleByPostIdOldestFirst(PUBLIC_POST_ID, null, PAGE)).willReturn(new PageImpl<>(List.of(
                comment(1L, PUBLIC_POST_ID, FAN_MEMBER_ID), comment(2L, PUBLIC_POST_ID, STRANGER_MEMBER_ID)), PAGE, 2));
        given(memberRepository.findAllById(Set.of(FAN_MEMBER_ID, STRANGER_MEMBER_ID)))
                .willReturn(List.of(withPhoto, member(STRANGER_MEMBER_ID)));

        List<CreatorPostCommentView> views = service.findByPost(CREATOR_ID, PUBLIC_POST_ID, null, PAGE).getContent();

        assertThat(views).extracting(CreatorPostCommentView::authorProfileImageUrl)
                .containsExactly("https://cdn.example.com/member-profile-images/20/a.jpg", null);
        verify(creatorPublicProfileQueryService).findByMemberIds(Set.of(FAN_MEMBER_ID, STRANGER_MEMBER_ID));
    }

    @Test
    void Creator_본인_댓글은_Space_프로필_사진을_쓰고_댓글이_여럿이어도_Space는_한_번만_읽는다() {
        Member owner = member(OWNER_MEMBER_ID);
        owner.updateProfileImageKey("member-profile-images/10/own.jpg");
        given(commentRepository.findVisibleByPostIdOldestFirst(PUBLIC_POST_ID, null, PAGE)).willReturn(new PageImpl<>(List.of(
                comment(1L, PUBLIC_POST_ID, OWNER_MEMBER_ID), comment(2L, PUBLIC_POST_ID, OWNER_MEMBER_ID),
                comment(3L, PUBLIC_POST_ID, FAN_MEMBER_ID)), PAGE, 3));
        given(memberRepository.findAllById(Set.of(OWNER_MEMBER_ID, FAN_MEMBER_ID)))
                .willReturn(List.of(owner, member(FAN_MEMBER_ID)));
        given(creatorPublicProfileQueryService.findByMemberIds(Set.of(OWNER_MEMBER_ID, FAN_MEMBER_ID)))
                .willReturn(java.util.Map.of(OWNER_MEMBER_ID,
                        creatorProfile(OWNER_MEMBER_ID, CREATOR_ID, "creator-" + CREATOR_ID,
                                "https://cdn.example.com/space/profile.png")));

        List<CreatorPostCommentView> views = service.findByPost(CREATOR_ID, PUBLIC_POST_ID, null, PAGE).getContent();

        // 회원 개인 사진이 있어도 Creator 본인 댓글은 Space 사진이다.
        assertThat(views).extracting(CreatorPostCommentView::authorProfileImageUrl)
                .containsExactly("https://cdn.example.com/space/profile.png", "https://cdn.example.com/space/profile.png", null);
        verify(creatorPublicProfileQueryService).findByMemberIds(Set.of(OWNER_MEMBER_ID, FAN_MEMBER_ID));
        verify(profileImageUrlResolver, never()).resolve("member-profile-images/10/own.jpg");
    }

    @Test
    void Creator_Space가_없으면_Creator_본인_댓글의_사진은_null이다() {
        given(commentRepository.findVisibleByPostIdOldestFirst(PUBLIC_POST_ID, null, PAGE)).willReturn(new PageImpl<>(List.of(
                comment(1L, PUBLIC_POST_ID, OWNER_MEMBER_ID)), PAGE, 1));
        given(memberRepository.findAllById(Set.of(OWNER_MEMBER_ID))).willReturn(List.of(member(OWNER_MEMBER_ID)));
        given(creatorPublicProfileQueryService.findByMemberIds(Set.of(OWNER_MEMBER_ID)))
                .willReturn(java.util.Map.of(OWNER_MEMBER_ID,
                        creatorProfile(OWNER_MEMBER_ID, CREATOR_ID, "creator-" + CREATOR_ID, null)));

        CreatorPostCommentView view = service.findByPost(CREATOR_ID, PUBLIC_POST_ID, null, PAGE).getContent().get(0);

        assertThat(view.authorProfileImageUrl()).isNull();
        assertThat(view.authorName()).isEqualTo("creator-" + CREATOR_ID);
    }

    @Test
    void 다른_Creator가_작성한_댓글도_게시글_소유자_여부와_관계없이_활동명과_Space_사진을_쓴다() {
        Member otherCreator = member(STRANGER_MEMBER_ID);
        otherCreator.updateProfileImageKey("member-profile-images/30/private.jpg");
        given(commentRepository.findVisibleByPostIdOldestFirst(PUBLIC_POST_ID, null, PAGE)).willReturn(new PageImpl<>(List.of(
                comment(1L, PUBLIC_POST_ID, STRANGER_MEMBER_ID)), PAGE, 1));
        given(memberRepository.findAllById(Set.of(STRANGER_MEMBER_ID))).willReturn(List.of(otherCreator));
        given(creatorPublicProfileQueryService.findByMemberIds(Set.of(STRANGER_MEMBER_ID)))
                .willReturn(java.util.Map.of(STRANGER_MEMBER_ID,
                        creatorProfile(STRANGER_MEMBER_ID, 2L, "다른크리에이터", "https://cdn.example.com/space/2.png")));

        CreatorPostCommentView view = service.findByPost(CREATOR_ID, PUBLIC_POST_ID, null, PAGE).getContent().get(0);

        assertThat(view.authorName()).isEqualTo("다른크리에이터");
        assertThat(view.authorProfileImageUrl()).isEqualTo("https://cdn.example.com/space/2.png");
        assertThat(view.writtenByCreator()).isFalse();
        verify(profileImageUrlResolver, never()).resolve("member-profile-images/30/private.jpg");
    }

    @Test
    void 작성자를_찾지_못한_댓글은_이름과_사진이_null이다() {
        given(commentRepository.findVisibleByPostIdOldestFirst(PUBLIC_POST_ID, null, PAGE)).willReturn(new PageImpl<>(List.of(
                comment(1L, PUBLIC_POST_ID, FAN_MEMBER_ID)), PAGE, 1));
        given(memberRepository.findAllById(Set.of(FAN_MEMBER_ID))).willReturn(List.of());

        CreatorPostCommentView view = service.findByPost(CREATOR_ID, PUBLIC_POST_ID, null, PAGE).getContent().get(0);

        assertThat(view.authorName()).isNull();
        assertThat(view.authorProfileImageUrl()).isNull();
    }

    @Test
    void 필터가_차단하거나_관리자가_숨긴_댓글은_스페이스_주인에게_작성자_이름과_사진도_가린다() {
        Member fan = member(FAN_MEMBER_ID);
        fan.updateProfileImageKey("member-profile-images/20/a.jpg");
        given(profileImageUrlResolver.resolve("member-profile-images/20/a.jpg"))
                .willReturn("https://cdn.example.com/member-profile-images/20/a.jpg");
        CreatorPostComment hidden = passed(2L, FAN_MEMBER_ID);
        hidden.hideByAdmin(9L, NOW);
        stubCommentPage(blocked(1L, FAN_MEMBER_ID, "profanity:test"), hidden, passed(3L, FAN_MEMBER_ID));
        given(memberRepository.findAllById(any())).willReturn(List.of(fan));

        List<CreatorPostCommentView> views =
                service.findByPost(CREATOR_ID, PUBLIC_POST_ID, OWNER_MEMBER_ID, PAGE).getContent();

        assertThat(views.get(0).authorName()).isNull();
        assertThat(views.get(0).authorProfileImageUrl()).isNull();
        assertThat(views.get(1).authorName()).isNull();
        assertThat(views.get(1).authorProfileImageUrl()).isNull();
        // 가려지지 않은 댓글은 그대로다.
        assertThat(views.get(2).authorName()).isEqualTo("member-" + FAN_MEMBER_ID);
        assertThat(views.get(2).authorProfileImageUrl()).isEqualTo("https://cdn.example.com/member-profile-images/20/a.jpg");
    }

    @Test
    void 가려진_댓글도_작성자_본인에게는_이름과_사진이_보이고_스페이스_주인에게는_가려진다() {
        Member fan = member(FAN_MEMBER_ID);
        fan.updateProfileImageKey("member-profile-images/20/a.jpg");
        given(profileImageUrlResolver.resolve("member-profile-images/20/a.jpg"))
                .willReturn("https://cdn.example.com/member-profile-images/20/a.jpg");
        CreatorPostComment blockedComment = blocked(1L, FAN_MEMBER_ID, "profanity:test");
        stubCommentPage(blockedComment);
        stubVisibleCommentPage(FAN_MEMBER_ID, blockedComment);
        given(memberRepository.findAllById(any())).willReturn(List.of(fan));

        CreatorPostCommentView seenByAuthor =
                service.findByPost(CREATOR_ID, PUBLIC_POST_ID, FAN_MEMBER_ID, PAGE).getContent().get(0);
        CreatorPostCommentView seenByCreator =
                service.findByPost(CREATOR_ID, PUBLIC_POST_ID, OWNER_MEMBER_ID, PAGE).getContent().get(0);

        assertThat(seenByAuthor.filtered()).isFalse();
        assertThat(seenByAuthor.authorName()).isEqualTo("member-" + FAN_MEMBER_ID);
        assertThat(seenByAuthor.authorProfileImageUrl()).isEqualTo("https://cdn.example.com/member-profile-images/20/a.jpg");
        assertThat(seenByCreator.filtered()).isTrue();
        assertThat(seenByCreator.authorName()).isNull();
        assertThat(seenByCreator.authorProfileImageUrl()).isNull();
    }

    @Test
    void 작성과_수정_응답에도_작성자_사진이_담긴다() {
        Member fan = member(FAN_MEMBER_ID);
        fan.updateProfileImageKey("member-profile-images/20/a.jpg");
        given(memberRepository.findByIdForUpdate(FAN_MEMBER_ID)).willReturn(Optional.of(fan));
        given(profileImageUrlResolver.resolve("member-profile-images/20/a.jpg"))
                .willReturn("https://cdn.example.com/member-profile-images/20/a.jpg");
        given(commentRepository.findByIdForUpdate(500L))
                .willReturn(Optional.of(comment(500L, PUBLIC_POST_ID, FAN_MEMBER_ID)));

        CreatorPostCommentView created = service.create(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, "응원해요");
        CreatorPostCommentView updated = service.update(FAN_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, 500L, "고쳤어요");

        assertThat(created.authorProfileImageUrl()).isEqualTo("https://cdn.example.com/member-profile-images/20/a.jpg");
        assertThat(updated.authorProfileImageUrl()).isEqualTo("https://cdn.example.com/member-profile-images/20/a.jpg");
    }

    @Test
    void Creator_본인이_작성하면_응답의_사진은_Space_프로필_사진이다() {
        given(creatorPublicProfileQueryService.findByMemberIds(Set.of(OWNER_MEMBER_ID)))
                .willReturn(java.util.Map.of(OWNER_MEMBER_ID,
                        creatorProfile(OWNER_MEMBER_ID, CREATOR_ID, "creator-" + CREATOR_ID,
                                "https://cdn.example.com/space/profile.png")));

        CreatorPostCommentView created = service.create(OWNER_MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID, "공지예요");

        assertThat(created.writtenByCreator()).isTrue();
        assertThat(created.authorName()).isEqualTo("creator-" + CREATOR_ID);
        assertThat(created.authorProfileImageUrl()).isEqualTo("https://cdn.example.com/space/profile.png");
    }

    @Test
    void 판정_전이거나_실패한_댓글은_가리지_않는다() {
        CreatorPostComment pending = comment(1L, PUBLIC_POST_ID, FAN_MEMBER_ID);
        CreatorPostComment failed = comment(2L, PUBLIC_POST_ID, FAN_MEMBER_ID);
        failed.markFilterFailed();
        stubVisibleCommentPage(STRANGER_MEMBER_ID, pending, failed);

        List<CreatorPostCommentView> views = service.findByPost(CREATOR_ID, PUBLIC_POST_ID, STRANGER_MEMBER_ID, PAGE).getContent();

        assertThat(views).allSatisfy(view -> assertThat(view.filtered()).isFalse());
    }

    @Test
    void 필터링된_댓글의_원문은_스페이스_주인만_받는다() {
        given(commentRepository.findById(1L)).willReturn(Optional.of(blocked(1L, FAN_MEMBER_ID, "profanity:병신")));

        assertThat(service.findOriginal(CREATOR_ID, PUBLIC_POST_ID, 1L, OWNER_MEMBER_ID).content()).isEqualTo("댓글 1");
    }

    @Test
    void 스페이스_주인이_아닌_조회자의_필터링된_댓글_원문_요청은_404다() {
        given(commentRepository.findById(1L)).willReturn(Optional.of(blocked(1L, FAN_MEMBER_ID, "profanity:병신")));

        assertError(() -> service.findOriginal(CREATOR_ID, PUBLIC_POST_ID, 1L, null), CommonErrorCode.RESOURCE_NOT_FOUND);
        assertError(() -> service.findOriginal(CREATOR_ID, PUBLIC_POST_ID, 1L, STRANGER_MEMBER_ID),
                CommonErrorCode.RESOURCE_NOT_FOUND);
        // 팔로워도 작성자 본인도 열 수 없다.
        assertError(() -> service.findOriginal(CREATOR_ID, PUBLIC_POST_ID, 1L, FAN_MEMBER_ID),
                CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 개인정보로_막힌_댓글의_원문은_403이다() {
        given(commentRepository.findById(1L)).willReturn(Optional.of(blocked(1L, FAN_MEMBER_ID, "privacy:email")));

        assertError(() -> service.findOriginal(CREATOR_ID, PUBLIC_POST_ID, 1L, OWNER_MEMBER_ID),
                PostErrorCode.COMMENT_NOT_REVEALABLE);
    }

    @Test
    void 필터링되지_않은_댓글이나_작성자_본인의_원문_요청은_404다() {
        given(commentRepository.findById(1L)).willReturn(Optional.of(passed(1L, FAN_MEMBER_ID)));
        given(commentRepository.findById(2L)).willReturn(Optional.of(blocked(2L, FAN_MEMBER_ID, "profanity:병신")));

        assertError(() -> service.findOriginal(CREATOR_ID, PUBLIC_POST_ID, 1L, OWNER_MEMBER_ID),
                CommonErrorCode.RESOURCE_NOT_FOUND);
        assertError(() -> service.findOriginal(CREATOR_ID, PUBLIC_POST_ID, 2L, FAN_MEMBER_ID),
                CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 다른_게시글의_댓글이나_없는_댓글의_원문은_404다() {
        given(commentRepository.findById(1L)).willReturn(Optional.of(blocked(1L, FAN_MEMBER_ID, "profanity:병신")));

        assertError(() -> service.findOriginal(CREATOR_ID, FOLLOWERS_POST_ID, 1L, OWNER_MEMBER_ID),
                CommonErrorCode.RESOURCE_NOT_FOUND);
        assertError(() -> service.findOriginal(CREATOR_ID, PUBLIC_POST_ID, 999L, OWNER_MEMBER_ID),
                CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 팔로워_공개_게시글의_필터링된_댓글_원문은_볼_수_없는_사람에게_403이다() {
        assertError(() -> service.findOriginal(CREATOR_ID, FOLLOWERS_POST_ID, 1L, STRANGER_MEMBER_ID),
                PostErrorCode.POST_FOLLOWERS_ONLY);
    }

    private void stubCommentPage(CreatorPostComment... comments) {
        given(commentRepository.findByPostIdOldestFirst(PUBLIC_POST_ID, PAGE))
                .willReturn(new PageImpl<>(List.of(comments), PAGE, comments.length));
        given(memberRepository.findAllById(any())).willReturn(List.of(member(FAN_MEMBER_ID)));
    }

    /** 스페이스 주인이 아닌 조회자용 목록 조회. 저장소가 가려진 댓글을 빼므로 넘긴 댓글이 그대로 내려온다고 가정한다. */
    private void stubVisibleCommentPage(Long viewerMemberId, CreatorPostComment... comments) {
        given(commentRepository.findVisibleByPostIdOldestFirst(PUBLIC_POST_ID, viewerMemberId, PAGE))
                .willReturn(new PageImpl<>(List.of(comments), PAGE, comments.length));
        given(memberRepository.findAllById(any())).willReturn(List.of(member(FAN_MEMBER_ID)));
    }

    private CreatorPostComment passed(Long commentId, Long memberId) {
        CreatorPostComment comment = comment(commentId, PUBLIC_POST_ID, memberId);
        comment.markFiltered(CommentFilterAction.PASS, List.of(), "rule-1", "model-1", NOW);
        return comment;
    }

    private CreatorPostComment blocked(Long commentId, Long memberId, String reasons) {
        CreatorPostComment comment = comment(commentId, PUBLIC_POST_ID, memberId);
        comment.markFiltered(CommentFilterAction.BLOCK, List.of(reasons.split(",")), "rule-1", "model-1", NOW);
        return comment;
    }

    private CreatorPostComment comment(Long commentId, Long postId, Long memberId) {
        CreatorPostComment comment = new CreatorPostComment(postId, memberId, "댓글 " + commentId, NOW);
        ReflectionTestUtils.setField(comment, "commentId", commentId);
        return comment;
    }

    private CreatorPublicProfile creatorProfile(
            Long memberId, Long creatorId, String activityName, String profileImageUrl) {
        return new CreatorPublicProfile(creatorId, activityName, profileImageUrl);
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, ErrorCode expected) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}

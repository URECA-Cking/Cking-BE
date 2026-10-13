package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.domain.PostVisibility;
import kr.co.cking.post.repository.CreatorPostLikeRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static kr.co.cking.post.PostFixtures.creator;
import static kr.co.cking.post.PostFixtures.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PostLikeWriterTest {

    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
    private static final Long CREATOR_ID = 1L;
    private static final Long OWNER_MEMBER_ID = 10L;
    private static final Long MEMBER_ID = 20L;
    private static final Long PUBLIC_POST_ID = 100L;
    private static final Long FOLLOWERS_POST_ID = 101L;

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorPostRepository postRepository = mock(CreatorPostRepository.class);
    private final CreatorFollowQueryService followQueryService = mock(CreatorFollowQueryService.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorPostLikeRepository likeRepository = mock(CreatorPostLikeRepository.class);
    private final PostLikeWriter writer = new PostLikeWriter(
            new PostAccessPolicy(creatorRepository, postRepository, followQueryService),
            memberRepository, likeRepository, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        given(memberRepository.existsById(MEMBER_ID)).willReturn(true);
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.of(creator(CREATOR_ID, OWNER_MEMBER_ID)));
        given(postRepository.findByIdForShare(PUBLIC_POST_ID))
                .willReturn(Optional.of(post(PUBLIC_POST_ID, CREATOR_ID, PostVisibility.PUBLIC)));
        given(postRepository.findByIdForShare(FOLLOWERS_POST_ID))
                .willReturn(Optional.of(post(FOLLOWERS_POST_ID, CREATOR_ID, PostVisibility.FOLLOWERS)));
    }

    @Test
    void 전체_공개_게시글은_팔로우하지_않아도_좋아요하고_방금_누른_좋아요는_likedToday이다() {
        given(likeRepository.findCreatedAt(PUBLIC_POST_ID, MEMBER_ID)).willReturn(Optional.of(NOW));
        given(likeRepository.countByPostId(PUBLIC_POST_ID)).willReturn(3L);

        PostLikeWriter.Result result = writer.like(MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID);

        verify(likeRepository).insertIfAbsent(PUBLIC_POST_ID, MEMBER_ID, NOW);
        assertThat(result.likedToday()).isTrue();
        assertThat(result.likeCount()).isEqualTo(3L);
    }

    @Test
    void 오늘_누른_좋아요에_다시_요청하면_likedToday가_true이다() {
        given(likeRepository.findCreatedAt(PUBLIC_POST_ID, MEMBER_ID))
                .willReturn(Optional.of(Instant.parse("2026-10-13T00:00:00Z")));

        assertThat(writer.like(MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID).likedToday()).isTrue();
    }

    /** Connector/J 기본 설정은 중복 insert에도 영향 행 수 1을 돌려주므로 insert 결과가 아니라 저장된 생성 시각으로 판단한다. */
    @Test
    void 이전_날_UTC에_누른_좋아요는_자정_직전이어도_likedToday가_false이다() {
        given(likeRepository.findCreatedAt(PUBLIC_POST_ID, MEMBER_ID))
                .willReturn(Optional.of(Instant.parse("2026-10-12T23:59:59Z")));

        PostLikeWriter.Result result = writer.like(MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID);

        verify(likeRepository).insertIfAbsent(PUBLIC_POST_ID, MEMBER_ID, NOW);
        assertThat(result.likedToday()).isFalse();
    }

    @Test
    void 동시에_취소되어_좋아요_행을_읽지_못하면_likedToday가_false이다() {
        given(likeRepository.findCreatedAt(PUBLIC_POST_ID, MEMBER_ID)).willReturn(Optional.empty());

        assertThat(writer.like(MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID).likedToday()).isFalse();
    }

    @Test
    void 팔로워_공개_게시글은_팔로워가_아니면_좋아요할_수_없다() {
        given(followQueryService.isFollowing(MEMBER_ID, CREATOR_ID)).willReturn(false);

        assertThatThrownBy(() -> writer.like(MEMBER_ID, CREATOR_ID, FOLLOWERS_POST_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PostErrorCode.POST_FOLLOWERS_ONLY));
        verify(likeRepository, never()).insertIfAbsent(anyLong(), anyLong(), any());
    }

    @Test
    void 팔로워_공개_게시글은_팔로워가_좋아요할_수_있다() {
        given(followQueryService.isFollowing(MEMBER_ID, CREATOR_ID)).willReturn(true);
        given(likeRepository.findCreatedAt(FOLLOWERS_POST_ID, MEMBER_ID)).willReturn(Optional.of(NOW));

        assertThat(writer.like(MEMBER_ID, CREATOR_ID, FOLLOWERS_POST_ID).likedToday()).isTrue();
    }

    @Test
    void 팔로워_공개_게시글은_작성_Creator_본인이_좋아요할_수_있다() {
        given(memberRepository.existsById(OWNER_MEMBER_ID)).willReturn(true);
        given(likeRepository.findCreatedAt(FOLLOWERS_POST_ID, OWNER_MEMBER_ID)).willReturn(Optional.of(NOW));

        assertThat(writer.like(OWNER_MEMBER_ID, CREATOR_ID, FOLLOWERS_POST_ID).likedToday()).isTrue();
    }

    @Test
    void 없는_게시글과_다른_Creator의_게시글은_404이다() {
        given(postRepository.findByIdForShare(999L)).willReturn(Optional.empty());
        given(postRepository.findByIdForShare(200L))
                .willReturn(Optional.of(post(200L, 2L, PostVisibility.PUBLIC)));

        for (Long postId : new Long[]{999L, 200L}) {
            assertThatThrownBy(() -> writer.like(MEMBER_ID, CREATOR_ID, postId))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        }
        verify(likeRepository, never()).insertIfAbsent(anyLong(), anyLong(), any());
    }

    @Test
    void 없는_회원은_404이다() {
        given(memberRepository.existsById(999L)).willReturn(false);

        assertThatThrownBy(() -> writer.like(999L, CREATOR_ID, PUBLIC_POST_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void 취소는_팔로우를_끊었어도_팔로워_공개_게시글에서_할_수_있다() {
        given(likeRepository.countByPostId(FOLLOWERS_POST_ID)).willReturn(0L);

        PostLikeWriter.Result result = writer.unlike(MEMBER_ID, CREATOR_ID, FOLLOWERS_POST_ID);

        verify(likeRepository).deleteByPostIdAndMemberId(FOLLOWERS_POST_ID, MEMBER_ID);
        assertThat(result.likedToday()).isFalse();
        assertThat(result.likeCount()).isZero();
        verify(followQueryService, never()).isFollowing(anyLong(), anyLong());
    }

    @Test
    void 좋아요하지_않은_게시글을_취소해도_성공하고_현재_좋아요_수를_반환한다() {
        given(likeRepository.deleteByPostIdAndMemberId(PUBLIC_POST_ID, MEMBER_ID)).willReturn(0);
        given(likeRepository.countByPostId(PUBLIC_POST_ID)).willReturn(5L);

        PostLikeWriter.Result result = writer.unlike(MEMBER_ID, CREATOR_ID, PUBLIC_POST_ID);

        assertThat(result.likedToday()).isFalse();
        assertThat(result.likeCount()).isEqualTo(5L);
    }
}

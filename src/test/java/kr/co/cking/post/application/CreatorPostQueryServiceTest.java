package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import kr.co.cking.post.application.dto.CreatorPostView;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.domain.PostVisibility;
import kr.co.cking.post.repository.CreatorPostImageRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static kr.co.cking.post.PostFixtures.creator;
import static kr.co.cking.post.PostFixtures.linkedImage;
import static kr.co.cking.post.PostFixtures.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CreatorPostQueryServiceTest {

    private static final Long CREATOR_ID = 1L;
    private static final Long OWNER_MEMBER_ID = 10L;
    private static final Long VIEWER_MEMBER_ID = 20L;
    private static final PageRequest PAGE = PageRequest.of(0, 20);

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorPostRepository postRepository = mock(CreatorPostRepository.class);
    private final CreatorPostImageRepository imageRepository = mock(CreatorPostImageRepository.class);
    private final CreatorFollowQueryService followQueryService = mock(CreatorFollowQueryService.class);
    private final ObjectStorage objectStorage = mock(ObjectStorage.class);
    private final CreatorPostQueryService service = new CreatorPostQueryService(
            new PostAccessPolicy(creatorRepository, postRepository, followQueryService),
            postRepository, imageRepository, new CreatorPostViewAssembler(objectStorage));

    private final CreatorPost publicPost = post(100L, CREATOR_ID, PostVisibility.PUBLIC);
    private final CreatorPost followersPost = post(101L, CREATOR_ID, PostVisibility.FOLLOWERS);

    @BeforeEach
    void setUp() {
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.of(creator(CREATOR_ID, OWNER_MEMBER_ID)));
        given(postRepository.findByCreatorIdLatestFirst(CREATOR_ID, PAGE))
                .willReturn(new PageImpl<>(List.of(followersPost, publicPost), PAGE, 2));
        given(imageRepository.findByPostIdInOrderByPostIdAscDisplayOrderAsc(List.of(101L, 100L))).willReturn(List.of(
                linkedImage("public-0", CREATOR_ID, 100L, 0),
                linkedImage("followers-0", CREATOR_ID, 101L, 0),
                linkedImage("followers-1", CREATOR_ID, 101L, 1)));
        given(objectStorage.presignedGetUrl(anyString(), eq(Duration.ofMinutes(10))))
                .willAnswer(invocation -> URI.create("https://storage.test/" + invocation.getArgument(0)));
    }

    @Test
    void 비로그인_목록은_팔로워_공개_게시글을_본문_이미지_없이_잠금으로_보여준다() {
        List<CreatorPostView> posts = service.findByCreator(CREATOR_ID, null, PAGE).getContent();

        CreatorPostView locked = posts.get(0);
        assertThat(locked.postId()).isEqualTo(101L);
        assertThat(locked.locked()).isTrue();
        assertThat(locked.content()).isNull();
        assertThat(locked.images()).isEmpty();
        assertThat(locked.imageCount()).isEqualTo(2);

        CreatorPostView open = posts.get(1);
        assertThat(open.locked()).isFalse();
        assertThat(open.content()).isEqualTo(publicPost.getContent());
        assertThat(open.images()).containsExactly(
                new CreatorPostView.Image("public-0", "https://storage.test/public-0"));
        verify(followQueryService, never()).isFollowing(any(), anyLong());
        verify(objectStorage, never()).presignedGetUrl(eq("followers-0"), any());
    }

    @Test
    void 팔로우하지_않은_로그인_사용자도_팔로워_공개_게시글은_잠금이다() {
        given(followQueryService.isFollowing(VIEWER_MEMBER_ID, CREATOR_ID)).willReturn(false);

        List<CreatorPostView> posts = service.findByCreator(CREATOR_ID, VIEWER_MEMBER_ID, PAGE).getContent();

        assertThat(posts.get(0).locked()).isTrue();
    }

    @Test
    void 팔로워는_팔로워_공개_게시글의_본문과_이미지를_순서대로_본다() {
        given(followQueryService.isFollowing(VIEWER_MEMBER_ID, CREATOR_ID)).willReturn(true);

        CreatorPostView post = service.findByCreator(CREATOR_ID, VIEWER_MEMBER_ID, PAGE).getContent().get(0);

        assertThat(post.locked()).isFalse();
        assertThat(post.content()).isEqualTo(followersPost.getContent());
        assertThat(post.images()).extracting(CreatorPostView.Image::imageKey)
                .containsExactly("followers-0", "followers-1");
    }

    @Test
    void 작성한_Creator_본인은_팔로우하지_않아도_팔로워_공개_게시글을_본다() {
        CreatorPostView post = service.findByCreator(CREATOR_ID, OWNER_MEMBER_ID, PAGE).getContent().get(0);

        assertThat(post.locked()).isFalse();
        verify(followQueryService, never()).isFollowing(any(), anyLong());
    }

    @Test
    void 없는_크리에이터의_목록은_404다() {
        given(creatorRepository.findById(2L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByCreator(2L, null, PAGE))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void 권한_없는_팔로워_공개_게시글_상세는_POST_FOLLOWERS_ONLY다() {
        given(postRepository.findById(101L)).willReturn(Optional.of(followersPost));

        assertThatThrownBy(() -> service.findDetail(CREATOR_ID, 101L, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PostErrorCode.POST_FOLLOWERS_ONLY));
        verify(objectStorage, never()).presignedGetUrl(anyString(), any());
    }

    @Test
    void 팔로워는_팔로워_공개_게시글_상세를_본다() {
        given(postRepository.findById(101L)).willReturn(Optional.of(followersPost));
        given(followQueryService.isFollowing(VIEWER_MEMBER_ID, CREATOR_ID)).willReturn(true);
        given(imageRepository.findByPostIdOrderByDisplayOrderAsc(101L))
                .willReturn(List.of(linkedImage("followers-0", CREATOR_ID, 101L, 0)));

        CreatorPostView detail = service.findDetail(CREATOR_ID, 101L, VIEWER_MEMBER_ID);

        assertThat(detail.locked()).isFalse();
        assertThat(detail.images()).extracting(CreatorPostView.Image::url)
                .containsExactly("https://storage.test/followers-0");
    }

    @Test
    void 이미지_주소_발급이_실패해도_목록은_성공하고_해당_이미지_url만_null이다() {
        given(objectStorage.presignedGetUrl(eq("public-0"), any()))
                .willThrow(new IllegalStateException("credentials unavailable"));

        CreatorPostView open = service.findByCreator(CREATOR_ID, null, PAGE).getContent().get(1);

        assertThat(open.images()).containsExactly(new CreatorPostView.Image("public-0", null));
        assertThat(open.imageCount()).isEqualTo(1);
    }

    @Test
    void 다른_크리에이터의_게시글_ID로_상세를_조회하면_404다() {
        given(postRepository.findById(200L)).willReturn(Optional.of(post(200L, 2L, PostVisibility.PUBLIC)));

        assertThatThrownBy(() -> service.findDetail(CREATOR_ID, 200L, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}

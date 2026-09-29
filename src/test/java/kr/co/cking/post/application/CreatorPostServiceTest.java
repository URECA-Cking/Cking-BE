package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.post.application.dto.CreatorPostFields;
import kr.co.cking.post.application.dto.CreatorPostView;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.domain.PostVisibility;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostImageRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ApplicationEventPublisher;

import java.net.URI;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static kr.co.cking.post.PostFixtures.NOW;
import static kr.co.cking.post.PostFixtures.creator;
import static kr.co.cking.post.PostFixtures.linkedImage;
import static kr.co.cking.post.PostFixtures.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CreatorPostServiceTest {

    private static final Long MEMBER_ID = 10L;
    private static final Long CREATOR_ID = 1L;
    private static final Long POST_ID = 100L;

    private final PostAuthorLookup authorLookup = mock(PostAuthorLookup.class);
    private final CreatorPostRepository postRepository = mock(CreatorPostRepository.class);
    private final CreatorPostImageRepository imageRepository = mock(CreatorPostImageRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ObjectStorage objectStorage = mock(ObjectStorage.class);
    private final CreatorPostCommentRepository commentRepository = mock(CreatorPostCommentRepository.class);
    private final CreatorPostService service = new CreatorPostService(
            authorLookup, postRepository, imageRepository, commentRepository, eventPublisher,
            new CreatorPostViewAssembler(objectStorage), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        given(authorLookup.requireCreator(MEMBER_ID)).willReturn(creator(CREATOR_ID, MEMBER_ID));
        given(objectStorage.presignedGetUrl(anyString(), any()))
                .willAnswer(invocation -> URI.create("https://storage.test/" + invocation.getArgument(0)));
    }

    @Test
    void 작성은_이미지를_조건부로_연결하고_요청_순서대로_표시_순서를_정한다() {
        given(postRepository.save(any(CreatorPost.class))).willReturn(post(POST_ID, CREATOR_ID, PostVisibility.PUBLIC));
        given(imageRepository.linkToPost(List.of("k2", "k1"), CREATOR_ID, POST_ID, NOW)).willReturn(2);

        CreatorPostView created = service.create(MEMBER_ID, fields("본문", PostVisibility.PUBLIC, "k2", "k1"));

        assertThat(created.postId()).isEqualTo(POST_ID);
        assertThat(created.locked()).isFalse();
        assertThat(created.images()).containsExactly(
                new CreatorPostView.Image("k2", "https://storage.test/k2"),
                new CreatorPostView.Image("k1", "https://storage.test/k1"));
        verify(imageRepository).updateDisplayOrder("k2", POST_ID, 0);
        verify(imageRepository).updateDisplayOrder("k1", POST_ID, 1);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void 연결된_이미지_수가_요청과_다르면_작성을_거부한다() {
        given(postRepository.save(any(CreatorPost.class))).willReturn(post(POST_ID, CREATOR_ID, PostVisibility.PUBLIC));
        given(imageRepository.linkToPost(anyCollection(), eq(CREATOR_ID), eq(POST_ID), eq(NOW))).willReturn(1);

        assertError(() -> service.create(MEMBER_ID, fields("본문", PostVisibility.PUBLIC, "k1", "someone-else")),
                PostErrorCode.POST_IMAGE_UNAVAILABLE);
    }

    @Test
    void 이미지만_있는_게시글도_작성할_수_있다() {
        given(postRepository.save(any(CreatorPost.class))).willReturn(post(POST_ID, CREATOR_ID, PostVisibility.FOLLOWERS));
        given(imageRepository.linkToPost(List.of("k1"), CREATOR_ID, POST_ID, NOW)).willReturn(1);

        service.create(MEMBER_ID, fields(null, PostVisibility.FOLLOWERS, "k1"));

        verify(postRepository).save(argThat(post -> post.getContent().isEmpty()
                && post.getVisibility() == PostVisibility.FOLLOWERS));
    }

    static Stream<CreatorPostFields> invalidFields() {
        return Stream.of(
                fields("본문", null),
                fields("  ", PostVisibility.PUBLIC),
                fields("a".repeat(CreatorPost.MAX_CONTENT_LENGTH + 1), PostVisibility.PUBLIC),
                fields("본문", PostVisibility.PUBLIC, "k1", "k2", "k3", "k4", "k5", "k6"),
                fields("본문", PostVisibility.PUBLIC, "k1", "k1"),
                fields("본문", PostVisibility.PUBLIC, "k1", " "));
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void 잘못된_입력은_저장하지_않고_거부한다(CreatorPostFields fields) {
        assertError(() -> service.create(MEMBER_ID, fields), CommonErrorCode.VALIDATION_FAILED);

        verify(postRepository, never()).save(any());
        verify(imageRepository, never()).linkToPost(anyCollection(), anyLong(), anyLong(), any());
    }

    @Test
    void 수정은_빠진_이미지를_해제하고_새_이미지만_연결한_뒤_전체_순서를_다시_정한다() {
        CreatorPost post = post(POST_ID, CREATOR_ID, PostVisibility.PUBLIC);
        given(postRepository.findByIdForUpdate(POST_ID)).willReturn(Optional.of(post));
        given(imageRepository.findByPostIdOrderByDisplayOrderAsc(POST_ID)).willReturn(List.of(
                linkedImage("keep", CREATOR_ID, POST_ID, 0), linkedImage("drop", CREATOR_ID, POST_ID, 1)));
        given(imageRepository.linkToPost(List.of("new"), CREATOR_ID, POST_ID, NOW)).willReturn(1);

        CreatorPostView updated = service.update(
                MEMBER_ID, POST_ID, fields("수정", PostVisibility.FOLLOWERS, "new", "keep"));

        assertThat(updated.content()).isEqualTo("수정");
        assertThat(updated.images()).extracting(CreatorPostView.Image::imageKey).containsExactly("new", "keep");
        assertThat(post.getContent()).isEqualTo("수정");
        assertThat(post.getVisibility()).isEqualTo(PostVisibility.FOLLOWERS);
        verify(imageRepository).releaseFromPost(POST_ID, List.of("drop"), NOW);
        verify(eventPublisher).publishEvent(new PostImagesReleasedEvent(List.of("drop")));
        verify(imageRepository).updateDisplayOrder("new", POST_ID, 0);
        verify(imageRepository).updateDisplayOrder("keep", POST_ID, 1);
    }

    @Test
    void 이미지가_바뀌지_않으면_해제나_연결_없이_순서만_반영한다() {
        given(postRepository.findByIdForUpdate(POST_ID))
                .willReturn(Optional.of(post(POST_ID, CREATOR_ID, PostVisibility.PUBLIC)));
        given(imageRepository.findByPostIdOrderByDisplayOrderAsc(POST_ID)).willReturn(List.of(
                linkedImage("a", CREATOR_ID, POST_ID, 0), linkedImage("b", CREATOR_ID, POST_ID, 1)));

        service.update(MEMBER_ID, POST_ID, fields("본문", PostVisibility.PUBLIC, "b", "a"));

        verify(imageRepository, never()).releaseFromPost(anyLong(), anyCollection(), any());
        verify(imageRepository, never()).linkToPost(anyCollection(), anyLong(), anyLong(), any());
        verifyNoInteractions(eventPublisher);
        verify(imageRepository).updateDisplayOrder("b", POST_ID, 0);
        verify(imageRepository).updateDisplayOrder("a", POST_ID, 1);
    }

    @Test
    void 다른_Creator의_게시글은_수정할_수_없다() {
        given(postRepository.findByIdForUpdate(POST_ID))
                .willReturn(Optional.of(post(POST_ID, 2L, PostVisibility.PUBLIC)));

        assertError(() -> service.update(MEMBER_ID, POST_ID, fields("수정", PostVisibility.PUBLIC)),
                CommonErrorCode.FORBIDDEN);
        verify(imageRepository, never()).updateDisplayOrder(anyString(), anyLong(), anyInt());
    }

    @Test
    void 없는_게시글은_수정할_수_없다() {
        given(postRepository.findByIdForUpdate(POST_ID)).willReturn(Optional.empty());

        assertError(() -> service.update(MEMBER_ID, POST_ID, fields("수정", PostVisibility.PUBLIC)),
                CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 삭제는_연결된_이미지를_모두_해제하고_댓글을_지운_뒤_게시글을_지운다() {
        CreatorPost post = post(POST_ID, CREATOR_ID, PostVisibility.PUBLIC);
        given(postRepository.findByIdForUpdate(POST_ID)).willReturn(Optional.of(post));
        given(imageRepository.findByPostIdOrderByDisplayOrderAsc(POST_ID)).willReturn(List.of(
                linkedImage("a", CREATOR_ID, POST_ID, 0), linkedImage("b", CREATOR_ID, POST_ID, 1)));

        service.delete(MEMBER_ID, POST_ID);

        verify(imageRepository).releaseFromPost(POST_ID, List.of("a", "b"), NOW);
        verify(eventPublisher).publishEvent(new PostImagesReleasedEvent(List.of("a", "b")));
        InOrder order = inOrder(commentRepository, postRepository);
        order.verify(commentRepository).deleteByPostId(POST_ID);
        order.verify(postRepository).delete(post);
    }

    @Test
    void 이미지가_없는_게시글_삭제는_이미지_삭제를_예약하지_않는다() {
        CreatorPost post = post(POST_ID, CREATOR_ID, PostVisibility.PUBLIC);
        given(postRepository.findByIdForUpdate(POST_ID)).willReturn(Optional.of(post));
        given(imageRepository.findByPostIdOrderByDisplayOrderAsc(POST_ID)).willReturn(List.of());

        service.delete(MEMBER_ID, POST_ID);

        verifyNoInteractions(eventPublisher);
        verify(postRepository).delete(post);
    }

    @Test
    void 다른_Creator의_게시글은_삭제할_수_없다() {
        given(postRepository.findByIdForUpdate(POST_ID))
                .willReturn(Optional.of(post(POST_ID, 2L, PostVisibility.PUBLIC)));

        assertError(() -> service.delete(MEMBER_ID, POST_ID), CommonErrorCode.FORBIDDEN);
        verify(postRepository, never()).delete(any());
    }

    private static CreatorPostFields fields(String content, PostVisibility visibility, String... imageKeys) {
        return new CreatorPostFields(content, visibility, Arrays.asList(imageKeys));
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, ErrorCode expected) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}

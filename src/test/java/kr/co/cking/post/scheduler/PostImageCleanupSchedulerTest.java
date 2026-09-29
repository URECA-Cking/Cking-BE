package kr.co.cking.post.scheduler;

import kr.co.cking.post.application.PostImageStorageCleaner;
import kr.co.cking.post.domain.CreatorPostImage;
import kr.co.cking.post.repository.CreatorPostImageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static kr.co.cking.post.PostFixtures.NOW;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 게시글 이미지 정리 스케줄러. */
class PostImageCleanupSchedulerTest {

    private final CreatorPostImageRepository imageRepository = mock(CreatorPostImageRepository.class);
    private final PostImageStorageCleaner storageCleaner = mock(PostImageStorageCleaner.class);

    @Test
    void 스케줄러는_24시간_지난_미연결_이미지를_먼저_선점한_뒤_유예가_지난_삭제_대기를_지운다() {
        PostImageCleanupScheduler scheduler = new PostImageCleanupScheduler(
                imageRepository, storageCleaner, Clock.fixed(NOW, ZoneOffset.UTC));
        given(imageRepository.findDeletePending(NOW.minus(Duration.ofMinutes(10)), PageRequest.of(0, 100)))
                .willReturn(List.of(CreatorPostImage.uploading("a", 1L, NOW), CreatorPostImage.uploading("b", 1L, NOW)));

        scheduler.cleanUp();

        InOrder order = inOrder(imageRepository, storageCleaner);
        order.verify(imageRepository).claimStaleUnlinked(NOW.minus(Duration.ofHours(24)), NOW);
        order.verify(imageRepository).findDeletePending(NOW.minus(Duration.ofMinutes(10)), PageRequest.of(0, 100));
        order.verify(storageCleaner).delete("a");
        order.verify(storageCleaner).delete("b");
    }

    @Test
    void 스케줄러는_한_이미지_삭제가_실패해도_나머지를_계속_지운다() {
        PostImageCleanupScheduler scheduler = new PostImageCleanupScheduler(
                imageRepository, storageCleaner, Clock.fixed(NOW, ZoneOffset.UTC));
        given(imageRepository.findDeletePending(NOW.minus(Duration.ofMinutes(10)), PageRequest.of(0, 100)))
                .willReturn(List.of(CreatorPostImage.uploading("a", 1L, NOW), CreatorPostImage.uploading("b", 1L, NOW)));
        willThrow(new IllegalStateException("storage down")).given(storageCleaner).delete("a");

        scheduler.cleanUp();

        verify(storageCleaner).delete("b");
    }

    @Test
    void 스케줄러는_삭제에_실패한_기록만_재시도_시각을_미뤄_대기열_뒤로_보낸다() {
        PostImageCleanupScheduler scheduler = new PostImageCleanupScheduler(
                imageRepository, storageCleaner, Clock.fixed(NOW, ZoneOffset.UTC));
        given(imageRepository.findDeletePending(NOW.minus(Duration.ofMinutes(10)), PageRequest.of(0, 100)))
                .willReturn(List.of(CreatorPostImage.uploading("a", 1L, NOW), CreatorPostImage.uploading("b", 1L, NOW)));
        willThrow(new IllegalStateException("storage down")).given(storageCleaner).delete("a");

        scheduler.cleanUp();

        verify(imageRepository).postponeDeletePending("a", NOW);
        verify(imageRepository, never()).postponeDeletePending("b", NOW);
    }

    @Test
    void 스케줄러는_재시도_시각을_미루지_못해도_나머지를_계속_지운다() {
        PostImageCleanupScheduler scheduler = new PostImageCleanupScheduler(
                imageRepository, storageCleaner, Clock.fixed(NOW, ZoneOffset.UTC));
        given(imageRepository.findDeletePending(NOW.minus(Duration.ofMinutes(10)), PageRequest.of(0, 100)))
                .willReturn(List.of(CreatorPostImage.uploading("a", 1L, NOW), CreatorPostImage.uploading("b", 1L, NOW)));
        willThrow(new IllegalStateException("storage down")).given(storageCleaner).delete("a");
        willThrow(new IllegalStateException("db down")).given(imageRepository).postponeDeletePending("a", NOW);

        scheduler.cleanUp();

        verify(storageCleaner).delete("b");
    }
}

package kr.co.cking.creator.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.domain.CreatorSpaceImageKind;
import kr.co.cking.creator.domain.CreatorSpaceTemplateImage;
import kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus;
import kr.co.cking.creator.repository.CreatorSpaceTemplateImageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 템플릿 이미지 정리 스케줄러(이슈 #598): 참조되지 않는 업로드만 선점하고, 삭제 확정을 먼저 한 행에 대해서만 저장소를 지운다.
 * 확정이 실패하면 그 사이 템플릿 저장이 이미지를 고정한 것이므로 저장소를 지우지 않는다.
 */
class CreatorSpaceTemplateImageCleanupSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 100);

    private final CreatorSpaceTemplateImageRepository repository = mock(CreatorSpaceTemplateImageRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final CreatorSpaceTemplateImageCleanupScheduler scheduler =
            new CreatorSpaceTemplateImageCleanupScheduler(repository, storage, Clock.fixed(NOW, ZoneOffset.UTC));

    private static CreatorSpaceTemplateImage image(String key, CreatorSpaceTemplateImageStatus status) {
        CreatorSpaceTemplateImage image = CreatorSpaceTemplateImage.uploading(key, CreatorSpaceImageKind.BANNER, NOW);
        ReflectionTestUtils.setField(image, "status", status);
        return image;
    }

    private void givenRows(CreatorSpaceTemplateImage... rows) {
        given(repository.findPendingDeletion(NOW.minus(Duration.ofMinutes(10)), FIRST_PAGE)).willReturn(List.of(rows));
    }

    private void givenCommitToDelete(String key) {
        given(repository.markDeleting(key, NOW)).willReturn(1);
    }

    @Test
    void 삭제_대기는_참조를_다시_확인하고_삭제를_확정한_뒤에야_저장소를_지우고_기록을_남긴다() {
        givenRows(image("a", CreatorSpaceTemplateImageStatus.DELETE_PENDING));
        givenCommitToDelete("a");

        scheduler.cleanUp();

        InOrder order = inOrder(repository, storage);
        order.verify(repository).claimStaleUnreferenced(NOW.minus(Duration.ofHours(24)), NOW);
        order.verify(repository).findPendingDeletion(NOW.minus(Duration.ofMinutes(10)), FIRST_PAGE);
        order.verify(repository).restoreIfReferenced("a", NOW);
        order.verify(repository).markDeleting("a", NOW);
        order.verify(storage).delete("a");
        order.verify(repository).markDeleted("a", NOW);
    }

    @Test
    void 삭제_확정에_실패하면_템플릿_저장이_고정한_것이므로_저장소를_지우지_않는다() {
        givenRows(image("a", CreatorSpaceTemplateImageStatus.DELETE_PENDING));
        given(repository.markDeleting("a", NOW)).willReturn(0);

        scheduler.cleanUp();

        verify(storage, never()).delete(any());
        verify(repository, never()).markDeleted(any(), any());
        verify(repository, never()).postponeDeletion(any(), any());
    }

    @Test
    void 삭제_직전에_참조된_것으로_확인되면_확정하지도_지우지도_않는다() {
        givenRows(image("a", CreatorSpaceTemplateImageStatus.DELETE_PENDING), image("b", CreatorSpaceTemplateImageStatus.DELETE_PENDING));
        given(repository.restoreIfReferenced("a", NOW)).willReturn(1);
        givenCommitToDelete("b");

        scheduler.cleanUp();

        verify(repository, never()).markDeleting(eq("a"), any());
        verify(storage, never()).delete("a");
        verify(storage).delete("b");
        verify(repository).markDeleted("b", NOW);
    }

    @Test
    void 이미_삭제를_확정한_행은_참조_확인_없이_저장소_삭제만_재시도한다() {
        givenRows(image("a", CreatorSpaceTemplateImageStatus.DELETING));

        scheduler.cleanUp();

        verify(repository, never()).restoreIfReferenced(any(), any());
        verify(repository, never()).markDeleting(any(), any());
        verify(storage).delete("a");
        verify(repository).markDeleted("a", NOW);
    }

    @Test
    void 한_이미지_삭제가_실패해도_나머지를_계속_지운다() {
        givenRows(image("a", CreatorSpaceTemplateImageStatus.DELETE_PENDING), image("b", CreatorSpaceTemplateImageStatus.DELETE_PENDING));
        givenCommitToDelete("a");
        givenCommitToDelete("b");
        willThrow(new IllegalStateException("storage down")).given(storage).delete("a");

        scheduler.cleanUp();

        verify(storage).delete("b");
        verify(repository).markDeleted("b", NOW);
    }

    @Test
    void 삭제에_실패한_기록은_삭제_확정_상태로_두고_재시도_시각만_미룬다() {
        givenRows(image("a", CreatorSpaceTemplateImageStatus.DELETE_PENDING), image("b", CreatorSpaceTemplateImageStatus.DELETE_PENDING));
        givenCommitToDelete("a");
        givenCommitToDelete("b");
        willThrow(new IllegalStateException("storage down")).given(storage).delete("a");

        scheduler.cleanUp();

        verify(repository).postponeDeletion("a", NOW);
        verify(repository, never()).markDeleted(eq("a"), any());
        verify(repository, never()).postponeDeletion(eq("b"), any());
    }

    @Test
    void 참조_확인_자체가_실패하면_확정하지도_지우지도_않고_재시도를_미룬다() {
        givenRows(image("a", CreatorSpaceTemplateImageStatus.DELETE_PENDING));
        given(repository.restoreIfReferenced("a", NOW)).willThrow(new IllegalStateException("db down"));

        scheduler.cleanUp();

        verify(repository, never()).markDeleting(any(), any());
        verify(storage, never()).delete(any());
        verify(repository).postponeDeletion("a", NOW);
    }

    @Test
    void 정리할_것이_없으면_저장소를_건드리지_않는다() {
        given(repository.findPendingDeletion(NOW.minus(Duration.ofMinutes(10)), FIRST_PAGE)).willReturn(List.of());

        scheduler.cleanUp();

        verify(repository).claimStaleUnreferenced(NOW.minus(Duration.ofHours(24)), NOW);
        verify(storage, never()).delete(any());
    }
}

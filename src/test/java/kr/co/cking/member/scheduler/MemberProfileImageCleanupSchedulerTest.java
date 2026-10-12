package kr.co.cking.member.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import kr.co.cking.member.application.MemberProfileImageStorageCleaner;
import kr.co.cking.member.domain.MemberProfileImage;
import kr.co.cking.member.repository.MemberProfileImageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.PageRequest;

class MemberProfileImageCleanupSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

    private final MemberProfileImageRepository imageRepository = mock(MemberProfileImageRepository.class);
    private final MemberProfileImageStorageCleaner storageCleaner = mock(MemberProfileImageStorageCleaner.class);

    @Test
    void 미연결_업로드를_선점하고_삭제_대기_객체를_정리한다() {
        MemberProfileImageCleanupScheduler scheduler = new MemberProfileImageCleanupScheduler(
                imageRepository, storageCleaner, Clock.fixed(NOW, ZoneOffset.UTC));
        given(imageRepository.findDeletePending(NOW.minus(Duration.ofMinutes(10)), PageRequest.of(0, 100)))
                .willReturn(List.of(
                        MemberProfileImage.uploading("a", 1L, NOW),
                        MemberProfileImage.uploading("b", 1L, NOW)));

        scheduler.cleanUp();

        InOrder order = inOrder(imageRepository, storageCleaner);
        order.verify(imageRepository).claimStaleUnlinked(NOW.minus(Duration.ofHours(24)), NOW);
        order.verify(imageRepository).findDeletePending(NOW.minus(Duration.ofMinutes(10)), PageRequest.of(0, 100));
        order.verify(storageCleaner).delete("a");
        order.verify(storageCleaner).delete("b");
    }

    @Test
    void 한_객체_삭제가_실패해도_다음_객체를_계속_처리하고_실패_항목만_재시도_대기로_돌린다() {
        MemberProfileImageCleanupScheduler scheduler = new MemberProfileImageCleanupScheduler(
                imageRepository, storageCleaner, Clock.fixed(NOW, ZoneOffset.UTC));
        given(imageRepository.findDeletePending(NOW.minus(Duration.ofMinutes(10)), PageRequest.of(0, 100)))
                .willReturn(List.of(
                        MemberProfileImage.uploading("a", 1L, NOW),
                        MemberProfileImage.uploading("b", 1L, NOW)));
        willThrow(new IllegalStateException("storage down")).given(storageCleaner).delete("a");

        scheduler.cleanUp();

        verify(imageRepository).postponeDeletePending("a", NOW);
        verify(storageCleaner).delete("b");
    }
}

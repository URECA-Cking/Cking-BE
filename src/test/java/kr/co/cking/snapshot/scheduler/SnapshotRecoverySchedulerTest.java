package kr.co.cking.snapshot.scheduler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.snapshot.application.OfficialSnapshotService;
import kr.co.cking.snapshot.domain.SnapshotErrorCode;
import kr.co.cking.snapshot.repository.SnapshotRecoveryFailureRepository;
import kr.co.cking.snapshot.repository.SnapshotSourceQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SnapshotRecoverySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-22T03:00:00Z");
    private static final Duration GRACE_PERIOD = Duration.ofMinutes(1);
    private static final int BATCH_SIZE = 100;

    @Mock
    private SnapshotSourceQueryRepository sourceQueryRepository;

    @Mock
    private OfficialSnapshotService officialSnapshotService;

    @Mock
    private SnapshotRecoveryFailureRepository failureRepository;

    private SnapshotRecoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SnapshotRecoveryScheduler(
                sourceQueryRepository,
                failureRepository,
                officialSnapshotService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                GRACE_PERIOD,
                BATCH_SIZE
        );
    }

    @Test
    void CLOSED_후_유예시간이_지난_Snapshot_누락_Event를_복구한다() {
        when(sourceQueryRepository.findMissingOfficialSnapshotEventIds(
                NOW.minus(GRACE_PERIOD), NOW, BATCH_SIZE)).thenReturn(List.of(1L, 2L));

        scheduler.recoverMissingSnapshots();

        InOrder inOrder = inOrder(officialSnapshotService);
        inOrder.verify(officialSnapshotService).createIfAbsent(1L);
        inOrder.verify(officialSnapshotService).createIfAbsent(2L);
        verify(failureRepository).clear(1L);
        verify(failureRepository).clear(2L);
    }

    @Test
    void 한_Event_복구가_실패해도_다음_Event를_계속_처리한다() {
        when(sourceQueryRepository.findMissingOfficialSnapshotEventIds(
                NOW.minus(GRACE_PERIOD), NOW, BATCH_SIZE)).thenReturn(List.of(1L, 2L, 3L));
        doThrow(new BusinessException(SnapshotErrorCode.EVENT_NOT_CLOSED))
                .when(officialSnapshotService).createIfAbsent(2L);

        scheduler.recoverMissingSnapshots();

        InOrder inOrder = inOrder(officialSnapshotService);
        inOrder.verify(officialSnapshotService).createIfAbsent(1L);
        inOrder.verify(officialSnapshotService).createIfAbsent(2L);
        inOrder.verify(officialSnapshotService).createIfAbsent(3L);
        verify(failureRepository).recordFailure(
                2L, NOW, SnapshotErrorCode.EVENT_NOT_CLOSED.code(), SnapshotErrorCode.EVENT_NOT_CLOSED.message());
    }

    @Test
    void 복구_대상이_없으면_Snapshot_서비스를_호출하지_않는다() {
        when(sourceQueryRepository.findMissingOfficialSnapshotEventIds(
                NOW.minus(GRACE_PERIOD), NOW, BATCH_SIZE)).thenReturn(List.of());

        scheduler.recoverMissingSnapshots();

        verify(officialSnapshotService, never()).createIfAbsent(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 시스템_오류는_백오프를_기록하고_다음_Event를_계속_처리한다() {
        when(sourceQueryRepository.findMissingOfficialSnapshotEventIds(
                NOW.minus(GRACE_PERIOD), NOW, BATCH_SIZE)).thenReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("DB 연결 실패"))
                .when(officialSnapshotService).createIfAbsent(1L);

        scheduler.recoverMissingSnapshots();

        verify(failureRepository).recordFailure(1L, NOW, "SYSTEM_ERROR", "DB 연결 실패");
        verify(officialSnapshotService).createIfAbsent(2L);
    }

    @Test
    void 한시간을_초과한_복구_유예시간은_허용하지_않는다() {
        assertThatThrownBy(() -> new SnapshotRecoveryScheduler(
                sourceQueryRepository,
                failureRepository,
                officialSnapshotService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(1).plusSeconds(1),
                BATCH_SIZE
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 일초_미만의_복구_유예시간은_허용하지_않는다() {
        assertThatThrownBy(() -> new SnapshotRecoveryScheduler(
                sourceQueryRepository,
                failureRepository,
                officialSnapshotService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMillis(999),
                BATCH_SIZE
        )).isInstanceOf(IllegalArgumentException.class);
    }
}

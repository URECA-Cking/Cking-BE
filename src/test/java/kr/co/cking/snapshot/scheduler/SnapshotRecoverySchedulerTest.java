package kr.co.cking.snapshot.scheduler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.snapshot.application.OfficialSnapshotService;
import kr.co.cking.snapshot.domain.SnapshotErrorCode;
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

    private SnapshotRecoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SnapshotRecoveryScheduler(
                sourceQueryRepository,
                officialSnapshotService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                GRACE_PERIOD,
                BATCH_SIZE
        );
    }

    @Test
    void CLOSED_후_유예시간이_지난_Snapshot_누락_Event를_복구한다() {
        when(sourceQueryRepository.findMissingOfficialSnapshotEventIds(
                NOW.minus(GRACE_PERIOD), BATCH_SIZE)).thenReturn(List.of(1L, 2L));

        scheduler.recoverMissingSnapshots();

        InOrder inOrder = inOrder(officialSnapshotService);
        inOrder.verify(officialSnapshotService).createIfAbsent(1L);
        inOrder.verify(officialSnapshotService).createIfAbsent(2L);
    }

    @Test
    void 한_Event_복구가_실패해도_다음_Event를_계속_처리한다() {
        when(sourceQueryRepository.findMissingOfficialSnapshotEventIds(
                NOW.minus(GRACE_PERIOD), BATCH_SIZE)).thenReturn(List.of(1L, 2L, 3L));
        doThrow(new BusinessException(SnapshotErrorCode.EVENT_NOT_CLOSED))
                .when(officialSnapshotService).createIfAbsent(2L);

        scheduler.recoverMissingSnapshots();

        InOrder inOrder = inOrder(officialSnapshotService);
        inOrder.verify(officialSnapshotService).createIfAbsent(1L);
        inOrder.verify(officialSnapshotService).createIfAbsent(2L);
        inOrder.verify(officialSnapshotService).createIfAbsent(3L);
    }

    @Test
    void 복구_대상이_없으면_Snapshot_서비스를_호출하지_않는다() {
        when(sourceQueryRepository.findMissingOfficialSnapshotEventIds(
                NOW.minus(GRACE_PERIOD), BATCH_SIZE)).thenReturn(List.of());

        scheduler.recoverMissingSnapshots();

        verify(officialSnapshotService, never()).createIfAbsent(org.mockito.ArgumentMatchers.anyLong());
    }
}

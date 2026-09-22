package kr.co.cking.snapshot.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import kr.co.cking.snapshot.application.OfficialSnapshotService;
import kr.co.cking.snapshot.repository.SnapshotSourceQueryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 정상 마감 직후 생성되지 못한 공식 Snapshot을 주기적으로 복구한다. */
@Slf4j
@Component
public class SnapshotRecoveryScheduler {

    private final SnapshotSourceQueryRepository sourceQueryRepository;
    private final OfficialSnapshotService officialSnapshotService;
    private final Clock clock;
    private final Duration gracePeriod;
    private final int batchSize;

    public SnapshotRecoveryScheduler(
            SnapshotSourceQueryRepository sourceQueryRepository,
            OfficialSnapshotService officialSnapshotService,
            Clock clock,
            @Value("${cking.snapshot.recovery.grace-period:PT1M}") Duration gracePeriod,
            @Value("${cking.snapshot.recovery.batch-size:100}") int batchSize
    ) {
        if (gracePeriod == null || gracePeriod.isZero() || gracePeriod.isNegative()) {
            throw new IllegalArgumentException("Snapshot 복구 유예 시간은 양수여야 합니다.");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Snapshot 복구 배치 크기는 양수여야 합니다.");
        }
        this.sourceQueryRepository = sourceQueryRepository;
        this.officialSnapshotService = officialSnapshotService;
        this.clock = clock;
        this.gracePeriod = gracePeriod;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${cking.snapshot.recovery.interval-ms:60000}",
            initialDelayString = "${cking.snapshot.recovery.interval-ms:60000}"
    )
    public void recoverMissingSnapshots() {
        Instant closedBefore = clock.instant().minus(gracePeriod);
        for (Long eventId : sourceQueryRepository.findMissingOfficialSnapshotEventIds(closedBefore, batchSize)) {
            try {
                officialSnapshotService.createIfAbsent(eventId);
            } catch (RuntimeException exception) {
                log.error("공식 Snapshot 누락 복구에 실패했습니다. eventId={}", eventId, exception);
            }
        }
    }
}

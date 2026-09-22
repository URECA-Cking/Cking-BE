package kr.co.cking.snapshot.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.snapshot.application.OfficialSnapshotService;
import kr.co.cking.snapshot.repository.SnapshotRecoveryFailureRepository;
import kr.co.cking.snapshot.repository.SnapshotSourceQueryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 정상 마감 직후 생성되지 못한 공식 Snapshot을 주기적으로 복구한다. */
@Slf4j
@Component
public class SnapshotRecoveryScheduler {

    private static final Duration MAX_GRACE_PERIOD = Duration.ofHours(1);
    private static final Duration MIN_GRACE_PERIOD = Duration.ofSeconds(1);

    private final SnapshotSourceQueryRepository sourceQueryRepository;
    private final SnapshotRecoveryFailureRepository failureRepository;
    private final OfficialSnapshotService officialSnapshotService;
    private final Clock clock;
    private final Duration gracePeriod;
    private final int batchSize;

    public SnapshotRecoveryScheduler(
            SnapshotSourceQueryRepository sourceQueryRepository,
            SnapshotRecoveryFailureRepository failureRepository,
            OfficialSnapshotService officialSnapshotService,
            Clock clock,
            @Value("${cking.snapshot.recovery.grace-period:PT1M}") Duration gracePeriod,
            @Value("${cking.snapshot.recovery.batch-size:100}") int batchSize
    ) {
        if (gracePeriod == null || gracePeriod.compareTo(MIN_GRACE_PERIOD) < 0
                || gracePeriod.compareTo(MAX_GRACE_PERIOD) > 0) {
            throw new IllegalArgumentException("Snapshot 복구 유예 시간은 1초 이상 1시간 이하여야 합니다.");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Snapshot 복구 배치 크기는 양수여야 합니다.");
        }
        this.sourceQueryRepository = sourceQueryRepository;
        this.failureRepository = failureRepository;
        this.officialSnapshotService = officialSnapshotService;
        this.clock = clock;
        this.gracePeriod = gracePeriod;
        this.batchSize = batchSize;
    }

    @Scheduled(
            scheduler = "snapshotRecoveryTaskScheduler",
            fixedDelayString = "${cking.snapshot.recovery.interval-ms:60000}",
            initialDelayString = "${cking.snapshot.recovery.interval-ms:60000}"
    )
    public void recoverMissingSnapshots() {
        Instant now = clock.instant();
        Instant closedBefore = now.minus(gracePeriod);
        for (Long eventId : sourceQueryRepository.findMissingOfficialSnapshotEventIds(
                closedBefore, now, batchSize)) {
            try {
                officialSnapshotService.createIfAbsent(eventId);
                failureRepository.clear(eventId);
            } catch (BusinessException exception) {
                recordFailure(eventId, now, exception.getErrorCode().code(), exception.getMessage());
                log.warn("공식 Snapshot 복구 업무 오류입니다. eventId={}, code={}",
                        eventId, exception.getErrorCode().code(), exception);
            } catch (IllegalArgumentException exception) {
                recordFailure(eventId, now, "INVALID_RECOVERY_INPUT", exception.getMessage());
                log.error("공식 Snapshot 복구 입력 오류입니다. eventId={}", eventId, exception);
            } catch (RuntimeException exception) {
                recordFailure(eventId, now, "SYSTEM_ERROR", exception.getMessage());
                log.error("공식 Snapshot 복구 시스템 오류입니다. eventId={}", eventId, exception);
            }
        }
    }

    private void recordFailure(Long eventId, Instant failedAt, String failureCode, String failureMessage) {
        try {
            failureRepository.recordFailure(eventId, failedAt, failureCode, failureMessage);
        } catch (RuntimeException exception) {
            log.error("Snapshot 복구 실패 이력을 저장하지 못했습니다. eventId={}", eventId, exception);
        }
    }
}

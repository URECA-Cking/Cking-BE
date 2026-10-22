package kr.co.cking.drawing.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import kr.co.cking.drawing.application.DrawingRetryService;
import kr.co.cking.drawing.application.DrawingFailurePersistenceService;
import kr.co.cking.drawing.domain.DrawAttemptStatus;
import kr.co.cking.drawing.repository.DrawAttemptHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 서버 중단 등으로 장시간 RUNNING에 머문 Drawing을 찾아 일반 Retry 경로로 복구한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DrawingRecoveryScheduler {

    private final DrawAttemptHistoryRepository attemptRepository;
    private final DrawingFailurePersistenceService failurePersistenceService;
    private final DrawingRetryService retryService;
    private final Clock clock;

    @Value("${cking.drawing.recovery-stale-after:PT10M}")
    private Duration staleAfter;

    @Scheduled(fixedDelayString = "${cking.drawing.recovery-interval-ms:60000}")
    public void recoverStaleDrawings() {
        Instant cutoff = clock.instant().minus(staleAfter);
        for (Long drawingId : attemptRepository.findStaleRunningDrawingIds(DrawAttemptStatus.STARTED, cutoff)) {
            try {
                if (failurePersistenceService.markInterrupted(drawingId, cutoff)) {
                    retryService.recover(drawingId);
                }
            } catch (RuntimeException exception) {
                log.error("RUNNING Drawing 복구에 실패했습니다. drawingId={}", drawingId, exception);
            }
        }
    }
}

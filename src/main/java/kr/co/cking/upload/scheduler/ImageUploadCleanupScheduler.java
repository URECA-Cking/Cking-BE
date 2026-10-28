package kr.co.cking.upload.scheduler;

import java.time.Clock;
import java.time.Duration;
import kr.co.cking.upload.repository.ImageUploadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 업로드 기록은 브라우저가 상태를 확인하는 동안(최대 30초)만 쓴다(#784).
 * 원본이 올라오지 않은 PENDING은 1시간 뒤 FAILED로 끝내고, 기록은 원본(raw/) 수명 주기와 같은 1일 뒤 지운다.
 * 1시간: 주소 유효 5분 + Lambda 재시도 최대 18분(보이지 않는 시간 6분 × 3번)보다 넉넉하게.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("!'${cking.image-pipeline.results-queue-url:}'.isEmpty()")
public class ImageUploadCleanupScheduler {

    static final Duration PENDING_EXPIRY = Duration.ofHours(1);
    static final Duration RETENTION = Duration.ofDays(1);
    static final int DELETE_BATCH = 1000;
    private static final int MAX_BATCHES = 10;

    private final ImageUploadRepository repository;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${cking.image-pipeline.cleanup-interval-ms:600000}")
    public void cleanUp() {
        var now = clock.instant();
        repository.expirePending(now.minus(PENDING_EXPIRY), now);
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            if (repository.deleteCreatedBefore(now.minus(RETENTION), DELETE_BATCH) < DELETE_BATCH) {
                return;
            }
        }
    }
}

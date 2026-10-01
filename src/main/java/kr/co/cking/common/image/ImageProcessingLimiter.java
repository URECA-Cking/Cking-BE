package kr.co.cking.common.image;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;

/** 메모리를 많이 쓰는 이미지 처리를 서버 전체에서 동시에 정해진 수까지만 실행한다. */
@Slf4j
public class ImageProcessingLimiter {

    private final Semaphore permits;
    private final Duration acquireTimeout;

    public ImageProcessingLimiter(int maxConcurrent, Duration acquireTimeout) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be positive: " + maxConcurrent);
        }
        this.permits = new Semaphore(maxConcurrent, true);
        this.acquireTimeout = Objects.requireNonNull(acquireTimeout, "acquireTimeout");
    }

    /** @throws BusinessException 대기 시간 안에 자리가 나지 않은 경우 ({@code IMAGE_PROCESSING_BUSY}) */
    public <T> T run(Supplier<T> task) {
        long waitStart = System.nanoTime();
        acquire();
        long workStart = System.nanoTime();
        try {
            return task.get();
        } finally {
            permits.release();
            log.info("image processing: waited={}ms, took={}ms",
                    TimeUnit.NANOSECONDS.toMillis(workStart - waitStart),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - workStart));
        }
    }

    private void acquire() {
        try {
            if (!permits.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new BusinessException(CommonErrorCode.IMAGE_PROCESSING_BUSY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(CommonErrorCode.IMAGE_PROCESSING_BUSY);
        }
    }
}

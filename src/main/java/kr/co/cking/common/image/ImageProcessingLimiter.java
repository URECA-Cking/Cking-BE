package kr.co.cking.common.image;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;

/**
 * 메모리를 많이 쓰는 이미지 처리를 서버 전체에서 제한한다.
 *
 * <p>입장 한도는 원본을 heap에 올린 요청 수(처리 중 + 대기 중)를, 처리 한도는 동시에 정규화하는 수를 묶는다.
 * 항상 입장 → 처리 순서로 잡는다.
 */
@Slf4j
public class ImageProcessingLimiter {

    private final Semaphore admission;
    private final Semaphore permits;
    private final Duration acquireTimeout;

    public ImageProcessingLimiter(int maxConcurrent, int maxWaiting, Duration acquireTimeout) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be positive: " + maxConcurrent);
        }
        if (maxWaiting < 0) {
            throw new IllegalArgumentException("maxWaiting must not be negative: " + maxWaiting);
        }
        this.admission = new Semaphore(maxConcurrent + maxWaiting);
        this.permits = new Semaphore(maxConcurrent, true);
        this.acquireTimeout = Objects.requireNonNull(acquireTimeout, "acquireTimeout");
    }

    /**
     * 업로드 원본을 heap에 올리기 전에 호출한다. 자리가 없으면 기다리지 않는다.
     *
     * @throws BusinessException 입장 자리가 없는 경우 ({@code IMAGE_PROCESSING_BUSY})
     */
    public <T> T admit(Supplier<T> task) {
        if (!admission.tryAcquire()) {
            throw new BusinessException(CommonErrorCode.IMAGE_PROCESSING_BUSY);
        }
        try {
            return task.get();
        } finally {
            admission.release();
        }
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

package kr.co.cking.stream.application.config;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ErrorHandler;

/**
 * 오류가 나도 취소되지 않는 Stream 구독의 오류 처리기. 폴링 스레드에서 불린다.
 * <ul>
 *   <li>컨테이너가 멈추는 중(앱 종료, 테스트 컨텍스트 일시정지)이면 기다리지 않는다. 기다리면 그 사이 재시작이
 *   이 폴링 작업을 살아 있는 것으로 보고 새로 띄우지 않는다.</li>
 *   <li>Consumer Group이 없으면(NOGROUP) 다시 만든다. 기동 때 한 번 만든 그룹은 Redis 데이터가 사라지면 함께 사라진다.</li>
 *   <li>그 밖의 오류는 다음 읽기 전에 쉬어, Redis가 끊긴 동안 재시도와 로그가 몰리지 않게 한다.</li>
 * </ul>
 */
@Slf4j
final class KeepAliveErrorHandler implements ErrorHandler {

    private static final Duration CHECK_INTERVAL = Duration.ofMillis(10);
    private static final Duration FULL_LOG_INTERVAL = Duration.ofMinutes(1);

    private final BooleanSupplier containerRunning;
    private final Runnable ensureConsumerGroup;
    private final Duration retryDelay;
    private Long lastFullLogAt;

    KeepAliveErrorHandler(BooleanSupplier containerRunning, Runnable ensureConsumerGroup, Duration retryDelay) {
        this.containerRunning = containerRunning;
        this.ensureConsumerGroup = ensureConsumerGroup;
        this.retryDelay = retryDelay;
    }

    @Override
    public void handleError(Throwable throwable) {
        if (!containerRunning.getAsBoolean()) {
            log.debug("Redis Stream 폴링이 멈추는 중에 오류가 발생했습니다.", throwable);
            return;
        }
        if (isNoGroup(throwable)) {
            recreateConsumerGroup();
            return;
        }
        logError(throwable);
        pauseBeforeNextPoll();
    }

    private void recreateConsumerGroup() {
        log.warn("Redis Stream Consumer Group이 없어 다시 만듭니다.");
        try {
            ensureConsumerGroup.run();
        } catch (RuntimeException e) {
            logError(e);
            pauseBeforeNextPoll();
        }
    }

    /** 같은 오류가 이어지면 스택트레이스는 1분에 한 번만 남긴다. */
    private void logError(Throwable throwable) {
        long now = System.nanoTime();
        if (lastFullLogAt == null || now - lastFullLogAt >= FULL_LOG_INTERVAL.toNanos()) {
            lastFullLogAt = now;
            log.error("Redis Stream 리스너 처리 중 예상하지 못한 오류가 발생했습니다.", throwable);
        } else {
            log.error("Redis Stream 리스너 처리 중 예상하지 못한 오류가 이어지고 있습니다: {}", throwable.toString());
        }
    }

    /** 쉬는 동안 컨테이너가 멈추면 바로 끝낸다. 확인 간격만큼 정지를 늦게 알아챈다. */
    private void pauseBeforeNextPoll() {
        long deadline = System.nanoTime() + retryDelay.toNanos();
        while (containerRunning.getAsBoolean()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            try {
                Thread.sleep(Duration.ofNanos(Math.min(remaining, CHECK_INTERVAL.toNanos())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean isNoGroup(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("NOGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

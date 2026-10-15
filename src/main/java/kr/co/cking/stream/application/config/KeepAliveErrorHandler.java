package kr.co.cking.stream.application.config;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import io.lettuce.core.RedisException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ErrorHandler;

/**
 * 오류가 나도 취소되지 않는 Stream 구독의 오류 처리기. 폴링 스레드에서 불린다.
 * <ul>
 *   <li>컨테이너가 멈추는 중(앱 종료, 테스트 컨텍스트 일시정지)이면 기다리지 않는다. 기다리면 그 사이 재시작이
 *   이 폴링 작업을 살아 있는 것으로 보고 새로 띄우지 않는다. 컨테이너는 폴링 스레드를 기다리지 않고 멈추므로
 *   진행 중이던 읽기가 연결 종료로 끊기는 것은 매번 생기는 일이라 ERROR로 남기지 않는다.</li>
 *   <li>Consumer Group이 없으면(NOGROUP) 다시 만든다. 기동 때 한 번 만든 그룹은 Redis 데이터가 사라지면 함께 사라진다.</li>
 *   <li>그 밖의 오류는 다음 읽기 전에 쉬어, Redis가 끊긴 동안 재시도가 몰리지 않게 한다.</li>
 *   <li>같은 종류의 로그는 간격마다 한 번만 남기고, 그 사이 건너뛴 횟수를 다음 로그에 붙인다.</li>
 * </ul>
 */
@Slf4j
final class KeepAliveErrorHandler implements ErrorHandler {

    private static final Duration CHECK_INTERVAL = Duration.ofMillis(10);

    private final BooleanSupplier containerRunning;
    private final Runnable ensureConsumerGroup;
    private final Duration retryDelay;
    private final RepeatedLog errorLog;
    private final RepeatedLog noGroupLog;

    KeepAliveErrorHandler(BooleanSupplier containerRunning, Runnable ensureConsumerGroup, Duration retryDelay,
            Duration logInterval) {
        this.containerRunning = containerRunning;
        this.ensureConsumerGroup = ensureConsumerGroup;
        this.retryDelay = retryDelay;
        this.errorLog = new RepeatedLog(logInterval);
        this.noGroupLog = new RepeatedLog(logInterval);
    }

    @Override
    public void handleError(Throwable throwable) {
        if (!containerRunning.getAsBoolean()) {
            logWhileStopping(throwable);
            return;
        }
        if (isNoGroup(throwable)) {
            recreateConsumerGroup();
        } else {
            logError(throwable);
        }
        pauseBeforeNextPoll();
    }

    private void logWhileStopping(Throwable throwable) {
        if (isConnectionClosed(throwable)) {
            log.debug("Redis Stream 폴링이 멈추는 중에 연결이 닫혔습니다.", throwable);
            return;
        }
        log.error("Redis Stream 폴링이 멈추는 중에 예상하지 못한 오류가 발생했습니다.", throwable);
    }

    private void recreateConsumerGroup() {
        long skipped = noGroupLog.skippedSinceLastLog();
        if (skipped == 0) {
            log.warn("Redis Stream Consumer Group이 없어 다시 만듭니다.");
        } else if (skipped > 0) {
            log.warn("Redis Stream Consumer Group이 없어 다시 만듭니다. 직전 로그 뒤 {}번 더 다시 만들었습니다.", skipped);
        }
        try {
            ensureConsumerGroup.run();
        } catch (RuntimeException e) {
            logError(e);
        }
    }

    private void logError(Throwable throwable) {
        long skipped = errorLog.skippedSinceLastLog();
        if (skipped == 0) {
            log.error("Redis Stream 리스너 처리 중 예상하지 못한 오류가 발생했습니다.", throwable);
        } else if (skipped > 0) {
            log.error("Redis Stream 리스너 처리 중 예상하지 못한 오류가 이어지고 있습니다. 직전 로그 뒤 {}번 더 발생했습니다.",
                    skipped, throwable);
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

    private static boolean isConnectionClosed(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RedisException && current.getMessage() != null
                    && (current.getMessage().contains("Connection closed")
                    || current.getMessage().contains("Connection is already closed"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 같은 로그가 이어지면 간격마다 한 번만 남기도록 건너뛴 횟수를 센다. */
    private static final class RepeatedLog {

        private final long intervalNanos;
        private Long lastLoggedAt;
        private long skipped;

        private RepeatedLog(Duration interval) {
            this.intervalNanos = interval.toNanos();
        }

        /** 남길 차례면 그동안 건너뛴 횟수를, 건너뛸 차례면 -1을 돌려준다. */
        long skippedSinceLastLog() {
            long now = System.nanoTime();
            if (lastLoggedAt != null && now - lastLoggedAt < intervalNanos) {
                skipped++;
                return -1;
            }
            lastLoggedAt = now;
            long result = skipped;
            skipped = 0;
            return result;
        }
    }
}

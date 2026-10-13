package kr.co.cking.stream.application.config;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import io.lettuce.core.RedisException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ErrorHandler;

@Slf4j
public class StreamListenerErrorHandler implements ErrorHandler {

    private static final Duration CHECK_INTERVAL = Duration.ofMillis(10);

    private final ApplicationShutdownState shutdownState;
    private final BooleanSupplier containerRunning;
    private final Duration retryDelay;

    StreamListenerErrorHandler(
            ApplicationShutdownState shutdownState,
            BooleanSupplier containerRunning,
            Duration retryDelay
    ) {
        this.shutdownState = shutdownState;
        this.containerRunning = containerRunning;
        this.retryDelay = retryDelay;
    }

    @Override
    public void handleError(Throwable throwable) {
        if (isStopping()) {
            // 컨테이너가 멈출 때(종료, 테스트 컨텍스트 일시정지)는 연결이 닫히는 것이 정상이고,
            // 대기하면 그 사이 재시작이 이 폴링 작업을 살아 있는 것으로 보고 새로 띄우지 않는다.
            if (isConnectionClosed(throwable)) {
                log.debug("Redis Stream 폴링이 멈추는 중에 연결이 닫혔습니다.", throwable);
            } else {
                log.error("Redis Stream 리스너 처리 중 예상하지 못한 오류가 발생했습니다.", throwable);
            }
            return;
        }

        log.error("Redis Stream 리스너 처리 중 예상하지 못한 오류가 발생했습니다.", throwable);
        pauseBeforeNextPoll();
    }

    private boolean isStopping() {
        return shutdownState.isShuttingDown() || !containerRunning.getAsBoolean();
    }

    /** 구독을 유지하므로 Redis가 끊긴 동안 오류가 반복된다. 쉬는 동안 컨테이너가 멈추면 바로 끝낸다. */
    private void pauseBeforeNextPoll() {
        long deadline = System.nanoTime() + retryDelay.toNanos();
        while (!isStopping()) {
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

    private boolean isConnectionClosed(Throwable throwable) {
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
}

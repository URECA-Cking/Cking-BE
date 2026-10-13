package kr.co.cking.stream.application.config;

import java.time.Duration;

import io.lettuce.core.RedisException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ErrorHandler;

@Slf4j
public class StreamListenerErrorHandler implements ErrorHandler {

    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private final ApplicationShutdownState shutdownState;
    private final Duration retryDelay;

    public StreamListenerErrorHandler(ApplicationShutdownState shutdownState) {
        this(shutdownState, RETRY_DELAY);
    }

    StreamListenerErrorHandler(ApplicationShutdownState shutdownState, Duration retryDelay) {
        this.shutdownState = shutdownState;
        this.retryDelay = retryDelay;
    }

    @Override
    public void handleError(Throwable throwable) {
        if (shutdownState.isShuttingDown() && isConnectionClosed(throwable)) {
            log.debug("애플리케이션 종료 중 Redis Stream 폴링 연결이 닫혔습니다.", throwable);
            return;
        }

        log.error("Redis Stream 리스너 처리 중 예상하지 못한 오류가 발생했습니다.", throwable);
        if (!shutdownState.isShuttingDown()) {
            pauseBeforeNextPoll();
        }
    }

    /** 구독을 유지하므로 Redis가 끊긴 동안 오류가 반복된다. 폴링 스레드에서 쉬어 재시도와 로그가 몰리지 않게 한다. */
    private void pauseBeforeNextPoll() {
        try {
            Thread.sleep(retryDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

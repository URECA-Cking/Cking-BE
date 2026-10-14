package kr.co.cking.stream.application.config;

import io.lettuce.core.RedisException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ErrorHandler;

@Slf4j
@RequiredArgsConstructor
public class StreamListenerErrorHandler implements ErrorHandler {

    private final ApplicationShutdownState shutdownState;

    @Override
    public void handleError(Throwable throwable) {
        if (shutdownState.isShuttingDown() && isConnectionClosed(throwable)) {
            log.debug("애플리케이션 종료 중 Redis Stream 폴링 연결이 닫혔습니다.", throwable);
            return;
        }

        log.error("Redis Stream 리스너 처리 중 예상하지 못한 오류가 발생했습니다.", throwable);
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

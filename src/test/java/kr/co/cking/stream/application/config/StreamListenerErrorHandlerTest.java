package kr.co.cking.stream.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.lettuce.core.RedisException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.slf4j.LoggerFactory;

class StreamListenerErrorHandlerTest {

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(StreamListenerErrorHandler.class)).addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(StreamListenerErrorHandler.class)).detachAppender(logAppender);
    }

    @Test
    void 종료_중_연결_종료는_ERROR로_기록하지_않는다() {
        ApplicationShutdownState shutdownState = mock(ApplicationShutdownState.class);
        when(shutdownState.isShuttingDown()).thenReturn(true);
        StreamListenerErrorHandler errorHandler = new StreamListenerErrorHandler(shutdownState);

        errorHandler.handleError(new RedisException("Connection closed"));

        assertThat(logAppender.list)
                .noneMatch(event -> event.getLevel().equals(Level.ERROR));
    }

    @Test
    void 정상_실행_중_연결_종료는_ERROR로_기록한다() {
        ApplicationShutdownState shutdownState = mock(ApplicationShutdownState.class);
        when(shutdownState.isShuttingDown()).thenReturn(false);
        StreamListenerErrorHandler errorHandler = new StreamListenerErrorHandler(shutdownState, Duration.ZERO);

        errorHandler.handleError(new RedisException("Connection closed"));

        assertThat(logAppender.list)
                .anyMatch(event -> event.getLevel().equals(Level.ERROR));
    }

    @Test
    void 정상_실행_중_오류_뒤에는_다음_폴링_전에_쉰다() {
        ApplicationShutdownState shutdownState = mock(ApplicationShutdownState.class);
        when(shutdownState.isShuttingDown()).thenReturn(false);
        StreamListenerErrorHandler errorHandler = new StreamListenerErrorHandler(shutdownState, Duration.ofMillis(200));

        long startedAt = System.nanoTime();
        errorHandler.handleError(new RedisException("Command timed out"));

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
    }

    @Test
    void 종료_중에는_오류_뒤에도_쉬지_않는다() {
        ApplicationShutdownState shutdownState = mock(ApplicationShutdownState.class);
        when(shutdownState.isShuttingDown()).thenReturn(true);
        StreamListenerErrorHandler errorHandler = new StreamListenerErrorHandler(shutdownState, Duration.ofSeconds(5));

        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> errorHandler.handleError(new RedisException("Command timed out")));
    }

    @Test
    void 컨텍스트_종료_이벤트를_받으면_종료_상태가_된다() {
        ApplicationShutdownState shutdownState = new ApplicationShutdownState();

        shutdownState.onApplicationEvent(new ContextClosedEvent(mock(ConfigurableApplicationContext.class)));

        assertThat(shutdownState.isShuttingDown()).isTrue();
    }
}

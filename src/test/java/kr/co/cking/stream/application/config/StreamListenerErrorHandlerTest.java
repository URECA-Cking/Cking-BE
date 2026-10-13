package kr.co.cking.stream.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
        StreamListenerErrorHandler errorHandler =
                new StreamListenerErrorHandler(shutdownState(true), () -> true, Duration.ZERO);

        errorHandler.handleError(new RedisException("Connection closed"));

        assertThat(logAppender.list)
                .noneMatch(event -> event.getLevel().equals(Level.ERROR));
    }

    @Test
    void 컨테이너가_멈춘_뒤의_연결_종료는_ERROR로_기록하지_않는다() {
        StreamListenerErrorHandler errorHandler =
                new StreamListenerErrorHandler(shutdownState(false), () -> false, Duration.ZERO);

        errorHandler.handleError(new RedisException("Connection is already closed"));

        assertThat(logAppender.list)
                .noneMatch(event -> event.getLevel().equals(Level.ERROR));
    }

    @Test
    void 정상_실행_중_연결_종료는_ERROR로_기록한다() {
        StreamListenerErrorHandler errorHandler =
                new StreamListenerErrorHandler(shutdownState(false), () -> true, Duration.ZERO);

        errorHandler.handleError(new RedisException("Connection closed"));

        assertThat(logAppender.list)
                .anyMatch(event -> event.getLevel().equals(Level.ERROR));
    }

    @Test
    void 정상_실행_중_오류_뒤에는_다음_폴링_전에_쉰다() {
        StreamListenerErrorHandler errorHandler =
                new StreamListenerErrorHandler(shutdownState(false), () -> true, Duration.ofMillis(200));

        long startedAt = System.nanoTime();
        errorHandler.handleError(new RedisException("Command timed out"));

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
    }

    @Test
    void 종료_중에는_오류_뒤에도_쉬지_않는다() {
        StreamListenerErrorHandler errorHandler =
                new StreamListenerErrorHandler(shutdownState(true), () -> true, Duration.ofSeconds(5));

        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> errorHandler.handleError(new RedisException("Command timed out")));
    }

    @Test
    void 컨테이너가_멈춘_뒤에는_오류_뒤에도_쉬지_않는다() {
        StreamListenerErrorHandler errorHandler =
                new StreamListenerErrorHandler(shutdownState(false), () -> false, Duration.ofSeconds(5));

        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> errorHandler.handleError(new RedisException("Command timed out")));
    }

    @Test
    void 쉬는_도중_컨테이너가_멈추면_바로_끝낸다() {
        AtomicBoolean running = new AtomicBoolean(true);
        StreamListenerErrorHandler errorHandler =
                new StreamListenerErrorHandler(shutdownState(false), running::get, Duration.ofSeconds(5));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            scheduler.schedule(() -> running.set(false), 100, TimeUnit.MILLISECONDS);

            assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> errorHandler.handleError(new RedisException("Command timed out")));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void 컨텍스트_종료_이벤트를_받으면_종료_상태가_된다() {
        ApplicationShutdownState shutdownState = new ApplicationShutdownState();

        shutdownState.onApplicationEvent(new ContextClosedEvent(mock(ConfigurableApplicationContext.class)));

        assertThat(shutdownState.isShuttingDown()).isTrue();
    }

    private static ApplicationShutdownState shutdownState(boolean shuttingDown) {
        ApplicationShutdownState shutdownState = mock(ApplicationShutdownState.class);
        when(shutdownState.isShuttingDown()).thenReturn(shuttingDown);
        return shutdownState;
    }
}

package kr.co.cking.stream.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisSystemException;

class KeepAliveErrorHandlerTest {

    private static final Duration LOG_INTERVAL = Duration.ofMinutes(1);

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(KeepAliveErrorHandler.class)).addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(KeepAliveErrorHandler.class)).detachAppender(logAppender);
    }

    @Test
    void 정상_실행_중에는_연결이_닫혀도_ERROR로_남기고_다음_폴링_전에_쉰다() {
        KeepAliveErrorHandler errorHandler =
                new KeepAliveErrorHandler(() -> true, () -> { }, Duration.ofMillis(200), LOG_INTERVAL);

        long startedAt = System.nanoTime();
        errorHandler.handleError(new RedisException("Connection closed"));

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
        assertThat(logAppender.list).anyMatch(event -> event.getLevel().equals(Level.ERROR));
    }

    @Test
    void 멈추는_중_연결_종료는_쉬지_않고_ERROR로_남기지_않는다() {
        KeepAliveErrorHandler errorHandler =
                new KeepAliveErrorHandler(() -> false, () -> { }, Duration.ofSeconds(5), LOG_INTERVAL);

        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> errorHandler.handleError(new RedisException("Connection closed")));
        assertThat(logAppender.list).noneMatch(event -> event.getLevel().equals(Level.ERROR));
    }

    @Test
    void 멈추는_중이라도_연결_종료가_아닌_오류는_쉬지_않고_ERROR로_남긴다() {
        KeepAliveErrorHandler errorHandler =
                new KeepAliveErrorHandler(() -> false, () -> { }, Duration.ofSeconds(5), LOG_INTERVAL);

        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> errorHandler.handleError(new RedisException("Command timed out")));
        assertThat(logAppender.list).anyMatch(event -> event.getLevel().equals(Level.ERROR));
    }

    @Test
    void 쉬는_도중_컨테이너가_멈추면_바로_끝낸다() {
        AtomicBoolean running = new AtomicBoolean(true);
        KeepAliveErrorHandler errorHandler =
                new KeepAliveErrorHandler(running::get, () -> { }, Duration.ofSeconds(5), LOG_INTERVAL);
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
    void Consumer_Group이_없으면_다시_만들고_다음_폴링_전에_쉰다() {
        AtomicInteger recreated = new AtomicInteger();
        KeepAliveErrorHandler errorHandler =
                new KeepAliveErrorHandler(() -> true, recreated::incrementAndGet, Duration.ofMillis(200), LOG_INTERVAL);

        long startedAt = System.nanoTime();
        errorHandler.handleError(noGroup());

        assertThat(recreated).hasValue(1);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
    }

    @Test
    void 같은_오류가_이어지면_간격_안에서는_한_번만_남긴다() {
        KeepAliveErrorHandler errorHandler =
                new KeepAliveErrorHandler(() -> true, () -> { }, Duration.ZERO, LOG_INTERVAL);

        errorHandler.handleError(new RedisException("Command timed out"));
        errorHandler.handleError(new RedisException("Command timed out"));
        errorHandler.handleError(new RedisException("Command timed out"));

        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getThrowableProxy()).isNotNull();
    }

    @Test
    void 간격이_지나면_건너뛴_횟수와_함께_다시_남긴다() throws InterruptedException {
        KeepAliveErrorHandler errorHandler =
                new KeepAliveErrorHandler(() -> true, () -> { }, Duration.ZERO, Duration.ofMillis(100));

        errorHandler.handleError(new RedisException("Command timed out"));
        errorHandler.handleError(new RedisException("Command timed out"));
        errorHandler.handleError(new RedisException("Command timed out"));
        Thread.sleep(150);
        errorHandler.handleError(new RedisException("Command timed out"));

        assertThat(logAppender.list).hasSize(2);
        assertThat(logAppender.list.get(1).getFormattedMessage()).contains("2번 더");
        assertThat(logAppender.list.get(1).getThrowableProxy()).isNotNull();
    }

    @Test
    void Consumer_Group_재생성이_이어지면_간격_안에서는_한_번만_남긴다() {
        AtomicInteger recreated = new AtomicInteger();
        KeepAliveErrorHandler errorHandler =
                new KeepAliveErrorHandler(() -> true, recreated::incrementAndGet, Duration.ZERO, LOG_INTERVAL);

        errorHandler.handleError(noGroup());
        errorHandler.handleError(noGroup());
        errorHandler.handleError(noGroup());

        assertThat(recreated).hasValue(3);
        assertThat(logAppender.list).filteredOn(event -> event.getLevel().equals(Level.WARN)).hasSize(1);
    }

    private static RedisSystemException noGroup() {
        return new RedisSystemException("Error in execution",
                new RedisCommandExecutionException("NOGROUP No such key 'stream' or consumer group 'group'"));
    }
}

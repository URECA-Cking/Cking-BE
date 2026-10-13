package kr.co.cking.stream.application.config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;

/**
 * Stream 소비자 컨테이너를 만든다. 오류 처리기가 컨테이너의 실행 여부를 알아야 정지 중에는 대기하지 않으므로,
 * 컨테이너를 만든 뒤 오류 처리기에 연결한다.
 */
final class StreamListenerContainers {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private StreamListenerContainers() {
    }

    static StreamMessageListenerContainer<String, MapRecord<String, String, String>> create(
            RedisConnectionFactory connectionFactory,
            ApplicationShutdownState shutdownState
    ) {
        return create(connectionFactory, shutdownState, POLL_TIMEOUT, RETRY_DELAY);
    }

    static StreamMessageListenerContainer<String, MapRecord<String, String, String>> create(
            RedisConnectionFactory connectionFactory,
            ApplicationShutdownState shutdownState,
            Duration pollTimeout,
            Duration retryDelay
    ) {
        AtomicReference<StreamMessageListenerContainer<String, MapRecord<String, String, String>>> self =
                new AtomicReference<>();
        StreamListenerErrorHandler errorHandler = new StreamListenerErrorHandler(
                shutdownState,
                () -> self.get() != null && self.get().isRunning(),
                retryDelay);

        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(pollTimeout)
                        .errorHandler(errorHandler)
                        .build();
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);
        self.set(container);
        return container;
    }
}

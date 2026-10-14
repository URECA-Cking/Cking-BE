package kr.co.cking.stream.application.config;

import java.time.Duration;

import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;
import org.springframework.util.ErrorHandler;

/**
 * Consumer Group 구독 요청을 만든다.
 * Spring Data Redis 기본값은 읽기 오류 한 번에 구독을 취소해 재시작 전까지 소비가 멈추므로(이슈 #555),
 * 오류가 나도 구독을 유지하고 이 구독 전용 오류 처리기({@link KeepAliveErrorHandler})로 복구한다.
 */
final class StreamReadRequests {

    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private StreamReadRequests() {
    }

    static StreamReadRequest<String> keepAlive(
            StreamMessageListenerContainer<?, ?> container,
            String streamKey,
            String consumerGroup,
            String consumerName,
            Runnable ensureConsumerGroup
    ) {
        return keepAlive(streamKey, consumerGroup, consumerName,
                new KeepAliveErrorHandler(container::isRunning, ensureConsumerGroup, RETRY_DELAY));
    }

    static StreamReadRequest<String> keepAlive(
            String streamKey,
            String consumerGroup,
            String consumerName,
            ErrorHandler errorHandler
    ) {
        return StreamReadRequest.builder(StreamOffset.create(streamKey, ReadOffset.lastConsumed()))
                .consumer(Consumer.from(consumerGroup, consumerName))
                .autoAcknowledge(false)
                .cancelOnError(error -> false)
                .errorHandler(errorHandler)
                .build();
    }
}

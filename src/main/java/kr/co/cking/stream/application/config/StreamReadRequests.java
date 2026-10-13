package kr.co.cking.stream.application.config;

import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;

/**
 * Consumer Group 구독 요청을 만든다.
 * Spring Data Redis 기본값은 읽기 오류 한 번에 구독을 취소해 재시작 전까지 소비가 멈추므로(이슈 #555),
 * 오류가 나도 구독을 유지한다. 반복되는 오류 사이의 대기는 {@link StreamListenerErrorHandler}가 맡는다.
 */
final class StreamReadRequests {

    private StreamReadRequests() {
    }

    static StreamReadRequest<String> keepAlive(String streamKey, String consumerGroup, String consumerName) {
        return StreamReadRequest.builder(StreamOffset.create(streamKey, ReadOffset.lastConsumed()))
                .consumer(Consumer.from(consumerGroup, consumerName))
                .autoAcknowledge(false)
                .cancelOnError(error -> false)
                .build();
    }
}

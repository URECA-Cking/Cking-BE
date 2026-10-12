package kr.co.cking.stream.presentation;

import kr.co.cking.ticket.application.TicketEarnEventDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class EarnStreamListenerTest {

    private final TicketEarnEventDispatcher dispatcher = mock(TicketEarnEventDispatcher.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
    private final EarnStreamListener listener = new EarnStreamListener(dispatcher, redisTemplate, "stream:test",
            "cg:test");
    private final Map<String, String> fields = Map.of("sourceType", "QUIZ", "payloadVersion", "1");
    private final MapRecord<String, String, String> message =
            StreamRecords.newRecord().in("stream:test").withId(RecordId.of("1-0")).ofMap(fields);

    @Test
    void 모든_메시지를_공통_분기로_반영하고_반영한_뒤에만_XACK한다() {
        given(redisTemplate.opsForStream()).willReturn(streamOperations);

        assertThat(listener.process(message)).isTrue();

        verify(dispatcher).apply(fields);
        verify(streamOperations).acknowledge("stream:test", "cg:test", RecordId.of("1-0"));
    }

    @Test
    void 반영하지_못하거나_거부한_메시지는_XACK하지_않고_PEL에_남긴다() {
        given(redisTemplate.opsForStream()).willReturn(streamOperations);
        willThrow(new IllegalArgumentException("unknown source")).given(dispatcher).apply(fields);

        assertThat(listener.process(message)).isFalse();

        verify(streamOperations, never()).acknowledge(anyString(), anyString(), any(RecordId[].class));
    }
}

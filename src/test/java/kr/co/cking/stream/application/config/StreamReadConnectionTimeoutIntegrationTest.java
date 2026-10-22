package kr.co.cking.stream.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

/** 실제 설정에서 공용 연결은 1초에 명령을 끊고, Stream 읽기 전용 연결은 2초 BLOCK 읽기를 끝까지 기다리는지 검증한다. */
@SpringBootTest
class StreamReadConnectionTimeoutIntegrationTest {

    @Autowired private LettuceConnectionFactory sharedFactory;
    @Autowired private StreamReadConnectionFactory streamReadConnectionFactory;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private java.util.List<StreamMessageListenerContainer<?, ?>> containers;

    private final String streamKey = "test:stream-read-timeout:" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(streamKey);
    }

    @Test
    void 공용_연결은_1초에_끊고_Stream_전용_연결은_2초_BLOCK_읽기를_기다린다() {
        redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), "g");
        assertThat(sharedFactory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofSeconds(1));

        long sharedStart = System.nanoTime();
        assertThatThrownBy(() -> blockingRead(sharedFactory)).isInstanceOf(QueryTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - sharedStart)).isLessThan(Duration.ofMillis(1900));

        long streamStart = System.nanoTime();
        assertThat(blockingRead(streamReadConnectionFactory.get())).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - streamStart)).isGreaterThanOrEqualTo(Duration.ofMillis(1900));
    }

    @Test
    void 앱의_Stream_소비자는_모두_전용_연결로_읽는다() {
        assertThat(containers).hasSize(3);
        for (StreamMessageListenerContainer<?, ?> container : containers) {
            RedisTemplate<?, ?> template = (RedisTemplate<?, ?>) ReflectionTestUtils.getField(container, "template");
            assertThat(template.getConnectionFactory()).isSameAs(streamReadConnectionFactory.get());
        }
    }

    private java.util.List<?> blockingRead(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        return template.opsForStream().read(Consumer.from("g", "c"),
                StreamReadOptions.empty().block(Duration.ofSeconds(2)), StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
    }
}

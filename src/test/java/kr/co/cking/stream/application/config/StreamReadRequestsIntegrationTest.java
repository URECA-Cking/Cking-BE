package kr.co.cking.stream.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.data.redis.stream.Subscription;

/**
 * Consumer Group을 지워 XREADGROUP이 NOGROUP으로 실패하게 만든 뒤,
 * 기본 구독은 취소되고 keepAlive 구독은 그룹이 다시 생기면 이어서 소비하는지 확인한다(이슈 #555).
 */
@SpringBootTest
class StreamReadRequestsIntegrationTest {

    private static final String STREAM_KEY = "stream:keep-alive-test";
    private static final String CONSUMER_GROUP = "cg:keep-alive-test";
    private static final Duration WAIT = Duration.ofSeconds(5);

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    private final AtomicInteger errors = new AtomicInteger();
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    @BeforeEach
    void setUp() {
        redisTemplate.delete(STREAM_KEY);
        createGroup();

        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofMillis(100))
                        .errorHandler(error -> {
                            errors.incrementAndGet();
                            sleep(Duration.ofMillis(20));
                        })
                        .build();
        container = StreamMessageListenerContainer.create(connectionFactory, options);
        container.start();
    }

    @AfterEach
    void tearDown() {
        container.stop();
        redisTemplate.delete(STREAM_KEY);
    }

    @Test
    void keepAlive_구독은_읽기_오류_뒤에도_유지되어_다시_소비한다() throws InterruptedException {
        Subscription subscription = container.register(
                StreamReadRequests.keepAlive(STREAM_KEY, CONSUMER_GROUP, "keep-alive-consumer"),
                message -> received.add(message.getValue().get("n")));
        assertThat(subscription.await(WAIT)).isTrue();

        add("1");
        assertThat(received.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo("1");

        redisTemplate.opsForStream().destroyGroup(STREAM_KEY, CONSUMER_GROUP);
        assertThat(waitUntil(() -> errors.get() > 0)).isTrue();

        createGroup();
        add("2");

        assertThat(received.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo("2");
        assertThat(subscription.isActive()).isTrue();
    }

    @Test
    void 기본_구독은_읽기_오류_한_번에_취소된다() throws InterruptedException {
        Subscription subscription = container.receive(
                Consumer.from(CONSUMER_GROUP, "default-consumer"),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()),
                message -> received.add(message.getValue().get("n")));
        assertThat(subscription.await(WAIT)).isTrue();

        redisTemplate.opsForStream().destroyGroup(STREAM_KEY, CONSUMER_GROUP);

        assertThat(waitUntil(() -> !subscription.isActive())).isTrue();
    }

    private void createGroup() {
        redisTemplate.execute((RedisCallback<String>) connection -> connection.streamCommands().xGroupCreate(
                STREAM_KEY.getBytes(StandardCharsets.UTF_8), CONSUMER_GROUP, ReadOffset.latest(), true));
    }

    private void add(String n) {
        redisTemplate.opsForStream().add(STREAM_KEY, Map.of("n", n));
    }

    private boolean waitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(Duration.ofMillis(20));
        }
        return condition.getAsBoolean();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

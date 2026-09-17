package kr.co.cking.event.application.config;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code cg:ticket-history} Consumer Group을 응모 Stream({@code stream:ticket-deducted})에
 * 미리 만들어 둔다. 마감 처리(EventLifecycleScheduler)의 Drain 확인이 이 그룹의
 * XINFO GROUPS(lastDeliveredId·pending)를 읽어야 하는데, 그룹 자체가 없으면 NOGROUP
 * 오류가 나기 때문이다.
 *
 * <p>ponytail: 이 그룹을 실제로 읽어 DB에 반영하는 Entry Stream Consumer는 별도 작업
 * 범위(T2-04 Entry 부분)라 아직 없다. 그 컨슈머가 붙기 전까지는 lastDeliveredId가
 * 전진하지 않아 Drain이 항상 "미완료"로 판정되는데, 이는 실제로 아무도 커밋하지
 * 않은 상태를 정직하게 반영하는 것이라 의도된 동작이다.
 */
@Component
public class EntryStreamGroupConfig {

    @Value("${cking.entry.stream-key:stream:ticket-deducted}")
    private String streamKey;

    @Value("${cking.entry.history-consumer-group:cg:ticket-history}")
    private String consumerGroup;

    private final StringRedisTemplate redisTemplate;

    public EntryStreamGroupConfig(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureConsumerGroup() {
        try {
            redisTemplate.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            streamKey.getBytes(StandardCharsets.UTF_8),
                            consumerGroup,
                            ReadOffset.from("0"),
                            true
                    )
            );
        } catch (DataAccessException e) {
            Throwable rootCause = e.getMostSpecificCause();
            if (rootCause.getMessage() == null || !rootCause.getMessage().contains("BUSYGROUP")) {
                throw e;
            }
        }
    }
}

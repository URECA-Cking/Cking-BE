package kr.co.cking.event.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroup;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 응모 Stream({@code stream:ticket-deducted})의 {@code cg:ticket-history} Consumer Group이
 * cutoff streamId까지 전부 소비·ACK했는지 확인한다. 이게 true여야 마감 처리 중인
 * 이벤트를 CLOSED로 확정해도 안전하다(§6.6, awaitDrain에 흡수되는 pendingCount 확인).
 */
@Component
public class EventDrainChecker {

    private final StringRedisTemplate redisTemplate;
    private final String entryStreamKey;
    private final String consumerGroup;

    public EventDrainChecker(
            StringRedisTemplate redisTemplate,
            @Value("${cking.entry.stream-key:stream:ticket-deducted}") String entryStreamKey,
            @Value("${cking.entry.history-consumer-group:cg:ticket-history}") String consumerGroup
    ) {
        this.redisTemplate = redisTemplate;
        this.entryStreamKey = entryStreamKey;
        this.consumerGroup = consumerGroup;
    }

    /**
     * lastDeliveredId가 cutoff 이상이고 PEL(미확인 메시지)이 없어야 Drain 완료로 본다.
     * 그룹이 아직 없으면(NOGROUP) 아무 메시지도 소비되지 않은 것이므로 false를 반환한다.
     */
    public boolean isDrained(String cutoffStreamId) {
        XInfoGroup group = findGroup();
        if (group == null) {
            return false;
        }
        return compare(group.lastDeliveredId(), cutoffStreamId) >= 0 && group.pendingCount() == 0L;
    }

    private XInfoGroup findGroup() {
        try {
            return redisTemplate.opsForStream().groups(entryStreamKey).stream()
                    .filter(g -> g.groupName().equals(consumerGroup))
                    .findFirst()
                    .orElse(null);
        } catch (DataAccessException e) {
            // 그룹·Stream이 아직 없으면(NOGROUP/no such key) 아무것도 소비되지 않은 것과 같다.
            return null;
        }
    }

    private int compare(String left, String right) {
        RecordId leftId = RecordId.of(left);
        RecordId rightId = RecordId.of(right);
        int byTimestamp = leftId.getTimestamp().compareTo(rightId.getTimestamp());
        if (byTimestamp != 0) {
            return byTimestamp;
        }
        return leftId.getSequence().compareTo(rightId.getSequence());
    }
}

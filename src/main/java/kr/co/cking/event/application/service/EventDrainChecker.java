package kr.co.cking.event.application.service;

import java.nio.charset.StandardCharsets;
import java.util.List;

import kr.co.cking.stream.repository.DeadStreamMessageQueryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroup;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 응모 Stream({@code stream:ticket-deducted})의 {@code cg:ticket-history} Consumer Group이
 * 특정 이벤트의 cutoff streamId까지 그 이벤트에 속한 메시지를 전부 소비·ACK했는지 확인한다.
 * 이게 true여야 마감 처리 중인 이벤트를 CLOSED로 확정해도 안전하다(§6.6).
 *
 * <p>Stream에는 여러 이벤트의 응모가 섞여 들어오므로, 그룹 전체의 pendingCount만으로는
 * 판단할 수 없다 - 다른 이벤트의 PEL 메시지 때문에 이 이벤트가 계속 막히거나, 반대로
 * 이 이벤트의 미해결 메시지를 놓칠 수 있다. cutoff 이하 PEL 중 이 이벤트에 속한 메시지가
 * 있는지를 직접 확인한다.
 *
 * <p>Dead Stream으로 옮긴 뒤 ACK된 메시지는 PEL에서 사라지므로, 같은 이벤트의 cutoff 이하
 * 미해결 SPEND 메시지도 함께 확인한다.
 */
@Component
public class EventDrainChecker {

    private static final long PENDING_PAGE_SIZE = 10_000L;

    private final StringRedisTemplate redisTemplate;
    private final DeadStreamMessageQueryRepository deadStreamMessageQueryRepository;
    private final String entryStreamKey;
    private final String consumerGroup;

    public EventDrainChecker(
            StringRedisTemplate redisTemplate,
            DeadStreamMessageQueryRepository deadStreamMessageQueryRepository,
            @Value("${cking.entry.stream-key:stream:ticket-deducted}") String entryStreamKey,
            @Value("${cking.entry.history-consumer-group:cg:ticket-history}") String consumerGroup
    ) {
        this.redisTemplate = redisTemplate;
        this.deadStreamMessageQueryRepository = deadStreamMessageQueryRepository;
        this.entryStreamKey = entryStreamKey;
        this.consumerGroup = consumerGroup;
    }

    /**
     * lastDeliveredId가 cutoff 이상이고, cutoff 이하 PEL에 이 이벤트({@code eventId}) 메시지가
     * 없어야 Drain 완료로 본다. 그룹이 아직 없으면(NOGROUP) 아무 메시지도 소비되지 않은 것이므로
     * false를 반환한다.
     */
    public boolean isDrained(Long eventId, String cutoffStreamId) {
        XInfoGroup group = findGroup();
        if (group == null) {
            return false;
        }
        if (compare(group.lastDeliveredId(), cutoffStreamId) < 0) {
            return false;
        }
        if (hasPendingMessageForEvent(eventId, cutoffStreamId)) {
            return false;
        }
        return !hasUnresolvedDeadStreamMessage(eventId, cutoffStreamId);
    }

    private boolean hasUnresolvedDeadStreamMessage(Long eventId, String cutoffStreamId) {
        return deadStreamMessageQueryRepository.findUnresolvedSpendSourceStreamIds(eventId).stream()
                .anyMatch(sourceStreamId -> compare(sourceStreamId, cutoffStreamId) <= 0);
    }

    /** cutoff 이하 PEL 메시지 중, 페이로드의 eventId가 일치하는 것이 있으면 true. */
    private boolean hasPendingMessageForEvent(Long eventId, String cutoffStreamId) {
        Range<String> range = Range.closed("-", cutoffStreamId);
        while (true) {
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(entryStreamKey, consumerGroup, range, PENDING_PAGE_SIZE);
            if (pending.isEmpty()) {
                return false;
            }
            if (containsPendingMessageForEvent(eventId, pending)) {
                return true;
            }

            String lastPendingId = pending.get(pending.size() - 1).getId().getValue();
            if (pending.size() < PENDING_PAGE_SIZE || compare(lastPendingId, cutoffStreamId) >= 0) {
                return false;
            }
            range = Range.leftOpen(lastPendingId, cutoffStreamId);
        }
    }

    /**
     * PEL 메시지의 eventId를 ID 단위로 조회한다. min~max 범위 XRANGE는 PEL 사이에 낀 정상 처리분
     * 전체를 읽어오므로 쓰지 않는다. PEL 건수만큼의 {@code XRANGE id id COUNT 1}을 pipelining한다.
     */
    private boolean containsPendingMessageForEvent(Long eventId, PendingMessages pending) {
        byte[] key = entryStreamKey.getBytes(StandardCharsets.UTF_8);
        List<String> ids = pending.stream().map(m -> m.getId().getValue()).toList();
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String id : ids) {
                connection.streamCommands().xRange(key, Range.closed(id, id), Limit.limit().count(1));
            }
            return null;
        });

        String targetEventId = String.valueOf(eventId);
        return results.stream()
                .flatMap(result -> ((List<?>) result).stream())
                .map(record -> (MapRecord<?, ?, ?>) record)
                .flatMap(record -> record.getValue().entrySet().stream())
                .anyMatch(field -> "eventId".equals(decode(field.getKey())) && targetEventId.equals(decode(field.getValue())));
    }

    // 파이프라인 결과는 template serializer를 거치지 않아 필드가 byte[]로 남는다.
    private static String decode(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
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

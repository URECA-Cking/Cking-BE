package kr.co.cking.stream.application;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroup;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import kr.co.cking.stream.repository.DeadStreamMessageQueryRepository;

/**
 * 특정 {@code (memberId, creatorId)}의 응모(SPEND)·적립(EARN) 메시지가 아직 DB에 반영되지 않았는지 확인한다.
 * 수동 잔액 보정이 이런 메시지를 남긴 채 Redis를 DB 값으로 덮어쓰면 응모권이 되돌아가거나 사라지므로,
 * 하나라도 있으면 보정을 거부하는 근거로 쓴다.
 *
 * <p>두 Stream 각각에서 (1) Consumer에 전달됐지만 ACK되지 않은 PEL 메시지, (2) 아직 Consumer에
 * 전달되지 않은 {@code lastDeliveredId} 이후 메시지를 보고, 추가로 (3) 미해결 Dead Stream을 확인한다.
 * PEL만 보면 (2)와 (3)을 놓친다. 이벤트 단위 Drain 판정은 {@code EventDrainChecker}가 따로 맡는다.
 */
@Component
public class UnappliedBalanceMessageChecker {

    private static final int PAGE_SIZE = 1_000;
    private static final String FROM_START = "0-0";

    private final StringRedisTemplate redisTemplate;
    private final DeadStreamMessageQueryRepository deadStreamMessageQueryRepository;
    private final String spendStreamKey;
    private final String spendConsumerGroup;
    private final String earnStreamKey;
    private final String earnConsumerGroup;

    public UnappliedBalanceMessageChecker(
            StringRedisTemplate redisTemplate,
            DeadStreamMessageQueryRepository deadStreamMessageQueryRepository,
            @Value("${cking.entry.stream-key:stream:ticket-deducted}") String spendStreamKey,
            @Value("${cking.entry.history-consumer-group:cg:ticket-history}") String spendConsumerGroup,
            @Value("${cking.ticket.earn-stream-key:stream:ticket-earned}") String earnStreamKey,
            @Value("${cking.ticket.earn-consumer-group:cg:ticket-earn}") String earnConsumerGroup
    ) {
        this.redisTemplate = redisTemplate;
        this.deadStreamMessageQueryRepository = deadStreamMessageQueryRepository;
        this.spendStreamKey = spendStreamKey;
        this.spendConsumerGroup = spendConsumerGroup;
        this.earnStreamKey = earnStreamKey;
        this.earnConsumerGroup = earnConsumerGroup;
    }

    public boolean exists(Long memberId, Long creatorId) {
        return hasUnappliedIn(spendStreamKey, spendConsumerGroup, memberId, creatorId)
                || hasUnappliedIn(earnStreamKey, earnConsumerGroup, memberId, creatorId)
                || deadStreamMessageQueryRepository.existsUnresolvedByMemberAndCreator(memberId, creatorId);
    }

    private boolean hasUnappliedIn(String streamKey, String group, Long memberId, Long creatorId) {
        XInfoGroup info = findGroup(streamKey, group);
        // 그룹이 아직 없으면 PEL도 없고(XPENDING은 NOGROUP 오류), 아무것도 소비되지 않았으니 처음부터 본다.
        if (info == null) {
            return hasUndelivered(streamKey, FROM_START, memberId, creatorId);
        }
        return hasPending(streamKey, group, memberId, creatorId)
                || hasUndelivered(streamKey, info.lastDeliveredId(), memberId, creatorId);
    }

    /** Consumer에 전달됐지만 ACK되지 않은 메시지 중 대상 (memberId, creatorId)의 것이 있으면 true. */
    private boolean hasPending(String streamKey, String group, Long memberId, Long creatorId) {
        Range<String> range = Range.unbounded();
        while (true) {
            PendingMessages pending = redisTemplate.opsForStream().pending(streamKey, group, range, PAGE_SIZE);
            if (pending.isEmpty()) {
                return false;
            }
            List<String> ids = pending.stream().map(message -> message.getId().getValue()).toList();
            if (anyMatchByIds(streamKey, ids, memberId, creatorId)) {
                return true;
            }
            if (ids.size() < PAGE_SIZE) {
                return false;
            }
            range = Range.leftOpen(ids.get(ids.size() - 1), "+");
        }
    }

    // 수동·저빈도 경로라 PEL ID마다 XRANGE id id COUNT 1을 순차 조회한다(EventDrainChecker는 틱마다 돌아 pipelining).
    private boolean anyMatchByIds(String streamKey, List<String> ids, Long memberId, Long creatorId) {
        return ids.stream().anyMatch(id -> {
            List<MapRecord<String, Object, Object>> records =
                    redisTemplate.opsForStream().range(streamKey, Range.closed(id, id), Limit.limit().count(1));
            return records != null && records.stream().anyMatch(record -> matches(
                    String.valueOf(record.getValue().get("userId")),
                    String.valueOf(record.getValue().get("creatorId")),
                    record.getValue().get("couponType"),
                    memberId, creatorId));
        });
    }

    /** Consumer Group이 아직 읽지 않은(전달 기준점 이후) 메시지 중 대상의 것이 있으면 true. */
    private boolean hasUndelivered(String streamKey, String lastDeliveredId, Long memberId, Long creatorId) {
        String lastId = lastDeliveredId;
        while (true) {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().range(
                    streamKey, Range.rightUnbounded(Range.Bound.exclusive(lastId)), Limit.limit().count(PAGE_SIZE));
            if (records == null || records.isEmpty()) {
                return false;
            }
            boolean found = records.stream().anyMatch(record -> matches(
                    String.valueOf(record.getValue().get("userId")),
                    String.valueOf(record.getValue().get("creatorId")),
                    record.getValue().get("couponType"),
                    memberId, creatorId));
            if (found) {
                return true;
            }
            if (records.size() < PAGE_SIZE) {
                return false;
            }
            lastId = records.get(records.size() - 1).getId().getValue();
        }
    }

    // Stream 키 자체가 없어도 오류가 나므로 그룹이 없는 것과 같게 본다.
    private XInfoGroup findGroup(String streamKey, String group) {
        try {
            return redisTemplate.opsForStream().groups(streamKey).stream()
                    .filter(g -> g.groupName().equals(group))
                    .findFirst()
                    .orElse(null);
        } catch (DataAccessException e) {
            return null;
        }
    }

    // 이 checker는 (memberId, creatorId) 크리에이터 잔액 보정 전용이다(이슈 #243). SPEND
    // 메시지의 couponType이 COMMON이면 creatorId 필드가 실려 있어도 그 크리에이터 잔액을
    // 차감하지 않았으므로 매칭 대상에서 제외한다 - 아니면 공용 응모권 사용 메시지가 크리에이터
    // 잔액의 미반영 메시지로 잘못 잡혀 정상 보정이 거부된다. couponType 필드가 없는 메시지
    // (배포 전 SPEND, EARN 스트림)는 CREATOR로 취급한다.
    private static boolean matches(String userId, String creatorId, Object couponType, Long memberId, Long targetCreatorId) {
        if ("COMMON".equals(couponType)) {
            return false;
        }
        return String.valueOf(memberId).equals(userId) && String.valueOf(targetCreatorId).equals(creatorId);
    }
}

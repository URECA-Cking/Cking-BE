package kr.co.cking.stream.scheduler;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import kr.co.cking.stream.domain.DeadStreamMessage;
import kr.co.cking.stream.domain.DeadStreamResolutionStatus;
import kr.co.cking.stream.domain.DeadStreamType;
import kr.co.cking.stream.presentation.SpendStreamListener;
import kr.co.cking.stream.repository.DeadStreamMessageRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * 서버 재기동·처리 실패로 PEL에 남은 SPEND Stream 메시지를 XCLAIM으로 회수해
 * 재처리한다. 최대 재시도 횟수를 넘긴 메시지는 {@code dead_stream_message}로
 * 옮기고 XACK해서 PEL에서 제거한다. {@link EarnStreamPelRecoveryScheduler}(이슈
 * #43)와 동일한 패턴 — Codex 리뷰(이슈 #62) 반영: PEL 회수 경로가 없으면 일시
 * 실패로 메시지가 영구히 PEL에 남아 Drain이 끝나지 않는다.
 *
 * <p>재시도 횟수는 별도 카운터를 두지 않고 Redis가 XCLAIM마다 관리하는
 * {@code totalDeliveryCount}(XPENDING 결과)를 그대로 기준으로 삼는다.
 */
@Slf4j
@Component
public class SpendStreamPelRecoveryScheduler {

    private static final String RECOVERY_CONSUMER = "spend-pel-recovery";
    private static final int SCAN_COUNT = 100;

    private final StringRedisTemplate redisTemplate;
    private final SpendStreamListener spendStreamListener;
    private final DeadStreamMessageRepository deadStreamMessageRepository;
    private final ObjectMapper objectMapper;
    private final String streamKey;
    private final String consumerGroup;
    private final Duration minIdleTime;
    private final long maxRetry;

    public SpendStreamPelRecoveryScheduler(
            StringRedisTemplate redisTemplate,
            SpendStreamListener spendStreamListener,
            DeadStreamMessageRepository deadStreamMessageRepository,
            ObjectMapper objectMapper,
            @Value("${cking.entry.stream-key:stream:ticket-deducted}") String streamKey,
            @Value("${cking.entry.history-consumer-group:cg:ticket-history}") String consumerGroup,
            @Value("${cking.entry.spend-pel-min-idle-ms:60000}") long minIdleTimeMillis,
            @Value("${cking.entry.spend-pel-max-retry:5}") long maxRetry
    ) {
        this.redisTemplate = redisTemplate;
        this.spendStreamListener = spendStreamListener;
        this.deadStreamMessageRepository = deadStreamMessageRepository;
        this.objectMapper = objectMapper;
        this.streamKey = streamKey;
        this.consumerGroup = consumerGroup;
        this.minIdleTime = Duration.ofMillis(minIdleTimeMillis);
        this.maxRetry = maxRetry;
    }

    @Scheduled(fixedDelayString = "${cking.entry.spend-pel-recovery-interval-ms:30000}")
    public void recoverPending() {
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(streamKey, consumerGroup, Range.unbounded(), SCAN_COUNT, minIdleTime);

        if (pending == null || pending.isEmpty()) {
            return;
        }

        Map<String, PendingMessage> pendingById = pending.stream()
                .collect(java.util.stream.Collectors.toMap(PendingMessage::getIdAsString, m -> m));

        RecordId[] recordIds = pending.stream().map(PendingMessage::getId).toArray(RecordId[]::new);
        List<MapRecord<String, String, String>> claimed = redisTemplate.<String, String>opsForStream()
                .claim(streamKey, consumerGroup, RECOVERY_CONSUMER, minIdleTime, recordIds);

        for (MapRecord<String, String, String> record : claimed) {
            PendingMessage pendingMessage = pendingById.get(record.getId().getValue());

            if (pendingMessage != null && pendingMessage.getTotalDeliveryCount() > maxRetry) {
                moveToDeadStream(record, pendingMessage);
            } else {
                spendStreamListener.process(record);
            }
        }
    }

    private void moveToDeadStream(MapRecord<String, String, String> record, PendingMessage pendingMessage) {
        String sourceStreamId = record.getId().getValue();
        Map<String, String> fields = record.getValue();
        String failureReason = "PEL 최대 재시도(%d회) 초과".formatted(maxRetry);
        Instant now = Instant.now();

        deadStreamMessageRepository.findBySourceStreamIdAndStreamType(sourceStreamId, DeadStreamType.SPEND)
                .ifPresentOrElse(
                        existing -> {
                            // findBy...는 Spring Data JPA가 자체 트랜잭션에서 실행해 반환 시점에
                            // 이미 detached 상태다. recordRetry()로 필드만 바꾸면 영속성
                            // 컨텍스트가 없어 DB에 flush되지 않으므로 명시적으로 save한다.
                            existing.recordRetry((int) pendingMessage.getTotalDeliveryCount(), failureReason, now);
                            deadStreamMessageRepository.save(existing);
                        },
                        () -> deadStreamMessageRepository.save(DeadStreamMessage.builder()
                                .sourceStreamId(sourceStreamId)
                                .streamType(DeadStreamType.SPEND)
                                .payload(toJson(fields))
                                .requestId(fields.get("requestId"))
                                .eventId(parseLongOrNull(fields.get("eventId")))
                                .memberId(parseLongOrNull(fields.get("userId")))
                                .failureReason(failureReason)
                                .retryCount((int) pendingMessage.getTotalDeliveryCount())
                                .lastFailedAt(now)
                                .resolutionStatus(DeadStreamResolutionStatus.UNRESOLVED)
                                .createdAt(now)
                                .build())
                );

        // Dead Stream에 원본을 보존했으므로 PEL에서 제거해도 안전하다.
        redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, record.getId());
        log.warn("SPEND Stream 메시지를 Dead Stream으로 이동했습니다. sourceStreamId={}, fields={}", sourceStreamId, fields);
    }

    private String toJson(Map<String, String> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JacksonException e) {
            throw new IllegalStateException("SPEND Stream payload를 JSON으로 직렬화하지 못했습니다.", e);
        }
    }

    private Long parseLongOrNull(String value) {
        return value == null ? null : Long.valueOf(value);
    }
}

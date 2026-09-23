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
import kr.co.cking.stream.presentation.CommonEarnStreamListener;
import kr.co.cking.stream.repository.DeadStreamMessageRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * PEL에 남은 공용 EARN Stream 메시지를 XCLAIM으로 회수해 재처리한다. 최대 재시도 횟수를
 * 넘긴 메시지는 {@code dead_stream_message}로 옮기고 XACK해서 PEL에서 제거한다(이슈 #244,
 * #219/#224에서 범위 밖으로 미뤘던 부분).
 *
 * <p>{@link kr.co.cking.stream.presentation.EarnStreamListener}·
 * {@link kr.co.cking.stream.scheduler.EarnStreamPelRecoveryScheduler}와 완전히 같은 구조다.
 * 이전 버전은 Dead Stream 이관이 없어 한도 초과 메시지를 포기하지 않고 더 긴 간격으로
 * 영구히 재시도했는데(페이지네이션·2단계 claim 분리 포함), 이관이 생긴 지금은 한도를 넘긴
 * 메시지를 그 자리에서 이관·ACK하므로 그 복잡도가 더 필요 없다 - PEL에 영구히 쌓이는
 * 메시지가 없어 페이지가 밀릴 일도 없다.
 */
@Slf4j
@Component
public class CommonEarnStreamPelRecoveryScheduler {

    private static final String RECOVERY_CONSUMER = "common-earn-pel-recovery";
    private static final int SCAN_COUNT = 100;

    private final StringRedisTemplate redisTemplate;
    private final CommonEarnStreamListener commonEarnStreamListener;
    private final DeadStreamMessageRepository deadStreamMessageRepository;
    private final ObjectMapper objectMapper;
    private final String streamKey;
    private final String consumerGroup;
    private final Duration minIdleTime;
    private final long maxRetry;

    public CommonEarnStreamPelRecoveryScheduler(
            StringRedisTemplate redisTemplate,
            CommonEarnStreamListener commonEarnStreamListener,
            DeadStreamMessageRepository deadStreamMessageRepository,
            ObjectMapper objectMapper,
            @Value("${cking.ticket.common-earn-stream-key:stream:common-ticket-earned}") String streamKey,
            @Value("${cking.ticket.common-earn-consumer-group:cg:common-ticket-earn}") String consumerGroup,
            @Value("${cking.ticket.common-earn-pel-min-idle-ms:60000}") long minIdleTimeMillis,
            @Value("${cking.ticket.common-earn-pel-max-retry:5}") long maxRetry
    ) {
        this.redisTemplate = redisTemplate;
        this.commonEarnStreamListener = commonEarnStreamListener;
        this.deadStreamMessageRepository = deadStreamMessageRepository;
        this.objectMapper = objectMapper;
        this.streamKey = streamKey;
        this.consumerGroup = consumerGroup;
        this.minIdleTime = Duration.ofMillis(minIdleTimeMillis);
        this.maxRetry = maxRetry;
    }

    @Scheduled(fixedDelayString = "${cking.ticket.common-earn-pel-recovery-interval-ms:30000}")
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
                try {
                    moveToDeadStream(record, pendingMessage);
                } catch (Exception e) {
                    // Dead Stream 이관 자체가 실패해도(예: dead_stream_message.member_id FK 위반)
                    // 예외를 밖으로 던지면 이번 틱에서 claim된 나머지 메시지 처리까지 전부 막힌다.
                    // 이 메시지만 건너뛰고 계속 진행한다 - ACK하지 않았으므로 PEL에 남아 다음
                    // 틱에 다시 시도된다.
                    log.error("공용 EARN Stream 메시지를 Dead Stream으로 이관하지 못했습니다. sourceStreamId={}, fields={}",
                            record.getId().getValue(), record.getValue(), e);
                }
            } else {
                commonEarnStreamListener.process(record);
            }
        }
    }

    private void moveToDeadStream(MapRecord<String, String, String> record, PendingMessage pendingMessage) {
        String sourceStreamId = record.getId().getValue();
        Map<String, String> fields = record.getValue();
        String failureReason = "PEL 최대 재시도(%d회) 초과".formatted(maxRetry);
        Instant now = Instant.now();

        deadStreamMessageRepository.findBySourceStreamIdAndStreamType(sourceStreamId, DeadStreamType.COMMON_EARN)
                .ifPresentOrElse(
                        existing -> {
                            existing.recordRetry((int) pendingMessage.getTotalDeliveryCount(), failureReason, now);
                            deadStreamMessageRepository.save(existing);
                        },
                        () -> deadStreamMessageRepository.save(DeadStreamMessage.builder()
                                .sourceStreamId(sourceStreamId)
                                .streamType(DeadStreamType.COMMON_EARN)
                                .payload(toJson(fields))
                                .requestId(fields.get("requestId"))
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
        log.warn("공용 EARN Stream 메시지를 Dead Stream으로 이동했습니다. sourceStreamId={}, fields={}", sourceStreamId, fields);
    }

    private String toJson(Map<String, String> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JacksonException e) {
            throw new IllegalStateException("공용 EARN Stream payload를 JSON으로 직렬화하지 못했습니다.", e);
        }
    }

    private Long parseLongOrNull(String value) {
        return value == null ? null : Long.valueOf(value);
    }
}

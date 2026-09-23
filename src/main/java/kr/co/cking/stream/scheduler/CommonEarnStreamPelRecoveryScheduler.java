package kr.co.cking.stream.scheduler;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import kr.co.cking.stream.presentation.CommonEarnStreamListener;
import lombok.extern.slf4j.Slf4j;

/**
 * PEL에 남은 공용 EARN Stream 메시지를 XCLAIM으로 회수해 재처리한다(이슈 #219). Lua가 이미
 * Redis 잔액을 올리고 성공을 응답한 메시지이므로, 이 회수 경로가 없으면 DB 반영 실패가
 * 영구 불일치로 남는다.
 *
 * <p>{@link EarnStreamPelRecoveryScheduler}와 달리 공용 EARN은 아직 Dead Stream 이관을
 * 지원하지 않는다(후속 이슈). 그래서 최대 재시도를 넘긴 메시지도 포기하지 않고 PEL에
 * 보존한 채 {@code overLimitIdleTime} 간격으로 계속 재처리한다 — 장시간 DB 장애로 한도를
 * 넘긴 일시적 실패도 복구 후 결국 반영되고, {@code apply()}가 requestId 기준 멱등이라
 * 재처리를 반복해도 중복 반영되지 않는다. 절대 성공할 수 없는 메시지(fingerprint 불일치 등)는
 * 간격마다 재시도·실패 로그가 반복되며, 후속 이슈의 Dead Stream 이관으로 정리한다.
 *
 * <p>XCLAIM은 {@code totalDeliveryCount}를 올리고 idle을 0으로 되돌리므로, 한도 초과
 * 메시지는 XPENDING의 idle로 먼저 걸러 간격이 지난 것만 claim한다. claim 시 min-idle도
 * 같은 간격으로 넘겨 다중 인스턴스 경합에서도 간격이 지켜지게 한다.
 */
@Slf4j
@Component
public class CommonEarnStreamPelRecoveryScheduler {

    private static final String RECOVERY_CONSUMER = "common-earn-pel-recovery";
    private static final int MAX_SCAN_PAGES = 10;

    private final StringRedisTemplate redisTemplate;
    private final CommonEarnStreamListener commonEarnStreamListener;
    private final String streamKey;
    private final String consumerGroup;
    private final Duration minIdleTime;
    private final long maxRetry;
    private final Duration overLimitIdleTime;
    private final int scanCount;

    public CommonEarnStreamPelRecoveryScheduler(
            StringRedisTemplate redisTemplate,
            CommonEarnStreamListener commonEarnStreamListener,
            @Value("${cking.ticket.common-earn-stream-key:stream:common-ticket-earned}") String streamKey,
            @Value("${cking.ticket.common-earn-consumer-group:cg:common-ticket-earn}") String consumerGroup,
            @Value("${cking.ticket.common-earn-pel-min-idle-ms:60000}") long minIdleTimeMillis,
            @Value("${cking.ticket.common-earn-pel-max-retry:5}") long maxRetry,
            @Value("${cking.ticket.common-earn-pel-over-limit-idle-ms:600000}") long overLimitIdleTimeMillis,
            @Value("${cking.ticket.common-earn-pel-scan-count:100}") int scanCount
    ) {
        this.redisTemplate = redisTemplate;
        this.commonEarnStreamListener = commonEarnStreamListener;
        this.streamKey = streamKey;
        this.consumerGroup = consumerGroup;
        this.minIdleTime = Duration.ofMillis(minIdleTimeMillis);
        this.maxRetry = maxRetry;
        this.overLimitIdleTime = Duration.ofMillis(overLimitIdleTimeMillis);
        this.scanCount = scanCount;
    }

    /**
     * 한도 초과 메시지는 claim하지 않아도 XPENDING 결과 앞쪽에 계속 남으므로, 첫 페이지만 보면
     * 그 뒤의 정상 메시지가 회수되지 못한다. 마지막으로 본 ID 다음부터 페이지를 넘겨가며 본다.
     */
    @Scheduled(fixedDelayString = "${cking.ticket.common-earn-pel-recovery-interval-ms:30000}")
    public void recoverPending() {
        Range<String> range = Range.unbounded();

        for (int page = 0; page < MAX_SCAN_PAGES; page++) {
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(streamKey, consumerGroup, range, scanCount, minIdleTime);

            if (pending == null || pending.isEmpty()) {
                return;
            }

            recover(pending);

            if (pending.size() < scanCount) {
                return;
            }
            String lastId = pending.get(pending.size() - 1).getIdAsString();
            range = Range.rightUnbounded(Range.Bound.inclusive(nextId(lastId)));
        }
    }

    private void recover(PendingMessages pending) {
        Map<String, PendingMessage> pendingById = pending.stream()
                .collect(Collectors.toMap(PendingMessage::getIdAsString, m -> m));

        List<RecordId> withinLimit = new ArrayList<>();
        List<RecordId> overLimitDue = new ArrayList<>();

        for (PendingMessage message : pending) {
            if (message.getTotalDeliveryCount() <= maxRetry) {
                withinLimit.add(message.getId());
            } else if (message.getElapsedTimeSinceLastDelivery().compareTo(overLimitIdleTime) >= 0) {
                overLimitDue.add(message.getId());
            }
            // 한도를 넘겼지만 간격이 지나지 않은 메시지는 이번 주기에 claim하지 않는다.
        }

        claimAndProcess(withinLimit, minIdleTime, pendingById, false);
        claimAndProcess(overLimitDue, overLimitIdleTime, pendingById, true);
    }

    private void claimAndProcess(List<RecordId> recordIds, Duration claimMinIdle,
                                 Map<String, PendingMessage> pendingById, boolean overLimit) {
        if (recordIds.isEmpty()) {
            return;
        }

        List<MapRecord<String, String, String>> claimed = redisTemplate.<String, String>opsForStream()
                .claim(streamKey, consumerGroup, RECOVERY_CONSUMER, claimMinIdle, recordIds.toArray(RecordId[]::new));

        for (MapRecord<String, String, String> record : claimed) {
            PendingMessage pendingMessage = pendingById.get(record.getId().getValue());

            // 한도 초과 후 첫 claim(직전 전달 횟수 = maxRetry + 1)에서만 남긴다. 이후 claim은 횟수가 더 크다.
            if (overLimit && pendingMessage != null && pendingMessage.getTotalDeliveryCount() == maxRetry + 1) {
                log.warn("공용 EARN Stream 메시지가 최대 재시도({}회)를 넘겼습니다. Dead Stream 미지원이라 PEL에 보존하고 "
                                + "{}초 간격으로 계속 재처리합니다. id={}, fields={}",
                        maxRetry, overLimitIdleTime.toSeconds(), record.getId(), record.getValue());
            }

            commonEarnStreamListener.process(record);
        }
    }

    /** Stream ID({@code ms-seq})의 바로 다음 ID. XPENDING 페이지를 이어서 보기 위한 시작점이다. */
    private static String nextId(String id) {
        int dash = id.indexOf('-');
        long millis = Long.parseLong(id.substring(0, dash));
        long sequence = Long.parseLong(id.substring(dash + 1));
        return millis + "-" + (sequence + 1);
    }
}

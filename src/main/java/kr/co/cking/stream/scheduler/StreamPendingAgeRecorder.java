package kr.co.cking.stream.scheduler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stream마다 PEL에서 가장 오래된 메시지가 만들어진 지 얼마나 됐는지 기록한다. 부하가 계속 들어오면 PEL 건수는 늘 0보다
 * 커서, 처리가 막혔는지는 건수가 아니라 가장 오래된 메시지의 나이로 판단한다. PEL이 비면 0, 조회에 실패하면 NaN이다.
 * 실행 풀은 {@code StreamPendingAgeSchedulingConfig}.
 */
@Slf4j
@Component
public class StreamPendingAgeRecorder {

    static final String METRIC = "cking.stream.pending.oldest.age";
    private static final long UNKNOWN = -1;

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final List<Target> targets;

    public StreamPendingAgeRecorder(
            StringRedisTemplate redisTemplate,
            Clock clock,
            MeterRegistry meterRegistry,
            @Value("${cking.entry.stream-key:stream:ticket-deducted}") String spendStreamKey,
            @Value("${cking.entry.history-consumer-group:cg:ticket-history}") String spendGroup,
            @Value("${cking.ticket.earn-stream-key:stream:ticket-earned}") String earnStreamKey,
            @Value("${cking.ticket.earn-consumer-group:cg:ticket-earn}") String earnGroup,
            @Value("${cking.ticket.common-earn-stream-key:stream:common-ticket-earned}") String commonEarnStreamKey,
            @Value("${cking.ticket.common-earn-consumer-group:cg:common-ticket-earn}") String commonEarnGroup
    ) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.targets = List.of(
                new Target(spendStreamKey, spendGroup),
                new Target(earnStreamKey, earnGroup),
                new Target(commonEarnStreamKey, commonEarnGroup));
        targets.forEach(target -> Gauge.builder(METRIC, target.ageMillis, StreamPendingAgeRecorder::seconds)
                .tag("stream", target.stream)
                .baseUnit("seconds")
                .register(meterRegistry));
    }

    @Scheduled(scheduler = "streamPendingAgeTaskScheduler", fixedDelayString = "${cking.stream.pending-age.interval-ms:15000}")
    public void record() {
        long now = clock.millis();
        targets.forEach(target -> target.ageMillis.set(oldestAgeMillis(target, now)));
    }

    private long oldestAgeMillis(Target target, long now) {
        PendingMessagesSummary summary;
        try {
            summary = redisTemplate.opsForStream().pending(target.stream, target.group);
        } catch (RuntimeException e) {
            log.warn("Stream PEL 나이를 조회하지 못했습니다. stream={}, cause={}", target.stream, e.toString());
            return UNKNOWN;
        }
        if (summary == null || summary.getTotalPendingMessages() == 0) {
            return 0;
        }
        return Math.max(0, now - summary.minRecordId().getTimestamp());
    }

    private static double seconds(AtomicLong ageMillis) {
        long value = ageMillis.get();
        return value == UNKNOWN ? Double.NaN : value / 1000.0;
    }

    private record Target(String stream, String group, AtomicLong ageMillis) {
        Target(String stream, String group) {
            this(stream, group, new AtomicLong(UNKNOWN));
        }
    }
}

package kr.co.cking.stream.scheduler;

import java.time.Duration;
import java.util.List;

import kr.co.cking.stream.application.config.StreamConsumerNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 교체된 서버가 남긴 Stream 소비자를 정리한다. 소비자 이름이 서버마다 달라 서버가 바뀔 때마다 그룹에 이름이 쌓인다.
 * 대기 메시지가 없고 {@code idle} 이상 읽기 시도가 없는 소비자만 지운다. 살아 있는 소비자는 메시지가 없어도 2초마다
 * 읽기를 시도해 idle이 짧다. 서버들이 함께 쓰는 재처리 소비자(*-pel-recovery)는 서버별 이름이 아니라 남긴다.
 * 여러 서버가 동시에 실행해도 확인과 삭제가 Lua 한 번이라 결과가 같다.
 */
@Slf4j
@Component
public class StreamConsumerCleanupScheduler {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> cleanupScript;
    private final List<Target> targets;
    private final Duration idle;

    public StreamConsumerCleanupScheduler(
            StringRedisTemplate redisTemplate,
            StreamConsumerNames consumerNames,
            @Value("${cking.entry.stream-key:stream:ticket-deducted}") String spendStreamKey,
            @Value("${cking.entry.history-consumer-group:cg:ticket-history}") String spendGroup,
            @Value("${cking.ticket.earn-stream-key:stream:ticket-earned}") String earnStreamKey,
            @Value("${cking.ticket.earn-consumer-group:cg:ticket-earn}") String earnGroup,
            @Value("${cking.ticket.common-earn-stream-key:stream:common-ticket-earned}") String commonEarnStreamKey,
            @Value("${cking.ticket.common-earn-consumer-group:cg:common-ticket-earn}") String commonEarnGroup,
            @Value("${cking.stream.consumer-cleanup.idle:PT1H}") Duration idle
    ) {
        this.redisTemplate = redisTemplate;
        this.cleanupScript = new DefaultRedisScript<>();
        this.cleanupScript.setLocation(new ClassPathResource("scripts/stream-consumer-cleanup.lua"));
        this.cleanupScript.setResultType(List.class);
        this.targets = List.of(
                new Target(spendStreamKey, spendGroup, consumerNames.of("spend"),
                        SpendStreamPelRecoveryScheduler.RECOVERY_CONSUMER),
                new Target(earnStreamKey, earnGroup, consumerNames.of("earn"),
                        EarnStreamPelRecoveryScheduler.RECOVERY_CONSUMER),
                new Target(commonEarnStreamKey, commonEarnGroup, consumerNames.of("common-earn"),
                        CommonEarnStreamPelRecoveryScheduler.RECOVERY_CONSUMER));
        this.idle = idle;
    }

    @Scheduled(
            fixedDelayString = "${cking.stream.consumer-cleanup.interval-ms:600000}",
            initialDelayString = "${cking.stream.consumer-cleanup.interval-ms:600000}")
    public void cleanUp() {
        for (Target target : targets) {
            try {
                List<?> removed = redisTemplate.execute(cleanupScript, List.of(target.streamKey()),
                        target.group(), String.valueOf(idle.toMillis()), target.ownConsumer(),
                        target.recoveryConsumer());
                if (removed != null && !removed.isEmpty()) {
                    log.info("떠난 Stream 소비자를 정리했습니다. stream={}, group={}, removed={}",
                            target.streamKey(), target.group(), removed);
                }
            } catch (DataAccessException e) {
                log.warn("Stream 소비자 정리에 실패했습니다. 다음 주기에 다시 시도합니다. stream={}, group={}",
                        target.streamKey(), target.group(), e);
            }
        }
    }

    private record Target(String streamKey, String group, String ownConsumer, String recoveryConsumer) {
    }
}

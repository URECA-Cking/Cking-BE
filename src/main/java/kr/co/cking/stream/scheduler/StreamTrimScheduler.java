package kr.co.cking.stream.scheduler;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 모든 Consumer Group이 처리를 끝낸 앞쪽 메시지를 Stream에서 지운다. XACK만으로는 메시지가 남아 응모·적립이 쌓이는 만큼
 * Redis 메모리가 늘어난다(#673). 지우는 기준은 {@code stream-trim.lua}에 있다.
 */
@Slf4j
@Component
public class StreamTrimScheduler {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> trimScript;
    private final List<String> streamKeys;

    public StreamTrimScheduler(
            StringRedisTemplate redisTemplate,
            @Value("${cking.entry.stream-key:stream:ticket-deducted}") String spendStreamKey,
            @Value("${cking.ticket.earn-stream-key:stream:ticket-earned}") String earnStreamKey,
            @Value("${cking.ticket.common-earn-stream-key:stream:common-ticket-earned}") String commonEarnStreamKey
    ) {
        this.redisTemplate = redisTemplate;
        this.trimScript = new DefaultRedisScript<>();
        this.trimScript.setLocation(new ClassPathResource("scripts/stream-trim.lua"));
        this.trimScript.setResultType(Long.class);
        this.streamKeys = List.of(spendStreamKey, earnStreamKey, commonEarnStreamKey);
    }

    @Scheduled(
            fixedDelayString = "${cking.stream.trim.interval-ms:30000}",
            initialDelayString = "${cking.stream.trim.interval-ms:30000}")
    public void trim() {
        for (String streamKey : streamKeys) {
            try {
                redisTemplate.execute(trimScript, List.of(streamKey));
            } catch (DataAccessException e) {
                log.warn("처리가 끝난 Stream 메시지를 지우지 못했습니다. 다음 주기에 다시 시도합니다. stream={}", streamKey, e);
            }
        }
    }
}

package kr.co.cking.stream.presentation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.Map;

import kr.co.cking.stream.scheduler.CommonEarnStreamPelRecoveryScheduler;
import kr.co.cking.ticket.application.CommonEarnEventDispatcher;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code stream:common-ticket-earned} 메시지를 {@link CommonMissionEarnLedgerService}에
 * 반영하고, DB 반영이 끝난 뒤에만 XACK한다(이슈 #219, {@code EarnStreamListener}와 동일 계약).
 */
@Slf4j
@Component
public class CommonEarnStreamListener implements StreamListener<String, MapRecord<String, String, String>> {

    private final CommonEarnEventDispatcher commonEarnEventDispatcher;
    private final StringRedisTemplate redisTemplate;
    private final String streamKey;
    private final String consumerGroup;

    /** 공용 EARN 분기와 Stream 소비·ACK 설정을 연결한다. */
    public CommonEarnStreamListener(
            CommonEarnEventDispatcher commonEarnEventDispatcher,
            StringRedisTemplate redisTemplate,
            @Value("${cking.ticket.common-earn-stream-key:stream:common-ticket-earned}") String streamKey,
            @Value("${cking.ticket.common-earn-consumer-group:cg:common-ticket-earn}") String consumerGroup
    ) {
        this.commonEarnEventDispatcher = commonEarnEventDispatcher;
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
        this.consumerGroup = consumerGroup;
    }

    /** 수신한 공용 EARN 메시지를 DB 반영 후 ACK하는 처리 경로로 전달한다. */
    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        process(message);
    }

    /**
     * DB 반영 + XACK을 수행한다. {@link CommonEarnStreamPelRecoveryScheduler}가 XCLAIM으로
     * 회수한 메시지를 재처리할 때도 이 메서드를 그대로 재사용한다.
     *
     * @return DB 반영과 XACK까지 성공했으면 true, 실패해서 PEL에 남겨야 하면 false
     */
    public boolean process(MapRecord<String, String, String> message) {
        Map<String, String> fields = message.getValue();

        try {
            commonEarnEventDispatcher.apply(fields);
            redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, message.getId());
            return true;
        } catch (Exception e) {
            // DB 반영에 실패했으므로 XACK하지 않는다 — 메시지는 PEL에 남아 재전달된다.
            log.error("공용 EARN Stream 메시지 처리에 실패했습니다. id={}, fields={}", message.getId(), fields, e);
            return false;
        }
    }
}

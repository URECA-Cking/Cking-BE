package kr.co.cking.stream.presentation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.Map;

import kr.co.cking.ticket.application.TicketEarnLedgerService;
import kr.co.cking.ticket.application.dto.EarnCommand;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code stream:ticket-earned} 메시지를 {@link TicketEarnLedgerService}에 반영하고,
 * DB 반영이 끝난 뒤에만 XACK한다(명세: "DB Transaction Commit 이후에만 XACK").
 */
@Slf4j
@Component
public class EarnStreamListener implements StreamListener<String, MapRecord<String, String, String>> {

    private final TicketEarnLedgerService ticketEarnLedgerService;
    private final StringRedisTemplate redisTemplate;
    private final String streamKey;
    private final String consumerGroup;

    public EarnStreamListener(
            TicketEarnLedgerService ticketEarnLedgerService,
            StringRedisTemplate redisTemplate,
            @Value("${cking.ticket.earn-stream-key:stream:ticket-earned}") String streamKey,
            @Value("${cking.ticket.earn-consumer-group:cg:ticket-earn}") String consumerGroup
    ) {
        this.ticketEarnLedgerService = ticketEarnLedgerService;
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
        this.consumerGroup = consumerGroup;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        Map<String, String> fields = message.getValue();

        try {
            EarnCommand command = EarnCommand.fromStreamFields(fields);
            ticketEarnLedgerService.apply(command);
            redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, message.getId());
        } catch (Exception e) {
            // DB 반영에 실패했으므로 XACK하지 않는다 — 메시지는 PEL에 남아 재전달된다.
            log.error("EARN Stream 메시지 처리에 실패했습니다. id={}, fields={}", message.getId(), fields, e);
        }
    }
}

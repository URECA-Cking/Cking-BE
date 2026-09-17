package kr.co.cking.stream.presentation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.Map;

import kr.co.cking.ticket.application.TicketSpendLedgerService;
import kr.co.cking.ticket.application.dto.SpendCommand;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code stream:ticket-deducted} 메시지를 {@link TicketSpendLedgerService}에 반영하고,
 * DB 반영이 끝난 뒤에만 XACK한다(명세: "DB Transaction Commit 이후에만 XACK").
 * 마감 barrier 메시지({@code type=EVENT_ENTRY_CLOSED})는 DB 반영 없이 바로 XACK한다
 * (EventDrainChecker가 이 barrier를 cutoff로 취급하므로 소비·ACK는 되어야 한다).
 */
@Slf4j
@Component
public class SpendStreamListener implements StreamListener<String, MapRecord<String, String, String>> {

    private static final String BARRIER_TYPE_FIELD = "type";
    private static final String BARRIER_TYPE_VALUE = "EVENT_ENTRY_CLOSED";

    private final TicketSpendLedgerService ticketSpendLedgerService;
    private final StringRedisTemplate redisTemplate;
    private final String streamKey;
    private final String consumerGroup;

    public SpendStreamListener(
            TicketSpendLedgerService ticketSpendLedgerService,
            StringRedisTemplate redisTemplate,
            @Value("${cking.entry.stream-key:stream:ticket-deducted}") String streamKey,
            @Value("${cking.entry.history-consumer-group:cg:ticket-history}") String consumerGroup
    ) {
        this.ticketSpendLedgerService = ticketSpendLedgerService;
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
        this.consumerGroup = consumerGroup;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        process(message);
    }

    /**
     * DB 반영(또는 barrier 판별) + XACK을 수행한다.
     *
     * @return XACK까지 성공했으면 true, DB 반영에 실패해서 PEL에 남겨야 하면 false
     */
    public boolean process(MapRecord<String, String, String> message) {
        Map<String, String> fields = message.getValue();

        try {
            if (!BARRIER_TYPE_VALUE.equals(fields.get(BARRIER_TYPE_FIELD))) {
                SpendCommand command = SpendCommand.fromStreamFields(fields);
                ticketSpendLedgerService.apply(command);
            }
            redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, message.getId());
            return true;
        } catch (Exception e) {
            // DB 반영에 실패했으므로 XACK하지 않는다 — 메시지는 PEL에 남아 재전달된다.
            log.error("SPEND Stream 메시지 처리에 실패했습니다. id={}, fields={}", message.getId(), fields, e);
            return false;
        }
    }
}

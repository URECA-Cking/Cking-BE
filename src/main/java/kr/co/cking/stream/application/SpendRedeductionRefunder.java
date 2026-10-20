package kr.co.cking.stream.application;

import java.util.List;
import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.ticket.application.config.CommonTicketRedisKeys;
import kr.co.cking.ticket.application.dto.SpendCommand;
import kr.co.cking.ticket.domain.CouponType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 이미 반영된 requestId를 응모 Lua가 다시 차감한 메시지(#673)의 Redis 차감을 되돌리고 ACK한다.
 * 되돌리는 조건과 순서는 {@code entry-spend-refund.lua}에 있다.
 */
@Slf4j
@Component
public class SpendRedeductionRefunder {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<String> refundScript;
    private final String streamKey;
    private final String consumerGroup;

    public SpendRedeductionRefunder(
            StringRedisTemplate redisTemplate,
            @Value("${cking.entry.stream-key:stream:ticket-deducted}") String streamKey,
            @Value("${cking.entry.history-consumer-group:cg:ticket-history}") String consumerGroup
    ) {
        this.redisTemplate = redisTemplate;
        this.refundScript = new DefaultRedisScript<>();
        this.refundScript.setLocation(new ClassPathResource("scripts/entry-spend-refund.lua"));
        this.refundScript.setResultType(String.class);
        this.streamKey = streamKey;
        this.consumerGroup = consumerGroup;
    }

    /** ACK까지 했으면 true, 수동 보정 중이라 PEL에 남겨야 하면 false. */
    public boolean refundAndAcknowledge(SpendCommand command, RecordId messageId) {
        boolean common = command.couponType() == CouponType.COMMON;
        String balanceKey = common
                ? CommonTicketRedisKeys.balance(command.userId())
                : EntryRedisKeys.balance(command.creatorId(), command.userId());
        String maintenanceKey = common
                ? CommonTicketRedisKeys.maintenance(command.userId())
                : EntryRedisKeys.maintenance(command.creatorId(), command.userId());

        String result = redisTemplate.execute(refundScript,
                List.of(balanceKey, maintenanceKey, EntryRedisKeys.entryTotal(command.eventId()),
                        EntryRedisKeys.entrants(command.eventId()), streamKey),
                String.valueOf(command.ticketCount()), String.valueOf(command.userId()), consumerGroup,
                messageId.getValue());

        if ("MAINTENANCE".equals(result)) {
            log.info("수동 잔액 보정 중이라 다시 차감된 SPEND를 되돌리지 않고 PEL에 남깁니다. requestId={}, streamId={}",
                    command.requestId(), messageId.getValue());
            return false;
        }
        if ("ALREADY_ACKED".equals(result)) {
            log.info("다른 소비자가 이미 처리한 메시지라 되돌리지 않습니다. requestId={}, streamId={}",
                    command.requestId(), messageId.getValue());
            return true;
        }
        log.warn("다시 차감된 SPEND를 되돌렸습니다. result={}, requestId={}, userId={}, ticketCount={}, streamId={}",
                result, command.requestId(), command.userId(), command.ticketCount(), messageId.getValue());
        return true;
    }
}

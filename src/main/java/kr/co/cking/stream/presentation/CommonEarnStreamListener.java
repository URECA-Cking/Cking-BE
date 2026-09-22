package kr.co.cking.stream.presentation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.Map;

import kr.co.cking.ticket.application.CommonMissionEarnLedgerService;
import kr.co.cking.ticket.application.dto.CommonEarnCommand;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code stream:common-ticket-earned} 메시지를 {@link CommonMissionEarnLedgerService}에
 * 반영하고, DB 반영이 끝난 뒤에만 XACK한다(이슈 #219, {@code EarnStreamListener}와 동일 계약).
 */
@Slf4j
@Component
public class CommonEarnStreamListener implements StreamListener<String, MapRecord<String, String, String>> {

    private final CommonMissionEarnLedgerService commonMissionEarnLedgerService;
    private final StringRedisTemplate redisTemplate;
    private final String streamKey;
    private final String consumerGroup;

    public CommonEarnStreamListener(
            CommonMissionEarnLedgerService commonMissionEarnLedgerService,
            StringRedisTemplate redisTemplate,
            @Value("${cking.ticket.common-earn-stream-key:stream:common-ticket-earned}") String streamKey,
            @Value("${cking.ticket.common-earn-consumer-group:cg:common-ticket-earn}") String consumerGroup
    ) {
        this.commonMissionEarnLedgerService = commonMissionEarnLedgerService;
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
        this.consumerGroup = consumerGroup;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        Map<String, String> fields = message.getValue();

        try {
            CommonEarnCommand command = CommonEarnCommand.fromStreamFields(fields);
            commonMissionEarnLedgerService.apply(command);
            redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, message.getId());
        } catch (Exception e) {
            // DB 반영에 실패했으므로 XACK하지 않는다 — 메시지는 PEL에 남는다(회수 스케줄러는
            // 아직 없음 — CommonMissionEarnLedgerService 클래스 문서 참고).
            log.error("공용 EARN Stream 메시지 처리에 실패했습니다. id={}, fields={}", message.getId(), fields, e);
        }
    }
}

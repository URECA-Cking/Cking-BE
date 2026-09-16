package kr.co.cking.ticket.application;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis Balance 증가 + {@code stream:ticket-earned} 발행만 담당한다.
 *
 * <p>ponytail: Business Key(userId+creatorId+missionId+periodKey) 중복판정
 * 가드(이슈 #30, 자비님 담당)가 아직 없어서 이 구현은 {@code EARN_ACCEPTED}와
 * 시스템 오류만 반환한다 — {@code ALREADY_PROCESSED}/{@code DUPLICATE_MISSION}/
 * {@code REQUEST_ID_CONFLICT}는 가드 없이는 판정 자체가 불가능하다. 이슈 #30이
 * 끝나면 그 가드를 이 메서드 호출 앞단(또는 Lua 스크립트 통합)에 붙여야 한다.
 */
@Slf4j
@Service
public class TicketEarnServiceImpl implements TicketEarnService {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> ticketEarnLuaScript;
    private final String streamKey;

    public TicketEarnServiceImpl(
            StringRedisTemplate redisTemplate,
            @Qualifier("ticketEarnLuaScript") DefaultRedisScript<List> ticketEarnLuaScript,
            @Value("${cking.ticket.earn-stream-key:stream:ticket-earned}") String streamKey
    ) {
        this.redisTemplate = redisTemplate;
        this.ticketEarnLuaScript = ticketEarnLuaScript;
        this.streamKey = streamKey;
    }

    @Override
    public EarnResult earn(EarnCommand command) {
        String balanceKey = "ticket:balance:%d:%d".formatted(command.creatorId(), command.userId());
        List<?> result;

        try {
            result = redisTemplate.execute(
                    ticketEarnLuaScript,
                    List.of(balanceKey),
                    String.valueOf(command.amount()),
                    streamKey,
                    command.requestId().toString(),
                    String.valueOf(command.userId()),
                    String.valueOf(command.creatorId()),
                    command.missionType(),
                    String.valueOf(command.missionId()),
                    command.periodKey(),
                    command.missionKey()
            );
        } catch (DataAccessException e) {
            log.error("EARN Lua 실행 중 Redis 접근에 실패했습니다. requestId={}, userId={}",
                    command.requestId(), command.userId(), e);
            return new EarnResult(EarnResultCode.EARN_PROCESSING_FAILED);
        }

        if (result == null || result.isEmpty() || !"EARN_ACCEPTED".equals(result.get(0))) {
            log.error("알 수 없는 EARN Lua 결과입니다. requestId={}, result={}", command.requestId(), result);
            return new EarnResult(EarnResultCode.EARN_STATUS_UNKNOWN);
        }

        return new EarnResult(EarnResultCode.EARN_ACCEPTED);
    }
}

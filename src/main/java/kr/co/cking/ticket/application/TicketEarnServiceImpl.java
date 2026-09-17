package kr.co.cking.ticket.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import lombok.extern.slf4j.Slf4j;

/**
 * 멱등성 확인(FR-P1-017) + 중복 적립 가드(FR-P2-006) + Redis Balance 증가 +
 * {@code stream:ticket-earned} 발행을 {@code ticket-earn.lua} 하나로 원자 처리한다.
 */
@Slf4j
@Service
public class TicketEarnServiceImpl implements TicketEarnService {

    // FR-P1-017 확정값 (24시간)
    private static final long IDEM_TTL_SECONDS = 86_400L;
    // 팀 확정값 (25시간, PR #63 리뷰 반영) — 가드 키에 yyyyMMdd가 포함돼 자정 지나면
    // 자연 만료되지만, 서버 시간대 오차에 대비해 24시간+1시간 여유를 둔다.
    private static final long GUARD_TTL_SECONDS = 90_000L;

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
        String requestId = command.requestId().toString();
        String periodKeyGuardFormat = toGuardPeriodKey(command.periodKey());
        String fingerprint = computeFingerprint(command);
        List<?> result;

        try {
            result = redisTemplate.execute(
                    ticketEarnLuaScript,
                    List.of(
                            TicketRedisKeys.idemMission(requestId),
                            TicketRedisKeys.earnGuard(
                                    command.userId(), command.missionType(), command.creatorId(), periodKeyGuardFormat
                            ),
                            TicketRedisKeys.balance(command.creatorId(), command.userId())
                    ),
                    String.valueOf(command.amount()),
                    fingerprint,
                    streamKey,
                    String.valueOf(IDEM_TTL_SECONDS),
                    String.valueOf(GUARD_TTL_SECONDS),
                    requestId,
                    String.valueOf(command.userId()),
                    String.valueOf(command.creatorId()),
                    command.missionType(),
                    String.valueOf(command.missionId()),
                    command.periodKey(),
                    command.missionKey()
            );
        } catch (QueryTimeoutException e) {
            log.error("EARN Lua 실행이 타임아웃되어 처리 여부를 알 수 없습니다. requestId={}, userId={}",
                    command.requestId(), command.userId(), e);
            return new EarnResult(EarnResultCode.EARN_STATUS_UNKNOWN);
        } catch (DataAccessException e) {
            log.error("EARN Lua 실행 중 Redis 접근에 실패했습니다. requestId={}, userId={}",
                    command.requestId(), command.userId(), e);
            return new EarnResult(EarnResultCode.EARN_PROCESSING_FAILED);
        }

        return parse(result, command);
    }

    // periodKey 형식 검증과 가드 키 변환은 System 2(EARN) 책임이다(T1은 KST 기준
    // LocalDate.now()로 생성하지만, "2026-9-16"처럼 padding 없는 값은 여기서
    // strict하게 걸러 EARN Contract 경계에서 거부한다 - 2026-09-17 팀 결정).
    private String toGuardPeriodKey(String periodKey) {
        LocalDate date = LocalDate.parse(periodKey, DateTimeFormatter.ISO_LOCAL_DATE);
        return date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    // requestId를 제외한 요청 내용을 해시로 요약해 REQUEST_ID_CONFLICT 판정에 사용한다.
    private String computeFingerprint(EarnCommand command) {
        String payload = command.userId() + ":" + command.creatorId() + ":" + command.missionType()
                + ":" + command.missionId() + ":" + command.periodKey() + ":" + command.missionKey()
                + ":" + command.amount();

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private EarnResult parse(List<?> luaResult, EarnCommand command) {
        if (luaResult == null || luaResult.isEmpty()) {
            log.error("알 수 없는 EARN Lua 결과입니다. requestId={}, result={}", command.requestId(), luaResult);
            return new EarnResult(EarnResultCode.EARN_STATUS_UNKNOWN);
        }

        EarnResultCode code;

        try {
            code = EarnResultCode.valueOf(String.valueOf(luaResult.get(0)));
        } catch (IllegalArgumentException e) {
            log.error("알 수 없는 EARN Lua 결과코드입니다. requestId={}, result={}", command.requestId(), luaResult, e);
            return new EarnResult(EarnResultCode.EARN_STATUS_UNKNOWN);
        }

        return new EarnResult(code);
    }
}

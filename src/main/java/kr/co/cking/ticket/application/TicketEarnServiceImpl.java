package kr.co.cking.ticket.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 멱등성 확인(FR-P1-017) + 중복 적립 가드(FR-P2-006) + Redis Balance 증가 +
 * {@code stream:ticket-earned} 발행을 {@code ticket-earn.lua} 하나로 원자 처리한다.
 */
@Slf4j
@Service
public class TicketEarnServiceImpl implements TicketEarnService {

    // idem과 일일 Guard의 replay 보존 기간을 동일하게 유지한다.
    private static final long IDEM_TTL_SECONDS = 90_000L;
    // 24시간에 시간대 경계 여유를 더한 기간이다.
    private static final long GUARD_TTL_SECONDS = 90_000L;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> ticketEarnLuaScript;
    private final String streamKey;
    private final ObjectMapper objectMapper;

    public TicketEarnServiceImpl(
            StringRedisTemplate redisTemplate,
            @Qualifier("ticketEarnLuaScript") DefaultRedisScript<List> ticketEarnLuaScript,
            @Value("${cking.ticket.earn-stream-key:stream:ticket-earned}") String streamKey,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.ticketEarnLuaScript = ticketEarnLuaScript;
        this.streamKey = streamKey;
        this.objectMapper = objectMapper;
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
                            TicketRedisKeys.balance(command.creatorId(), command.userId()),
                            TicketRedisKeys.maintenanceLock(command.creatorId(), command.userId())
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

    @Override
    public EarnLookupResult findExisting(EarnCommand command) {
        String requestId = command.requestId().toString();
        String fingerprint = computeFingerprint(command);
        String stored;

        try {
            stored = redisTemplate.opsForValue().get(TicketRedisKeys.idemMission(requestId));
        } catch (DataAccessException e) {
            log.error("EARN replay 조회 중 Redis 접근에 실패했습니다. requestId={}, userId={}",
                    command.requestId(), command.userId(), e);
            return new EarnLookupResult(EarnLookupStatus.UNAVAILABLE);
        }

        if (stored == null) {
            return new EarnLookupResult(EarnLookupStatus.NOT_FOUND);
        }

        Map<String, Object> idemRecord;

        try {
            idemRecord = objectMapper.readValue(stored, new TypeReference<Map<String, Object>>() {
            });
        } catch (JacksonException e) {
            log.error("EARN idem 레코드를 역직렬화하지 못했습니다. requestId={}, userId={}",
                    command.requestId(), command.userId(), e);
            return new EarnLookupResult(EarnLookupStatus.UNAVAILABLE);
        }

        // 이전 형식에는 status가 없고 periodKey 포함 fingerprint를 사용했다.
        // 기존 record의 TTL 동안 완료된 성공으로만 취급한다.
        Object status = idemRecord.get("status");
        if (status == null && idemRecord.get("result") != null) {
            return new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED);
        }

        if (!fingerprint.equals(idemRecord.get("fingerprint"))) {
            return new EarnLookupResult(EarnLookupStatus.REQUEST_ID_CONFLICT);
        }
        if ("COMPLETED".equals(status)) {
            return new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED);
        }

        // PROCESSING은 저장된 Guard 키로 확인하고, 기존 레코드는 호출 시점 키를 쓴다.
        Object storedGuardKey = idemRecord.get("guardKey");
        String guardValue;

        try {
            String guardKey = storedGuardKey instanceof String s
                    ? s
                    : TicketRedisKeys.earnGuard(command.userId(), command.missionType(), command.creatorId(),
                            toGuardPeriodKey(command.periodKey()));
            guardValue = redisTemplate.opsForValue().get(guardKey);
        } catch (DataAccessException e) {
            log.error("EARN replay 조회 중 Guard 확인에 실패했습니다. requestId={}, userId={}",
                    command.requestId(), command.userId(), e);
            return new EarnLookupResult(EarnLookupStatus.UNAVAILABLE);
        }

        if ((requestId + ":" + fingerprint).equals(guardValue)) {
            return new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED);
        }

        // Guard가 없거나 다른 값이면 실제 성사 여부를 알 수 없으니 신규 지급을 막는다.
        return new EarnLookupResult(EarnLookupStatus.UNAVAILABLE);
    }

    // periodKey 형식 검증과 가드 키 변환은 System 2(EARN) 책임이다(T1은 UTC 기준
    // LocalDate.now()로 생성하지만, "2026-9-16"처럼 padding 없는 값은 여기서
    // strict하게 걸러 EARN Contract 경계에서 거부한다, 취합v1.5.4 §4.5). periodKey는
    // 서버가 만드므로 형식 오류는 사실상 버그일 때만 발생한다 - IllegalArgumentException으로
    // 던져 호출측(T1)이 공통 VALIDATION_FAILED(400)로 변환할 수 있게 한다.
    private String toGuardPeriodKey(String periodKey) {
        try {
            LocalDate date = LocalDate.parse(periodKey, DateTimeFormatter.ISO_LOCAL_DATE);
            return date.format(DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("periodKey는 yyyy-MM-dd 형식이어야 합니다: " + periodKey, e);
        }
    }

    // requestId를 제외한 요청 내용을 해시로 요약해 REQUEST_ID_CONFLICT 판정에 사용한다.
    // periodKey는 서버 파생값이므로 fingerprint에서 제외한다. 자정 이후 재시도는
    // 동일 요청으로 판정하며, periodKey는 일일 Guard와 MissionCompletion에만 사용한다.
    private String computeFingerprint(EarnCommand command) {
        String payload = command.userId() + ":" + command.creatorId() + ":" + command.missionType()
                + ":" + command.missionId() + ":" + command.missionKey() + ":" + command.amount();

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

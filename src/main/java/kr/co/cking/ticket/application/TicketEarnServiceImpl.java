package kr.co.cking.ticket.application;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
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
public class TicketEarnServiceImpl implements TicketEarnService, TicketEarnClaimedExecutor {

    // idem과 일일 Guard의 replay 보존 기간을 동일하게 유지한다.
    private static final long IDEM_TTL_SECONDS = 90_000L;
    // 24시간에 시간대 경계 여유를 더한 기간이다.
    private static final long GUARD_TTL_SECONDS = 90_000L;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> ticketEarnLuaScript;
    private final String streamKey;
    private final ObjectMapper objectMapper;
    private final TicketEarnRequestClaimService requestClaimService;

    public TicketEarnServiceImpl(
            StringRedisTemplate redisTemplate,
            @Qualifier("ticketEarnLuaScript") DefaultRedisScript<List> ticketEarnLuaScript,
            @Value("${cking.ticket.earn-stream-key:stream:ticket-earned}") String streamKey,
            ObjectMapper objectMapper
    ) {
        this(redisTemplate, ticketEarnLuaScript, streamKey, objectMapper, null);
    }

    @Autowired
    public TicketEarnServiceImpl(
            StringRedisTemplate redisTemplate,
            @Qualifier("ticketEarnLuaScript") DefaultRedisScript<List> ticketEarnLuaScript,
            @Value("${cking.ticket.earn-stream-key:stream:ticket-earned}") String streamKey,
            ObjectMapper objectMapper,
            TicketEarnRequestClaimService requestClaimService
    ) {
        this.redisTemplate = redisTemplate;
        this.ticketEarnLuaScript = ticketEarnLuaScript;
        this.streamKey = streamKey;
        this.objectMapper = objectMapper;
        this.requestClaimService = requestClaimService;
    }

    @Override
    public EarnResult earn(EarnCommand command) {
        EarnResult preClaimResult = preClaimResult(command);
        if (preClaimResult != null) {
            return preClaimResult;
        }

        return earnClaimed(command);
    }

    @Override
    public EarnResult earnClaimed(EarnCommand command) {
        String requestId = command.requestId().toString();
        String idemKey = idemKeyOf(command);
        String periodKeyGuardFormat = guardKeySegmentOf(command);
        String fingerprint = command.computeFingerprint();
        List<?> result;

        try {
            result = redisTemplate.execute(
                    ticketEarnLuaScript,
                    List.of(
                            idemKey,
                            TicketRedisKeys.earnGuard(
                                    command.userId(), command.missionType(), command.creatorId(), periodKeyGuardFormat
                            ),
                            TicketRedisKeys.balance(command.creatorId(), command.userId()),
                            TicketRedisKeys.maintenance(command.creatorId(), command.userId())
                    ),
                    String.valueOf(command.amount()),
                    fingerprint,
                    streamKey,
                    String.valueOf(idemTtlSecondsOf(command)),
                    String.valueOf(guardTtlSecondsOf(command)),
                    requestId,
                    String.valueOf(command.userId()),
                    String.valueOf(command.creatorId()),
                    command.missionType(),
                    String.valueOf(command.missionId()),
                    command.periodKey(),
                    command.missionKey(),
                    command.rewardPolicy().name()
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

        EarnResult earnResult = parse(result, command);
        if (requestClaimService != null && (earnResult.code() == EarnResultCode.EARN_ACCEPTED
                || earnResult.code() == EarnResultCode.ALREADY_PROCESSED)) {
            requestClaimService.accept(requestId);
        }
        return earnResult;
    }

    @Override
    public EarnLookupResult findExisting(EarnCommand command) {
        EarnLookupResult preClaimResult = findPreClaimResult(command);
        if (preClaimResult != null) {
            return preClaimResult;
        }

        return findExistingClaimed(command);
    }

    @Override
    public EarnLookupResult findExistingClaimed(EarnCommand command) {
        String requestId = command.requestId().toString();
        String fingerprint = command.computeFingerprint();
        String stored;

        try {
            stored = redisTemplate.opsForValue().get(idemKeyOf(command));
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
            acceptRequest(command);
            return new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED);
        }

        if (!fingerprint.equals(idemRecord.get("fingerprint"))) {
            return new EarnLookupResult(EarnLookupStatus.REQUEST_ID_CONFLICT);
        }
        if ("COMPLETED".equals(status)) {
            acceptRequest(command);
            return new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED);
        }

        // PROCESSING은 저장된 Guard 키로 확인하고, 기존 레코드는 호출 시점 키를 쓴다.
        Object storedGuardKey = idemRecord.get("guardKey");
        String guardValue;

        try {
            String guardKey = storedGuardKey instanceof String s
                    ? s
                    : TicketRedisKeys.earnGuard(command.userId(), command.missionType(), command.creatorId(),
                            guardKeySegmentOf(command));
            guardValue = redisTemplate.opsForValue().get(guardKey);
        } catch (DataAccessException e) {
            log.error("EARN replay 조회 중 Guard 확인에 실패했습니다. requestId={}, userId={}",
                    command.requestId(), command.userId(), e);
            return new EarnLookupResult(EarnLookupStatus.UNAVAILABLE);
        }

        if ((requestId + ":" + fingerprint).equals(guardValue)) {
            acceptRequest(command);
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

    /** 보상 정책에 맞는 Redis Guard 키의 마지막 세그먼트를 만든다. */
    private String guardKeySegmentOf(EarnCommand command) {
        return command.rewardPolicy().isOnce() ? "once" : toGuardPeriodKey(command.periodKey());
    }

    /** DAILY는 25시간, ONCE는 만료 없이 Redis Guard를 유지한다. */
    private long guardTtlSecondsOf(EarnCommand command) {
        return command.rewardPolicy().isOnce() ? 0L : GUARD_TTL_SECONDS;
    }

    private String idemKeyOf(EarnCommand command) {
        return command.rewardPolicy().isOnce()
                ? TicketRedisKeys.idemMissionOnce(command.requestId().toString())
                : TicketRedisKeys.idemMission(command.requestId().toString());
    }

    private long idemTtlSecondsOf(EarnCommand command) {
        return command.rewardPolicy().isOnce() ? 0L : IDEM_TTL_SECONDS;
    }

    private EarnResult preClaimResult(EarnCommand command) {
        if (requestClaimService == null) {
            return null;
        }
        return switch (requestClaimService.claim(command)) {
            case PENDING -> null;
            case ACCEPTED -> new EarnResult(EarnResultCode.ALREADY_PROCESSED);
            case REQUEST_ID_CONFLICT -> new EarnResult(EarnResultCode.REQUEST_ID_CONFLICT);
        };
    }

    private EarnLookupResult findPreClaimResult(EarnCommand command) {
        if (requestClaimService == null) {
            return null;
        }
        TicketEarnRequestClaim claim = requestClaimService.find(command);
        if (claim == null || claim == TicketEarnRequestClaim.PENDING) {
            return null;
        }
        return claim == TicketEarnRequestClaim.ACCEPTED
                ? new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED)
                : new EarnLookupResult(EarnLookupStatus.REQUEST_ID_CONFLICT);
    }

    private void acceptRequest(EarnCommand command) {
        if (requestClaimService != null) {
            requestClaimService.accept(command.requestId().toString());
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

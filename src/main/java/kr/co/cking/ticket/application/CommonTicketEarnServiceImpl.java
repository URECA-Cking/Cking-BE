package kr.co.cking.ticket.application;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import kr.co.cking.ticket.application.config.CommonTicketRedisKeys;
import kr.co.cking.ticket.application.dto.CommonEarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link TicketEarnServiceImpl}과 동일 원칙(멱등성 확인 + 중복 적립 가드 + Redis
 * Balance 증가 + Stream 발행을 {@code common-ticket-earn.lua} 하나로 원자 처리)을
 * 크리에이터 축 없이 제공한다(이슈 #219).
 */
@Slf4j
@Service
public class CommonTicketEarnServiceImpl implements CommonTicketEarnService {

    private static final long IDEM_TTL_SECONDS = 90_000L;
    private static final long GUARD_TTL_SECONDS = 90_000L;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> commonTicketEarnLuaScript;
    private final String streamKey;
    private final ObjectMapper objectMapper;
    private final TicketEarnRequestClaimService requestClaimService;

    public CommonTicketEarnServiceImpl(
            StringRedisTemplate redisTemplate,
            @Qualifier("commonTicketEarnLuaScript") DefaultRedisScript<List> commonTicketEarnLuaScript,
            @Value("${cking.ticket.common-earn-stream-key:stream:common-ticket-earned}") String streamKey,
            ObjectMapper objectMapper,
            TicketEarnRequestClaimService requestClaimService
    ) {
        this.redisTemplate = redisTemplate;
        this.commonTicketEarnLuaScript = commonTicketEarnLuaScript;
        this.streamKey = streamKey;
        this.objectMapper = objectMapper;
        this.requestClaimService = requestClaimService;
    }

    @Override
    public EarnResult earn(CommonEarnCommand command) {
        EarnResult preClaimResult = preClaimResult(command);
        if (preClaimResult != null) {
            return preClaimResult;
        }

        String requestId = command.requestId().toString();
        String periodKeyGuardFormat = toGuardPeriodKey(command.periodKey());
        String fingerprint = command.computeFingerprint();
        List<?> result;

        try {
            result = redisTemplate.execute(
                    commonTicketEarnLuaScript,
                    List.of(
                            CommonTicketRedisKeys.idemMission(requestId),
                            CommonTicketRedisKeys.earnGuard(command.userId(), command.missionType(), periodKeyGuardFormat),
                            CommonTicketRedisKeys.balance(command.userId()),
                            CommonTicketRedisKeys.maintenance(command.userId())
                    ),
                    String.valueOf(command.amount()),
                    fingerprint,
                    streamKey,
                    String.valueOf(IDEM_TTL_SECONDS),
                    String.valueOf(GUARD_TTL_SECONDS),
                    requestId,
                    String.valueOf(command.userId()),
                    command.missionType(),
                    String.valueOf(command.missionId()),
                    command.periodKey()
            );
        } catch (QueryTimeoutException e) {
            log.error("공용 EARN Lua 실행이 타임아웃되어 처리 여부를 알 수 없습니다. requestId={}, userId={}",
                    command.requestId(), command.userId(), e);
            return new EarnResult(EarnResultCode.EARN_STATUS_UNKNOWN);
        } catch (DataAccessException e) {
            log.error("공용 EARN Lua 실행 중 Redis 접근에 실패했습니다. requestId={}, userId={}",
                    command.requestId(), command.userId(), e);
            return new EarnResult(EarnResultCode.EARN_PROCESSING_FAILED);
        }

        EarnResult earnResult = parse(result, command);
        if (earnResult.code() == EarnResultCode.EARN_ACCEPTED || earnResult.code() == EarnResultCode.ALREADY_PROCESSED) {
            requestClaimService.accept(requestId);
        }
        return earnResult;
    }

    @Override
    public EarnLookupResult findExisting(CommonEarnCommand command) {
        EarnLookupResult preClaimResult = findPreClaimResult(command);
        if (preClaimResult != null) {
            return preClaimResult;
        }

        String requestId = command.requestId().toString();
        String fingerprint = command.computeFingerprint();
        String stored;

        try {
            stored = redisTemplate.opsForValue().get(CommonTicketRedisKeys.idemMission(requestId));
        } catch (DataAccessException e) {
            log.error("공용 EARN replay 조회 중 Redis 접근에 실패했습니다. requestId={}, userId={}",
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
            log.error("공용 EARN idem 레코드를 역직렬화하지 못했습니다. requestId={}, userId={}",
                    command.requestId(), command.userId(), e);
            return new EarnLookupResult(EarnLookupStatus.UNAVAILABLE);
        }

        if (!fingerprint.equals(idemRecord.get("fingerprint"))) {
            return new EarnLookupResult(EarnLookupStatus.REQUEST_ID_CONFLICT);
        }
        if ("COMPLETED".equals(idemRecord.get("status"))) {
            requestClaimService.accept(requestId);
            return new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED);
        }

        Object storedGuardKey = idemRecord.get("guardKey");
        String guardValue;

        try {
            String guardKey = storedGuardKey instanceof String s
                    ? s
                    : CommonTicketRedisKeys.earnGuard(command.userId(), command.missionType(),
                            toGuardPeriodKey(command.periodKey()));
            guardValue = redisTemplate.opsForValue().get(guardKey);
        } catch (DataAccessException e) {
            log.error("공용 EARN replay 조회 중 Guard 확인에 실패했습니다. requestId={}, userId={}",
                    command.requestId(), command.userId(), e);
            return new EarnLookupResult(EarnLookupStatus.UNAVAILABLE);
        }

        if ((requestId + ":" + fingerprint).equals(guardValue)) {
            requestClaimService.accept(requestId);
            return new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED);
        }

        return new EarnLookupResult(EarnLookupStatus.UNAVAILABLE);
    }

    private String toGuardPeriodKey(String periodKey) {
        try {
            LocalDate date = LocalDate.parse(periodKey, DateTimeFormatter.ISO_LOCAL_DATE);
            return date.format(DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("periodKey는 yyyy-MM-dd 형식이어야 합니다: " + periodKey, e);
        }
    }

    private EarnResult preClaimResult(CommonEarnCommand command) {
        return switch (requestClaimService.claim(command)) {
            case PENDING -> null;
            case ACCEPTED -> new EarnResult(EarnResultCode.ALREADY_PROCESSED);
            case REQUEST_ID_CONFLICT -> new EarnResult(EarnResultCode.REQUEST_ID_CONFLICT);
        };
    }

    private EarnLookupResult findPreClaimResult(CommonEarnCommand command) {
        TicketEarnRequestClaim claim = requestClaimService.find(command);
        if (claim == null || claim == TicketEarnRequestClaim.PENDING) {
            return null;
        }
        return claim == TicketEarnRequestClaim.ACCEPTED
                ? new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED)
                : new EarnLookupResult(EarnLookupStatus.REQUEST_ID_CONFLICT);
    }

    private EarnResult parse(List<?> luaResult, CommonEarnCommand command) {
        if (luaResult == null || luaResult.isEmpty()) {
            log.error("알 수 없는 공용 EARN Lua 결과입니다. requestId={}, result={}", command.requestId(), luaResult);
            return new EarnResult(EarnResultCode.EARN_STATUS_UNKNOWN);
        }

        EarnResultCode code;

        try {
            code = EarnResultCode.valueOf(String.valueOf(luaResult.get(0)));
        } catch (IllegalArgumentException e) {
            log.error("알 수 없는 공용 EARN Lua 결과코드입니다. requestId={}, result={}", command.requestId(), luaResult, e);
            return new EarnResult(EarnResultCode.EARN_STATUS_UNKNOWN);
        }

        return new EarnResult(code);
    }
}

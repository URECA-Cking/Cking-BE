package kr.co.cking.event.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.event.application.dto.EntrySpendResult;
import kr.co.cking.event.application.dto.enums.EntrySpendResultCode;
import kr.co.cking.ticket.application.config.CommonTicketRedisKeys;
import kr.co.cking.ticket.domain.CouponType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EntrySpendServiceImpl implements EntrySpendService {

    // FR-P2-033: idem TTL은 1시간이다.
    private static final long IDEM_TTL_SECONDS = 3600L;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> entrySpendLuaScript;

    public EntrySpendServiceImpl(
            StringRedisTemplate redisTemplate,
            @Qualifier("entrySpendLuaScript") DefaultRedisScript<List> entrySpendLuaScript
    ) {
        this.redisTemplate = redisTemplate;
        this.entrySpendLuaScript = entrySpendLuaScript;
    }

    // FR-P2-034 / 취합v1.5.4 §5.3 확정값. 실제 운영 키는 기본값 그대로 쓰고,
    // 테스트는 별도 키로 격리해서 이 키를 지우거나 타입을 바꾸는 조작이
    // 실제 stream:ticket-deducted에 영향을 주지 않도록 한다.
    @Value("${cking.entry.stream-key:stream:ticket-deducted}")
    private String streamKey;

    @Override
    public EntrySpendResult spend(
            Long eventId,
            Long userId,
            Long creatorId,
            String requestId,
            int ticketCount,
            CouponType couponType
    ) {
        String fingerprint = computeFingerprint(eventId, userId, ticketCount, couponType);
        // 이슈 #243: 어느 잔액·보정 락 키를 검증·차감할지는 이벤트가 아니라 요청의
        // couponType이 정한다. Lua에는 분기 로직이 없다 - Java가 이미 고른 키를 그대로 넘긴다.
        boolean common = couponType == CouponType.COMMON;
        String balanceKey = common ? CommonTicketRedisKeys.balance(userId) : EntryRedisKeys.balance(creatorId, userId);
        String maintenanceKey = common
                ? CommonTicketRedisKeys.maintenance(userId)
                : EntryRedisKeys.maintenance(creatorId, userId);
        List<?> luaResult;

        try {
            luaResult = redisTemplate.execute(
                    entrySpendLuaScript,
                    List.of(
                            EntryRedisKeys.status(eventId),
                            EntryRedisKeys.endAt(eventId),
                            balanceKey,
                            EntryRedisKeys.idem(requestId),
                            EntryRedisKeys.spendGuard(requestId),
                            maintenanceKey,
                            EntryRedisKeys.entryTotal(eventId),
                            EntryRedisKeys.entrants(eventId)
                    ),
                    String.valueOf(ticketCount),
                    fingerprint,
                    streamKey,
                    String.valueOf(IDEM_TTL_SECONDS),
                    String.valueOf(eventId),
                    String.valueOf(userId),
                    String.valueOf(creatorId),
                    requestId,
                    couponType.name()
            );
        } catch (DataAccessException e) {
            // Lua는 DECRBY/XADD 실패 시 잔액과 guard를 정리한 뒤 오류를 반환한다.
            // 타임아웃도 SYSTEM_ERROR다. Lua가 이미 차감했을 수 있어 클라이언트는 동일 requestId로 재시도한다.
            // 재시도는 이중 차감 없이 안전하나 결과는 그 시점의 코드를 따른다: docs/domains/event/lua-api.md
            log.error(
                    "응모 Lua 실행 중 Redis 접근에 실패했습니다. eventId={}, userId={}, requestId={}",
                    eventId, userId, requestId, e
            );
            return EntrySpendResult.of(EntrySpendResultCode.SYSTEM_ERROR);
        }

        return parse(luaResult, eventId, userId, requestId);
    }

    // FR-P2-029: 동일 requestId라도 요청 내용(eventId+userId+ticketCount)이 다르면
    // IDEMPOTENCY_CONFLICT로 구분해야 하므로, 그 내용을 요약한 값을 여기서 직접 계산한다.
    // 클라이언트는 이 값을 알거나 전달할 필요가 없다.
    // couponType은 CREATOR면 접미사를 붙이지 않는다(D5: 배포 직전 요청의 재시도가
    // IDEMPOTENCY_CONFLICT로 깨지지 않게 기존 fingerprint 값을 그대로 유지).
    private String computeFingerprint(Long eventId, Long userId, int ticketCount, CouponType couponType) {
        String payload = eventId + ":" + userId + ":" + ticketCount;
        if (couponType == CouponType.COMMON) {
            payload += ":COMMON";
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private EntrySpendResult parse(List<?> luaResult, Long eventId, Long userId, String requestId) {
        if (luaResult == null || luaResult.isEmpty()) {
            log.error(
                    "알 수 없는 응모 Lua 결과입니다. eventId={}, userId={}, requestId={}, result={}",
                    eventId, userId, requestId, luaResult
            );
            return EntrySpendResult.of(EntrySpendResultCode.SYSTEM_ERROR);
        }

        EntrySpendResultCode code;

        try {
            code = EntrySpendResultCode.valueOf(String.valueOf(luaResult.get(0)));
        } catch (IllegalArgumentException e) {
            log.error(
                    "알 수 없는 응모 Lua 결과코드입니다. eventId={}, userId={}, requestId={}, result={}",
                    eventId, userId, requestId, luaResult, e
            );
            return EntrySpendResult.of(EntrySpendResultCode.SYSTEM_ERROR);
        }

        return switch (code) {
            case SUCCESS -> EntrySpendResult.ofSuccess(
                    code,
                    String.valueOf(luaResult.get(1)),
                    Long.valueOf(String.valueOf(luaResult.get(2)))
            );
            // idem 경로는 상세 결과를 포함하고, guard 경로는 코드만 반환한다.
            case DUPLICATE_REPLAY -> luaResult.size() >= 3
                    ? EntrySpendResult.ofSuccess(
                            code,
                            String.valueOf(luaResult.get(1)),
                            Long.valueOf(String.valueOf(luaResult.get(2)))
                    )
                    : EntrySpendResult.of(code);
            case INSUFFICIENT_BALANCE -> EntrySpendResult.ofBalance(
                    code,
                    Long.valueOf(String.valueOf(luaResult.get(1)))
            );
            default -> EntrySpendResult.of(code);
        };
    }
}

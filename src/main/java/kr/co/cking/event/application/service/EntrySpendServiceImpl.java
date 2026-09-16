package kr.co.cking.event.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.event.application.dto.EntrySpendResult;
import kr.co.cking.event.application.dto.enums.EntrySpendResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EntrySpendServiceImpl implements EntrySpendService {

    // FR-P2-034 / 취합v1.5.4 §5.3 확정값
    private static final String STREAM_KEY = "stream:ticket-deducted";
    // FR-P2-033 확정값 (1시간)
    private static final long IDEM_TTL_SECONDS = 3600L;

    private final StringRedisTemplate redisTemplate;

    @Qualifier("entrySpendLuaScript")
    private final DefaultRedisScript<List> entrySpendLuaScript;

    @Override
    public EntrySpendResult spend(
            Long eventId,
            Long userId,
            Long creatorId,
            String requestId,
            int ticketCount
    ) {
        String fingerprint = computeFingerprint(eventId, userId, ticketCount);
        List<?> luaResult;

        try {
            luaResult = redisTemplate.execute(
                    entrySpendLuaScript,
                    List.of(
                            EntryRedisKeys.status(eventId),
                            EntryRedisKeys.endAt(eventId),
                            EntryRedisKeys.balance(creatorId, userId),
                            EntryRedisKeys.idem(requestId)
                    ),
                    String.valueOf(ticketCount),
                    fingerprint,
                    STREAM_KEY,
                    String.valueOf(IDEM_TTL_SECONDS),
                    String.valueOf(eventId),
                    String.valueOf(userId),
                    String.valueOf(creatorId),
                    requestId
            );
        } catch (DataAccessException e) {
            // entry-spend.lua는 XADD 실패 시 잔액을 보상한 뒤 error reply를 반환하므로,
            // 이 경로로 들어오는 실패는 이미 Redis 쪽 상태가 정리된 뒤다.
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
    private String computeFingerprint(Long eventId, Long userId, int ticketCount) {
        String payload = eventId + ":" + userId + ":" + ticketCount;

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
            case SUCCESS, DUPLICATE_REPLAY -> EntrySpendResult.ofSuccess(
                    code,
                    String.valueOf(luaResult.get(1)),
                    Long.valueOf(String.valueOf(luaResult.get(2)))
            );
            case INSUFFICIENT_BALANCE -> EntrySpendResult.ofBalance(
                    code,
                    Long.valueOf(String.valueOf(luaResult.get(1)))
            );
            default -> EntrySpendResult.of(code);
        };
    }
}

package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.ticket.application.TicketMaintenanceLock;
import kr.co.cking.event.application.dto.EntrySpendResult;
import kr.co.cking.event.application.dto.enums.EntrySpendResultCode;
import kr.co.cking.ticket.application.config.CommonTicketRedisKeys;
import kr.co.cking.ticket.domain.CouponType;

// entrySpendServiceImpl.streamKey(cking.entry.stream-key)를 테스트 전용 키로 오버라이드한다.
// 이 클래스는 그 키를 삭제하거나 타입을 바꾸는 조작(WRONGTYPE 유도 등)을 하므로,
// 실제 운영 키 stream:ticket-deducted와 절대 겹치면 안 된다.
@SpringBootTest(properties = "cking.entry.stream-key=stream:ticket-deducted:test")
class EntrySpendServiceIntegrationTest {

    private static final Long EVENT_ID = 90001L;
    private static final Long USER_ID = 90002L;
    private static final Long CREATOR_ID = 90003L;
    private static final String STREAM_KEY = "stream:ticket-deducted:test";

    private static final long FAR_FUTURE_MILLIS = 9_999_999_999_999L;
    private static final long PAST_MILLIS = 1_000_000_000_000L;

    @Autowired
    private EntrySpendService entrySpendService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TicketMaintenanceLock maintenanceLock;

    private static final List<String> REQUEST_IDS = List.of(
            "req-success",
            "req-duplicate",
            "req-conflict",
            "req-xadd-fail",
            "req-concurrent",
            "req-replay-after-close",
            "req-conflict-after-close",
            "req-misc",
            "req-guard-recovery",
            "req-guard-stuck",
            "req-guard-conflict",
            "req-decrby-fail",
            "req-guard-ttl",
            "req-maintenance-lock",
            "req-maintenance-lock-replay",
            "req-real-lock",
            "req-common",
            "req-common-only-creator-balance",
            "req-common-insufficient",
            "req-creator-only-common-balance"
    );

    @BeforeEach
    @AfterEach
    void cleanUp() {
        List<String> keys = new ArrayList<>(List.of(
                EntryRedisKeys.status(EVENT_ID),
                EntryRedisKeys.endAt(EVENT_ID),
                EntryRedisKeys.balance(CREATOR_ID, USER_ID),
                EntryRedisKeys.maintenance(CREATOR_ID, USER_ID),
                CommonTicketRedisKeys.balance(USER_ID),
                CommonTicketRedisKeys.maintenance(USER_ID),
                EntryRedisKeys.entryTotal(EVENT_ID),
                EntryRedisKeys.entrants(EVENT_ID),
                STREAM_KEY
        ));

        for (String requestId : REQUEST_IDS) {
            keys.add(EntryRedisKeys.idem(requestId));
            keys.add(EntryRedisKeys.spendGuard(requestId));
        }

        redisTemplate.delete(keys);
    }

    // Guard 상태를 직접 구성하기 위해 운영 코드와 같은 fingerprint를 계산한다.
    private String fingerprint(Long eventId, Long userId, int ticketCount) throws NoSuchAlgorithmException {
        String payload = eventId + ":" + userId + ":" + ticketCount;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));

        return HexFormat.of().formatHex(hash);
    }

    private void openGate() {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "OPEN");
        redisTemplate.opsForValue().set(EntryRedisKeys.endAt(EVENT_ID), String.valueOf(FAR_FUTURE_MILLIS));
    }

    @Test
    void Gate_키가_없으면_GATE_NOT_LOADED를_반환한다() {
        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-misc", 2, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.GATE_NOT_LOADED);
    }

    @Test
    void status가_CLOSED면_EVENT_NOT_OPEN을_반환한다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "CLOSED");
        redisTemplate.opsForValue().set(EntryRedisKeys.endAt(EVENT_ID), String.valueOf(FAR_FUTURE_MILLIS));

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-misc", 2, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.EVENT_NOT_OPEN);
    }

    @Test
    void endAt이_지났으면_EVENT_CLOSED를_반환한다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "OPEN");
        redisTemplate.opsForValue().set(EntryRedisKeys.endAt(EVENT_ID), String.valueOf(PAST_MILLIS));

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-misc", 2, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.EVENT_CLOSED);
    }

    @Test
    void ticketCount가_0이면_INVALID_TICKET_COUNT를_반환한다() {
        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-misc", 0, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.INVALID_TICKET_COUNT);
    }

    @Test
    void ticketCount가_101이면_INVALID_TICKET_COUNT를_반환한다() {
        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-misc", 101, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.INVALID_TICKET_COUNT);
    }

    @Test
    void balance_키가_없으면_BALANCE_NOT_LOADED를_반환한다() {
        openGate();

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-misc", 2, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.BALANCE_NOT_LOADED);
    }

    @Test
    void balance가_부족하면_INSUFFICIENT_BALANCE를_반환한다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "1");

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-misc", 5, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.INSUFFICIENT_BALANCE);
    }

    @Test
    void 정상_요청이면_SUCCESS와_차감된_잔액을_반환하고_idem_TTL과_Stream_필드까지_맞다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-success", 2, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(result.balance()).isEqualTo(8L);
        assertThat(result.streamId()).isNotBlank();
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("8");

        // FR-P2-033: idem 키 TTL이 1시간(3600초)으로 저장됐는지 확인
        Long ttl = redisTemplate.getExpire(EntryRedisKeys.idem("req-success"), TimeUnit.SECONDS);
        assertThat(ttl).isBetween(3590L, 3600L);

        // FR-P2-034: Stream에 실제로 올바른 필드 값이 들어갔는지 확인
        StreamOperations<String, String, String> streamOps = redisTemplate.opsForStream();
        List<MapRecord<String, String, String>> records =
                streamOps.range(STREAM_KEY, Range.just(result.streamId()));

        assertThat(records).hasSize(1);
        Map<String, String> fields = records.get(0).getValue();
        assertThat(fields.get("eventId")).isEqualTo(String.valueOf(EVENT_ID));
        assertThat(fields.get("userId")).isEqualTo(String.valueOf(USER_ID));
        assertThat(fields.get("creatorId")).isEqualTo(String.valueOf(CREATOR_ID));
        assertThat(fields.get("requestId")).isEqualTo("req-success");
        assertThat(fields.get("ticketCount")).isEqualTo("2");
    }

    // 이슈 #243: couponType=COMMON이면 크리에이터 잔액이 아니라 공용 잔액을 검증·차감한다.
    @Test
    void COMMON_요청은_공용_잔액을_차감하고_크리에이터_잔액은_그대로다() {
        openGate();
        redisTemplate.opsForValue().set(CommonTicketRedisKeys.balance(USER_ID), "10");
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "5");

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-common", 2, CouponType.COMMON);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(result.balance()).isEqualTo(8L);
        assertThat(redisTemplate.opsForValue().get(CommonTicketRedisKeys.balance(USER_ID))).isEqualTo("8");
        // 크리에이터 잔액은 COMMON 차감의 영향을 받지 않는다.
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("5");

        StreamOperations<String, String, String> streamOps = redisTemplate.opsForStream();
        List<MapRecord<String, String, String>> records =
                streamOps.range(STREAM_KEY, Range.just(result.streamId()));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getValue().get("couponType")).isEqualTo("COMMON");
    }

    // 자비 확인 동시성 시나리오(반대 방향): 크리에이터 응모권만 있고 공용 응모권이 없는
    // 사용자가 COMMON으로 응모하면 BALANCE_NOT_LOADED이고 크리에이터 잔액은 차감되지 않는다.
    @Test
    void 공용_잔액_없이_COMMON으로_응모하면_BALANCE_NOT_LOADED이고_크리에이터_잔액은_그대로다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "5");
        // CommonTicketRedisKeys.balance(USER_ID)는 의도적으로 세팅하지 않는다.

        EntrySpendResult result = entrySpendService.spend(
                EVENT_ID, USER_ID, CREATOR_ID, "req-common-only-creator-balance", 2, CouponType.COMMON);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.BALANCE_NOT_LOADED);
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("5");
    }

    // 이슈 #243 완료조건: 공용 잔액 키는 있지만 부족한 경우도 격리를 확인한다(키 부재와는
    // 다른 코드) - 크리에이터 잔액은 넉넉해도 COMMON 요청에는 영향을 주지 않는다.
    @Test
    void COMMON_잔액이_부족하면_INSUFFICIENT_BALANCE이고_크리에이터_잔액은_그대로다() {
        openGate();
        redisTemplate.opsForValue().set(CommonTicketRedisKeys.balance(USER_ID), "1");
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        EntrySpendResult result = entrySpendService.spend(
                EVENT_ID, USER_ID, CREATOR_ID, "req-common-insufficient", 5, CouponType.COMMON);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.INSUFFICIENT_BALANCE);
        assertThat(redisTemplate.opsForValue().get(CommonTicketRedisKeys.balance(USER_ID))).isEqualTo("1");
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("10");
    }

    // 반대 방향(자비 요청 동시성 시나리오의 비동시성 버전): 공용 잔액만 있고 크리에이터
    // 잔액 키가 없는 사용자가 CREATOR로 응모하면 BALANCE_NOT_LOADED이고 공용 잔액은 그대로다.
    @Test
    void 크리에이터_잔액_없이_CREATOR로_응모하면_BALANCE_NOT_LOADED이고_공용_잔액은_그대로다() {
        openGate();
        redisTemplate.opsForValue().set(CommonTicketRedisKeys.balance(USER_ID), "10");
        // EntryRedisKeys.balance(CREATOR_ID, USER_ID)는 의도적으로 세팅하지 않는다.

        EntrySpendResult result = entrySpendService.spend(
                EVENT_ID, USER_ID, CREATOR_ID, "req-creator-only-common-balance", 2, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.BALANCE_NOT_LOADED);
        assertThat(redisTemplate.opsForValue().get(CommonTicketRedisKeys.balance(USER_ID))).isEqualTo("10");
    }

    // FR-P2-045~050: 집계 키가 이미 적재돼 있으면 신규 SUCCESS 경로에서만 원자적으로 증가한다.
    @Test
    void 집계_키가_적재돼_있으면_SUCCESS_시_실시간_응모_현황이_증가한다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");
        redisTemplate.opsForValue().set(EntryRedisKeys.entryTotal(EVENT_ID), "3");
        redisTemplate.opsForHash().put(EntryRedisKeys.entrants(EVENT_ID), String.valueOf(USER_ID), "1");

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-success", 2, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.entryTotal(EVENT_ID))).isEqualTo("5");
        assertThat(redisTemplate.opsForHash().get(EntryRedisKeys.entrants(EVENT_ID), String.valueOf(USER_ID)))
                .isEqualTo("3");
    }

    // entrants(Hash)만 eviction 등으로 유실되고 entry-total은 살아있는 desync 상황.
    // HINCRBY가 entrants를 이번 요청 하나로 조용히 재생성하면 과거 참여자 기록이
    // 사라지므로, 대신 entry-total도 함께 지워 이후 조회가 DB 집계로 전부 대체되게 한다.
    @Test
    void entrants만_유실되면_증가시키지_않고_entry_total도_정리한다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");
        redisTemplate.opsForValue().set(EntryRedisKeys.entryTotal(EVENT_ID), "3");
        // entrants는 의도적으로 세팅하지 않는다 - eviction으로 사라진 상태를 흉내낸다.

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-success", 2, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(redisTemplate.hasKey(EntryRedisKeys.entryTotal(EVENT_ID))).isFalse();
        assertThat(redisTemplate.hasKey(EntryRedisKeys.entrants(EVENT_ID))).isFalse();
    }

    // 집계 키 미적재(배포 시점 OPEN 이벤트 등)는 "0으로 간주 금지" 원칙에 따라 새로 만들지 않는다.
    @Test
    void 집계_키가_없으면_SUCCESS_해도_새로_만들지_않는다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-success", 2, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(redisTemplate.hasKey(EntryRedisKeys.entryTotal(EVENT_ID))).isFalse();
        assertThat(redisTemplate.hasKey(EntryRedisKeys.entrants(EVENT_ID))).isFalse();
    }

    // 동일 requestId 재전송(DUPLICATE_REPLAY)은 재차감이 없으므로 집계도 다시 증가하지 않는다.
    @Test
    void DUPLICATE_REPLAY는_집계를_다시_증가시키지_않는다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");
        redisTemplate.opsForValue().set(EntryRedisKeys.entryTotal(EVENT_ID), "0");

        entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-success", 2, CouponType.CREATOR);
        EntrySpendResult replay = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-success", 2, CouponType.CREATOR);

        assertThat(replay.code()).isEqualTo(EntrySpendResultCode.DUPLICATE_REPLAY);
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.entryTotal(EVENT_ID))).isEqualTo("2");
    }

    // issue #106: guard는 idemTtl이 아니라 endAt까지 유지돼야 한다 - idemTtl로
    // 통일하면 이벤트가 1시간보다 오래 열려 있을 때 idem 저장 실패 후 guard까지
    // 만료돼 재차감이 다시 가능해진다. endAt을 idemTtl보다 먼 2시간 뒤로 잡아
    // guard TTL이 그만큼 유지되는지 확인한다.
    @Test
    void guard_TTL은_idemTtl이_아니라_이벤트_종료_시각까지_유지된다() {
        long endAtMillis = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2);
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "OPEN");
        redisTemplate.opsForValue().set(EntryRedisKeys.endAt(EVENT_ID), String.valueOf(endAtMillis));
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-guard-ttl", 2, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.SUCCESS);

        // idemTtl(3600초)보다 길게 유지되어야 하며, endAt(2시간 뒤)에 맞춰 7200초 근방이어야 한다.
        Long guardTtl = redisTemplate.getExpire(EntryRedisKeys.spendGuard("req-guard-ttl"), TimeUnit.SECONDS);
        assertThat(guardTtl).isGreaterThan(3600L);
        assertThat(guardTtl).isBetween(7190L, 7200L);
    }

    @Test
    void 같은_요청을_재시도하면_DUPLICATE_REPLAY로_기존_결과를_반환한다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        EntrySpendResult first = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-duplicate", 2, CouponType.CREATOR);
        EntrySpendResult retry = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-duplicate", 2, CouponType.CREATOR);

        assertThat(first.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(retry.code()).isEqualTo(EntrySpendResultCode.DUPLICATE_REPLAY);
        assertThat(retry.streamId()).isEqualTo(first.streamId());
        // 재차감되지 않아야 한다 (10 - 2 = 8 그대로 유지)
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("8");
    }

    @Test
    void 같은_requestId에_다른_ticketCount면_IDEMPOTENCY_CONFLICT를_반환한다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        EntrySpendResult first = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-conflict", 2, CouponType.CREATOR);
        EntrySpendResult conflicting = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-conflict", 3, CouponType.CREATOR);

        assertThat(first.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(conflicting.code()).isEqualTo(EntrySpendResultCode.IDEMPOTENCY_CONFLICT);
    }

    // issue #29/#36: 성공 응답을 못 받은 클라이언트가 재시도했는데 그 사이 이벤트가
    // 마감된 경우, Gate 상태와 무관하게 기존 성공 결과가 그대로 재현돼야 한다.
    @Test
    void 성공한_요청은_이벤트_마감_후_재시도해도_DUPLICATE_REPLAY를_반환한다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        EntrySpendResult first = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-replay-after-close", 2, CouponType.CREATOR);
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "CLOSED");

        EntrySpendResult retryAfterClose =
                entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-replay-after-close", 2, CouponType.CREATOR);

        assertThat(first.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(retryAfterClose.code()).isEqualTo(EntrySpendResultCode.DUPLICATE_REPLAY);
        assertThat(retryAfterClose.streamId()).isEqualTo(first.streamId());
    }

    // 마감 여부와 무관하게 payload 불일치는 여전히 IDEMPOTENCY_CONFLICT여야 한다.
    @Test
    void 마감_후에도_같은_requestId에_다른_ticketCount면_IDEMPOTENCY_CONFLICT를_반환한다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        EntrySpendResult first = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-conflict-after-close", 2, CouponType.CREATOR);
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "CLOSED");

        EntrySpendResult conflicting =
                entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-conflict-after-close", 3, CouponType.CREATOR);

        assertThat(first.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(conflicting.code()).isEqualTo(EntrySpendResultCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void XADD가_실패하면_SYSTEM_ERROR를_반환하고_잔액을_보상한다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");
        // 격리된 테스트 전용 stream 키를 STRING 타입으로 선점시켜 XADD가 WRONGTYPE로 실패하도록 유도한다.
        redisTemplate.opsForValue().set(STREAM_KEY, "not-a-stream");

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-xadd-fail", 2, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.SYSTEM_ERROR);
        // DECRBY가 INCRBY로 보상되어 원래 잔액(10)이 그대로 유지되어야 한다.
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("10");
        assertThat(redisTemplate.hasKey(EntryRedisKeys.idem("req-xadd-fail"))).isFalse();
        // 실패한 요청은 guard 없이 다시 시도할 수 있어야 한다.
        assertThat(redisTemplate.hasKey(EntryRedisKeys.spendGuard("req-xadd-fail"))).isFalse();
    }

    // 실제 차감에 실패한 요청은 guard 없이 다시 시도할 수 있어야 한다.
    @Test
    void Balance가_정수가_아니면_DECRBY_실패_시_guard가_남지_않고_보정_후_재시도가_성공한다() {
        openGate();
        // ticketCount(2)보다 큰 값을 써서 INSUFFICIENT_BALANCE 분기(1.5 < 2)를 피한다.
        // tonumber("10.5")는 10.5로 파싱되어 Balance 확인은 통과하지만, Redis DECRBY는
        // 정수 문자열만 허용하므로 여기서 실제로 실패한다.
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10.5");

        EntrySpendResult failed = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-decrby-fail", 2, CouponType.CREATOR);

        assertThat(failed.code()).isEqualTo(EntrySpendResultCode.SYSTEM_ERROR);
        assertThat(redisTemplate.hasKey(EntryRedisKeys.spendGuard("req-decrby-fail"))).isFalse();
        assertThat(redisTemplate.hasKey(EntryRedisKeys.idem("req-decrby-fail"))).isFalse();

        // Balance를 정상값으로 고치면, 같은 requestId로도 처음부터 다시 성공해야 한다.
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        EntrySpendResult retry = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-decrby-fail", 2, CouponType.CREATOR);

        assertThat(retry.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(retry.balance()).isEqualTo(8L);
    }

    // idem이 없더라도 guard가 같은 요청의 재차감을 막아야 한다.
    @Test
    void idem_키가_유실돼도_guard가_DUPLICATE_REPLAY로_막고_잔액을_다시_깎지_않는다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        EntrySpendResult first = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-guard-recovery", 2, CouponType.CREATOR);
        redisTemplate.delete(EntryRedisKeys.idem("req-guard-recovery"));

        EntrySpendResult retry = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-guard-recovery", 2, CouponType.CREATOR);

        assertThat(first.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(retry.code()).isEqualTo(EntrySpendResultCode.DUPLICATE_REPLAY);
        assertThat(retry.streamId()).isNull();
        assertThat(retry.balance()).isNull();
        // 잔액이 재차감되지 않아야 한다 (10 - 2 = 8 그대로 유지)
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("8");
    }

    // Guard만 남아 있으면 동일 요청은 재처리하지 않는다.
    @Test
    void idem_없이_guard만_있으면_DUPLICATE_REPLAY를_반환하고_잔액을_건드리지_않는다()
            throws NoSuchAlgorithmException {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");
        String fp = fingerprint(EVENT_ID, USER_ID, 2);
        redisTemplate.opsForValue().set(
                EntryRedisKeys.spendGuard("req-guard-stuck"),
                "{\"fingerprint\":\"" + fp + "\"}"
        );

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-guard-stuck", 2, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.DUPLICATE_REPLAY);
        assertThat(result.streamId()).isNull();
        assertThat(result.balance()).isNull();
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("10");
    }

    // 같은 requestId라도 요청 내용이 다르면 충돌로 처리한다.
    @Test
    void idem_없이_guard의_fingerprint가_다르면_IDEMPOTENCY_CONFLICT를_반환한다()
            throws NoSuchAlgorithmException {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");
        String differentFingerprint = fingerprint(EVENT_ID, USER_ID, 3);
        redisTemplate.opsForValue().set(
                EntryRedisKeys.spendGuard("req-guard-conflict"),
                "{\"fingerprint\":\"" + differentFingerprint + "\"}"
        );

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-guard-conflict", 2, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.IDEMPOTENCY_CONFLICT);
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("10");
    }

    // 동시에 같은 요청이 들어와도 차감과 Stream 발행은 한 번만 일어나야 한다.
    @Test
    void 동시에_같은_요청이_들어와도_차감과_XADD가_한_번만_일어난다() throws InterruptedException {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<EntrySpendResult>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    return entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-concurrent", 2, CouponType.CREATOR);
                }));
            }

            startLatch.countDown();

            long successCount = 0;

            for (Future<EntrySpendResult> future : futures) {
                EntrySpendResult result = future.get();

                if (result.code() == EntrySpendResultCode.SUCCESS) {
                    successCount++;
                } else {
                    assertThat(result.code()).isEqualTo(EntrySpendResultCode.DUPLICATE_REPLAY);
                }
            }

            assertThat(successCount).isEqualTo(1);
            assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("8");
            // 잔액이 한 번만 깎인 것만으로는 "XADD도 한 번만 됐다"는 걸 증명하지 못하므로,
            // Stream 레코드 수 자체도 정확히 1건인지 확인한다.
            assertThat(redisTemplate.opsForStream().size(STREAM_KEY)).isEqualTo(1L);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        } finally {
            executor.shutdownNow();
        }
    }

    // issue #172: 수동 보정(resyncRedisToDb) 중에는 신규 차감을 막아야 한다.
    @Test
    void 수동_보정_락이_걸려있으면_BALANCE_MAINTENANCE를_반환하고_잔액을_건드리지_않는다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");
        redisTemplate.opsForValue().set(EntryRedisKeys.maintenance(CREATOR_ID, USER_ID), "locked");

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-maintenance-lock", 2, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.BALANCE_MAINTENANCE);
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("10");
        assertThat(redisTemplate.hasKey(EntryRedisKeys.idem("req-maintenance-lock"))).isFalse();
        assertThat(redisTemplate.hasKey(EntryRedisKeys.spendGuard("req-maintenance-lock"))).isFalse();
    }

    // 이미 성공한 요청의 replay는 보정 락과 무관하게 기존 결과를 그대로 재현해야 한다 -
    // idem/guard 확인이 락 확인보다 먼저 실행되므로 replay는 락에 막히지 않는다.
    @Test
    void 수동_보정_락이_걸려있어도_이미_완료된_요청은_DUPLICATE_REPLAY로_재현된다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        EntrySpendResult first =
                entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-maintenance-lock-replay", 2, CouponType.CREATOR);
        redisTemplate.opsForValue().set(EntryRedisKeys.maintenance(CREATOR_ID, USER_ID), "locked");

        EntrySpendResult retry =
                entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-maintenance-lock-replay", 2, CouponType.CREATOR);

        assertThat(first.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(retry.code()).isEqualTo(EntrySpendResultCode.DUPLICATE_REPLAY);
        assertThat(retry.streamId()).isEqualTo(first.streamId());
    }

    // 보정 서비스가 쓰는 TicketMaintenanceLock이 잡은 실제 lock과 SPEND Lua가 같은 키를 보는지 확인한다.
    // 키 함수나 형식이 한쪽만 바뀌면 lock이 조용히 무력화되므로 두 PR이 맞물리는 지점을 고정한다.
    @Test
    void TicketMaintenanceLock이_잡은_lock은_SPEND를_막고_해제하면_통과시킨다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        String token = maintenanceLock.acquire(CREATOR_ID, USER_ID);
        EntrySpendResult blocked = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-real-lock", 2, CouponType.CREATOR);
        maintenanceLock.release(CREATOR_ID, USER_ID, token);
        EntrySpendResult passed = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-real-lock", 2, CouponType.CREATOR);

        assertThat(blocked.code()).isEqualTo(EntrySpendResultCode.BALANCE_MAINTENANCE);
        assertThat(passed.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(passed.balance()).isEqualTo(8L);
    }

    // 문서(event/lua-api.md)가 약속하는 동작: 락이 풀린 뒤 같은 requestId로 재시도하면
    // BALANCE_MAINTENANCE가 아니라 신규 요청과 동일하게 처리되어 성공한다.
    @Test
    void 수동_보정_락이_풀리면_같은_requestId로_재시도해도_SUCCESS를_반환한다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");
        redisTemplate.opsForValue().set(EntryRedisKeys.maintenance(CREATOR_ID, USER_ID), "locked");

        EntrySpendResult blocked =
                entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-maintenance-lock", 2, CouponType.CREATOR);
        assertThat(blocked.code()).isEqualTo(EntrySpendResultCode.BALANCE_MAINTENANCE);

        redisTemplate.delete(EntryRedisKeys.maintenance(CREATOR_ID, USER_ID));
        EntrySpendResult retried =
                entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-maintenance-lock", 2, CouponType.CREATOR);

        assertThat(retried.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(retried.balance()).isEqualTo(8L);
    }
}

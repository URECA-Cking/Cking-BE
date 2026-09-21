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
import kr.co.cking.event.application.dto.EntrySpendResult;
import kr.co.cking.event.application.dto.enums.EntrySpendResultCode;

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
            "req-maintenance-lock-replay"
    );

    @BeforeEach
    @AfterEach
    void cleanUp() {
        List<String> keys = new ArrayList<>(List.of(
                EntryRedisKeys.status(EVENT_ID),
                EntryRedisKeys.endAt(EVENT_ID),
                EntryRedisKeys.balance(CREATOR_ID, USER_ID),
                EntryRedisKeys.maintenanceLock(CREATOR_ID, USER_ID),
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
        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-misc", 2);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.GATE_NOT_LOADED);
    }

    @Test
    void status가_CLOSED면_EVENT_NOT_OPEN을_반환한다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "CLOSED");
        redisTemplate.opsForValue().set(EntryRedisKeys.endAt(EVENT_ID), String.valueOf(FAR_FUTURE_MILLIS));

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-misc", 2);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.EVENT_NOT_OPEN);
    }

    @Test
    void endAt이_지났으면_EVENT_CLOSED를_반환한다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "OPEN");
        redisTemplate.opsForValue().set(EntryRedisKeys.endAt(EVENT_ID), String.valueOf(PAST_MILLIS));

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-misc", 2);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.EVENT_CLOSED);
    }

    @Test
    void ticketCount가_0이면_INVALID_TICKET_COUNT를_반환한다() {
        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-misc", 0);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.INVALID_TICKET_COUNT);
    }

    @Test
    void ticketCount가_101이면_INVALID_TICKET_COUNT를_반환한다() {
        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-misc", 101);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.INVALID_TICKET_COUNT);
    }

    @Test
    void balance_키가_없으면_BALANCE_NOT_LOADED를_반환한다() {
        openGate();

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-misc", 2);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.BALANCE_NOT_LOADED);
    }

    @Test
    void balance가_부족하면_INSUFFICIENT_BALANCE를_반환한다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "1");

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-misc", 5);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.INSUFFICIENT_BALANCE);
    }

    @Test
    void 정상_요청이면_SUCCESS와_차감된_잔액을_반환하고_idem_TTL과_Stream_필드까지_맞다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-success", 2);

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

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-guard-ttl", 2);

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

        EntrySpendResult first = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-duplicate", 2);
        EntrySpendResult retry = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-duplicate", 2);

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

        EntrySpendResult first = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-conflict", 2);
        EntrySpendResult conflicting = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-conflict", 3);

        assertThat(first.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(conflicting.code()).isEqualTo(EntrySpendResultCode.IDEMPOTENCY_CONFLICT);
    }

    // issue #29/#36: 성공 응답을 못 받은 클라이언트가 재시도했는데 그 사이 이벤트가
    // 마감된 경우, Gate 상태와 무관하게 기존 성공 결과가 그대로 재현돼야 한다.
    @Test
    void 성공한_요청은_이벤트_마감_후_재시도해도_DUPLICATE_REPLAY를_반환한다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        EntrySpendResult first = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-replay-after-close", 2);
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "CLOSED");

        EntrySpendResult retryAfterClose =
                entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-replay-after-close", 2);

        assertThat(first.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(retryAfterClose.code()).isEqualTo(EntrySpendResultCode.DUPLICATE_REPLAY);
        assertThat(retryAfterClose.streamId()).isEqualTo(first.streamId());
    }

    // 마감 여부와 무관하게 payload 불일치는 여전히 IDEMPOTENCY_CONFLICT여야 한다.
    @Test
    void 마감_후에도_같은_requestId에_다른_ticketCount면_IDEMPOTENCY_CONFLICT를_반환한다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        EntrySpendResult first = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-conflict-after-close", 2);
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "CLOSED");

        EntrySpendResult conflicting =
                entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-conflict-after-close", 3);

        assertThat(first.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(conflicting.code()).isEqualTo(EntrySpendResultCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void XADD가_실패하면_SYSTEM_ERROR를_반환하고_잔액을_보상한다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");
        // 격리된 테스트 전용 stream 키를 STRING 타입으로 선점시켜 XADD가 WRONGTYPE로 실패하도록 유도한다.
        redisTemplate.opsForValue().set(STREAM_KEY, "not-a-stream");

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-xadd-fail", 2);

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

        EntrySpendResult failed = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-decrby-fail", 2);

        assertThat(failed.code()).isEqualTo(EntrySpendResultCode.SYSTEM_ERROR);
        assertThat(redisTemplate.hasKey(EntryRedisKeys.spendGuard("req-decrby-fail"))).isFalse();
        assertThat(redisTemplate.hasKey(EntryRedisKeys.idem("req-decrby-fail"))).isFalse();

        // Balance를 정상값으로 고치면, 같은 requestId로도 처음부터 다시 성공해야 한다.
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        EntrySpendResult retry = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-decrby-fail", 2);

        assertThat(retry.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(retry.balance()).isEqualTo(8L);
    }

    // idem이 없더라도 guard가 같은 요청의 재차감을 막아야 한다.
    @Test
    void idem_키가_유실돼도_guard가_DUPLICATE_REPLAY로_막고_잔액을_다시_깎지_않는다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");

        EntrySpendResult first = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-guard-recovery", 2);
        redisTemplate.delete(EntryRedisKeys.idem("req-guard-recovery"));

        EntrySpendResult retry = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-guard-recovery", 2);

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

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-guard-stuck", 2);

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

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-guard-conflict", 2);

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
                    return entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-concurrent", 2);
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
    // BALANCE_MAINTENANCE(HTTP 503)는 EntrySpendResultCode에 추가될 새 코드다 -
    // TicketCompensationService 쪽 PR이 그 enum 값과 HTTP 매핑을 추가하기 전까지는
    // 이 테스트가 컴파일되지 않는다(합의된 순서).
    @Test
    void 수동_보정_락이_걸려있으면_BALANCE_MAINTENANCE를_반환하고_잔액을_건드리지_않는다() {
        openGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), "10");
        redisTemplate.opsForValue().set(EntryRedisKeys.maintenanceLock(CREATOR_ID, USER_ID), "locked");

        EntrySpendResult result = entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-maintenance-lock", 2);

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
                entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-maintenance-lock-replay", 2);
        redisTemplate.opsForValue().set(EntryRedisKeys.maintenanceLock(CREATOR_ID, USER_ID), "locked");

        EntrySpendResult retry =
                entrySpendService.spend(EVENT_ID, USER_ID, CREATOR_ID, "req-maintenance-lock-replay", 2);

        assertThat(first.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(retry.code()).isEqualTo(EntrySpendResultCode.DUPLICATE_REPLAY);
        assertThat(retry.streamId()).isEqualTo(first.streamId());
    }
}

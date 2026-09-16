package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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

    @BeforeEach
    @AfterEach
    void cleanUp() {
        redisTemplate.delete(List.of(
                EntryRedisKeys.status(EVENT_ID),
                EntryRedisKeys.endAt(EVENT_ID),
                EntryRedisKeys.balance(CREATOR_ID, USER_ID),
                EntryRedisKeys.idem("req-success"),
                EntryRedisKeys.idem("req-duplicate"),
                EntryRedisKeys.idem("req-conflict"),
                EntryRedisKeys.idem("req-xadd-fail"),
                EntryRedisKeys.idem("req-concurrent"),
                EntryRedisKeys.idem("req-replay-after-close"),
                EntryRedisKeys.idem("req-conflict-after-close"),
                EntryRedisKeys.idem("req-misc"),
                STREAM_KEY
        ));
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
    }

    // FR-P2-030/FR-P2-043: RTM에 "코드 리뷰로 대체 불가"로 명시된 항목 - 동일 requestId로
    // 동시에 여러 요청이 들어와도 Lua의 원자성 덕분에 차감과 XADD가 딱 한 번만 일어나야 한다.
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
}

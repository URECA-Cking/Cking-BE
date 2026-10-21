package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.event.application.dto.EntrySpendResult;
import kr.co.cking.event.application.dto.enums.EntrySpendResultCode;
import kr.co.cking.ticket.domain.CouponType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 설정으로 켜면 실제 응모 경로가 복제 확인을 거친다(#674). 로컬 Redis에는 복제 노드가 없어 SUCCESS는 제한 시간 뒤
 * 확인 못함으로 세고, 응모 결과는 그대로 SUCCESS다.
 */
@SpringBootTest(properties = {
        "cking.entry.stream-key=stream:ticket-deducted:replication-test",
        "cking.entry.replication-wait.enabled=true",
        "cking.entry.replication-wait.timeout-ms=50"
})
class EntrySpendReplicationWaitIntegrationTest {

    private static final Long EVENT_ID = 91001L;
    private static final Long USER_ID = 91002L;
    private static final Long CREATOR_ID = 91003L;
    private static final String STREAM_KEY = "stream:ticket-deducted:replication-test";
    private static final List<String> REQUEST_IDS = List.of("req-replication-success", "req-replication-insufficient");

    @Autowired private EntrySpendService entrySpendService;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private MeterRegistry meterRegistry;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        redisTemplate.delete(List.of(
                EntryRedisKeys.status(EVENT_ID),
                EntryRedisKeys.endAt(EVENT_ID),
                EntryRedisKeys.balance(CREATOR_ID, USER_ID),
                EntryRedisKeys.entryTotal(EVENT_ID),
                EntryRedisKeys.entrants(EVENT_ID),
                STREAM_KEY));
        REQUEST_IDS.forEach(requestId -> redisTemplate.delete(List.of(
                EntryRedisKeys.idem(requestId), EntryRedisKeys.spendGuard(requestId))));
    }

    @Test
    void 성공한_응모는_복제를_기다리고_확인하지_못해도_SUCCESS를_돌려준다() {
        openGateWithBalance("10");
        double before = count("unconfirmed");

        EntrySpendResult result = entrySpendService.spend(
                EVENT_ID, USER_ID, CREATOR_ID, "req-replication-success", 2, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.SUCCESS);
        assertThat(result.balance()).isEqualTo(8L);
        assertThat(count("unconfirmed") - before).isEqualTo(1);
    }

    @Test
    void 실패한_응모는_복제를_기다리지_않는다() {
        openGateWithBalance("1");
        double before = count("unconfirmed") + count("confirmed");

        EntrySpendResult result = entrySpendService.spend(
                EVENT_ID, USER_ID, CREATOR_ID, "req-replication-insufficient", 2, CouponType.CREATOR);

        assertThat(result.code()).isEqualTo(EntrySpendResultCode.INSUFFICIENT_BALANCE);
        assertThat(count("unconfirmed") + count("confirmed") - before).isZero();
    }

    private void openGateWithBalance(String balance) {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "OPEN");
        redisTemplate.opsForValue().set(EntryRedisKeys.endAt(EVENT_ID), "9999999999999");
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, USER_ID), balance);
    }

    private double count(String result) {
        return meterRegistry.get(EntryReplicationWait.METRIC).tag("result", result).timer().count();
    }
}

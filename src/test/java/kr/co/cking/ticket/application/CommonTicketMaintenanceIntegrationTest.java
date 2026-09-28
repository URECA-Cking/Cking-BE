package kr.co.cking.ticket.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import kr.co.cking.ticket.application.config.CommonTicketRedisKeys;
import kr.co.cking.ticket.application.dto.CommonEarnCommand;
import kr.co.cking.ticket.application.dto.EarnResultCode;

/** 이슈 #256: 공용 보정 락이 실제 Redis에서 공용 EARN Lua와 락 헬퍼에 반영되는지 검증한다. */
@SpringBootTest
class CommonTicketMaintenanceIntegrationTest {

    private static final Long MEMBER_ID = 97801L;

    @Autowired
    private CommonTicketEarnService earnService;

    @Autowired
    private TicketMaintenanceLock lock;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        redisTemplate.delete(CommonTicketRedisKeys.maintenance(MEMBER_ID));
        redisTemplate.delete(CommonTicketRedisKeys.balance(MEMBER_ID));
        redisTemplate.delete(CommonTicketRedisKeys.earnGuard(MEMBER_ID, "ATTENDANCE", "20260925"));
    }

    @Test
    void 공용_보정_락이_걸려있으면_EARN은_BALANCE_MAINTENANCE이고_잔액을_건드리지_않는다() {
        CommonEarnCommand command = new CommonEarnCommand(
                UUID.randomUUID(), MEMBER_ID, "ATTENDANCE", 1L, "2026-09-25", 1L);
        redisTemplate.opsForValue().set(CommonTicketRedisKeys.maintenance(MEMBER_ID), "locked");

        assertThat(earnService.earn(command).code()).isEqualTo(EarnResultCode.BALANCE_MAINTENANCE);
        assertThat(redisTemplate.hasKey(CommonTicketRedisKeys.balance(MEMBER_ID))).isFalse();
        assertThat(redisTemplate.hasKey(CommonTicketRedisKeys.idemMission(command.requestId().toString()))).isFalse();
    }

    @Test
    void 공용_락은_소유자만_잔액을_덮어쓰고_해제할_수_있다() {
        String token = lock.acquireCommon(MEMBER_ID);

        assertThat(token).isNotNull();
        assertThat(lock.acquireCommon(MEMBER_ID)).isNull();
        assertThat(lock.setCommonBalanceIfHeld(MEMBER_ID, "other", 5L)).isFalse();
        assertThat(lock.setCommonBalanceIfHeld(MEMBER_ID, token, 5L)).isTrue();
        assertThat(redisTemplate.opsForValue().get(CommonTicketRedisKeys.balance(MEMBER_ID))).isEqualTo("5");

        lock.releaseCommon(MEMBER_ID, token);
        assertThat(redisTemplate.hasKey(CommonTicketRedisKeys.maintenance(MEMBER_ID))).isFalse();
    }
}

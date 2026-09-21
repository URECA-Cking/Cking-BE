package kr.co.cking.ticket.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.ticket.application.config.TicketRedisKeys;

@SpringBootTest
class TicketMaintenanceLockTest {

    private static final Long CREATOR_ID = 97701L;
    private static final Long MEMBER_ID = 97702L;

    @Autowired
    private TicketMaintenanceLock lock;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        redisTemplate.delete(TicketRedisKeys.maintenanceLock(CREATOR_ID, MEMBER_ID));
        redisTemplate.delete(EntryRedisKeys.balance(CREATOR_ID, MEMBER_ID));
    }

    @Test
    void 이미_잡힌_lock은_다시_얻을_수_없고_lease는_60초다() {
        String token = lock.acquire(CREATOR_ID, MEMBER_ID);

        assertThat(token).isNotNull();
        assertThat(lock.acquire(CREATOR_ID, MEMBER_ID)).isNull();
        Long ttlSeconds = redisTemplate.getExpire(TicketRedisKeys.maintenanceLock(CREATOR_ID, MEMBER_ID));
        assertThat(ttlSeconds).isBetween(55L, 60L);
    }

    @Test
    void 다른_token으로는_해제되지_않고_소유자만_해제할_수_있다() {
        String token = lock.acquire(CREATOR_ID, MEMBER_ID);

        lock.release(CREATOR_ID, MEMBER_ID, "other-token");
        assertThat(redisTemplate.hasKey(TicketRedisKeys.maintenanceLock(CREATOR_ID, MEMBER_ID))).isTrue();

        lock.release(CREATOR_ID, MEMBER_ID, token);
        assertThat(redisTemplate.hasKey(TicketRedisKeys.maintenanceLock(CREATOR_ID, MEMBER_ID))).isFalse();
        assertThat(lock.acquire(CREATOR_ID, MEMBER_ID)).isNotNull();
    }

    @Test
    void lock을_소유한_동안에만_잔액을_덮어쓴다() {
        String token = lock.acquire(CREATOR_ID, MEMBER_ID);

        assertThat(lock.setBalanceIfHeld(CREATOR_ID, MEMBER_ID, token, 10L)).isTrue();
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isEqualTo("10");

        assertThat(lock.setBalanceIfHeld(CREATOR_ID, MEMBER_ID, "other-token", 99L)).isFalse();
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isEqualTo("10");
    }

    @Test
    void lock이_만료되거나_해제되면_잔액을_덮어쓰지_않는다() {
        String token = lock.acquire(CREATOR_ID, MEMBER_ID);
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, MEMBER_ID), "3");
        redisTemplate.expire(TicketRedisKeys.maintenanceLock(CREATOR_ID, MEMBER_ID), Duration.ofMillis(1));
        sleepQuietly(50);

        assertThat(lock.setBalanceIfHeld(CREATOR_ID, MEMBER_ID, token, 10L)).isFalse();
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isEqualTo("3");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

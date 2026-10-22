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
        redisTemplate.delete(TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID));
        redisTemplate.delete(EntryRedisKeys.balance(CREATOR_ID, MEMBER_ID));
    }

    @Test
    void 이미_잡힌_lock은_다시_얻을_수_없고_lease는_60초다() {
        String token = lock.acquire(CREATOR_ID, MEMBER_ID);

        assertThat(token).isNotNull();
        assertThat(lock.acquire(CREATOR_ID, MEMBER_ID)).isNull();
        Long ttlSeconds = redisTemplate.getExpire(TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID));
        assertThat(ttlSeconds).isBetween(55L, 60L);
    }

    @Test
    void 다른_token으로는_해제되지_않고_소유자만_해제할_수_있다() {
        String token = lock.acquire(CREATOR_ID, MEMBER_ID);

        lock.release(CREATOR_ID, MEMBER_ID, "other-token");
        assertThat(redisTemplate.hasKey(TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID))).isTrue();

        lock.release(CREATOR_ID, MEMBER_ID, token);
        assertThat(redisTemplate.hasKey(TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID))).isFalse();
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
        redisTemplate.expire(TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID), Duration.ofMillis(1));
        sleepQuietly(50);

        assertThat(lock.setBalanceIfHeld(CREATOR_ID, MEMBER_ID, token, 10L)).isFalse();
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isEqualTo("3");
    }

    // 시나리오 40(TicketBalanceKeyLoader와 EARN 동시 실행): 락을 쥔 동안 SET NX로 적재하되,
    // 그 사이 EARN이 이미 키를 만들었으면 덮어쓰지 않아야 한다.
    @Test
    void lock을_쥔_동안_키가_없으면_SET_NX로_적재하고_true를_반환한다() {
        String token = lock.acquire(CREATOR_ID, MEMBER_ID);

        assertThat(lock.loadBalanceIfHeld(CREATOR_ID, MEMBER_ID, token, 5L)).isTrue();
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isEqualTo("5");
    }

    @Test
    void lock을_쥔_동안에도_EARN이_먼저_만든_키는_덮어쓰지_않는다() {
        String token = lock.acquire(CREATOR_ID, MEMBER_ID);
        redisTemplate.opsForValue().set(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID), "999");

        assertThat(lock.loadBalanceIfHeld(CREATOR_ID, MEMBER_ID, token, 5L)).isTrue();
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isEqualTo("999");
    }

    @Test
    void lock이_검사_도중_만료되면_토큰이_달라도_적재하지_않고_false다() {
        String token = lock.acquire(CREATOR_ID, MEMBER_ID);
        redisTemplate.expire(TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID), Duration.ofMillis(1));
        sleepQuietly(50);

        assertThat(lock.loadBalanceIfHeld(CREATOR_ID, MEMBER_ID, token, 5L)).isFalse();
        assertThat(redisTemplate.hasKey(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isFalse();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

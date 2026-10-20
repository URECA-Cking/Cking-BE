package kr.co.cking.stream.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.ticket.application.config.CommonTicketRedisKeys;
import kr.co.cking.ticket.application.dto.SpendCommand;
import kr.co.cking.ticket.domain.CouponType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 다시 차감된 SPEND를 Redis에서 되돌리고 ACK하는지, 되돌리면 안 되는 상태에서는 건드리지 않는지 검증한다(#673). */
class SpendRedeductionRefunderIntegrationTest {

    private static final String STREAM = "stream:refund-test";
    private static final String GROUP = "cg:refund-test";
    private static final long EVENT_ID = 97201L;
    private static final long USER_ID = 97001L;
    private static final long CREATOR_ID = 97101L;
    private static final String CREATOR_BALANCE = EntryRedisKeys.balance(CREATOR_ID, USER_ID);
    private static final String COMMON_BALANCE = CommonTicketRedisKeys.balance(USER_ID);
    private static final String ENTRY_TOTAL = EntryRedisKeys.entryTotal(EVENT_ID);
    private static final String ENTRANTS = EntryRedisKeys.entrants(EVENT_ID);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private SpendRedeductionRefunder refunder;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        cleanUpKeys();
        refunder = new SpendRedeductionRefunder(redisTemplate, STREAM, GROUP);
    }

    @AfterEach
    void tearDown() {
        cleanUpKeys();
        connectionFactory.destroy();
    }

    @Test
    void 크리에이터_잔액과_실시간_집계를_되돌리고_ACK한다() {
        redisTemplate.opsForValue().set(CREATOR_BALANCE, "90");
        redisTemplate.opsForValue().set(ENTRY_TOTAL, "10");
        redisTemplate.opsForHash().put(ENTRANTS, String.valueOf(USER_ID), "10");
        RecordId messageId = deliver();

        assertThat(refunder.refundAndAcknowledge(command(CouponType.CREATOR), messageId)).isTrue();

        assertThat(redisTemplate.opsForValue().get(CREATOR_BALANCE)).isEqualTo("95");
        assertThat(redisTemplate.opsForValue().get(ENTRY_TOTAL)).isEqualTo("5");
        assertThat(redisTemplate.opsForHash().get(ENTRANTS, String.valueOf(USER_ID))).isEqualTo("5");
        assertThat(pendingCount()).isZero();
    }

    @Test
    void 공용_응모권이면_공용_잔액을_되돌린다() {
        redisTemplate.opsForValue().set(CREATOR_BALANCE, "90");
        redisTemplate.opsForValue().set(COMMON_BALANCE, "40");
        RecordId messageId = deliver();

        assertThat(refunder.refundAndAcknowledge(command(CouponType.COMMON), messageId)).isTrue();

        assertThat(redisTemplate.opsForValue().get(COMMON_BALANCE)).isEqualTo("45");
        assertThat(redisTemplate.opsForValue().get(CREATOR_BALANCE)).isEqualTo("90");
        assertThat(pendingCount()).isZero();
    }

    @Test
    void 수동_보정_중이면_아무것도_바꾸지_않고_PEL에_남긴다() {
        redisTemplate.opsForValue().set(CREATOR_BALANCE, "90");
        redisTemplate.opsForValue().set(EntryRedisKeys.maintenance(CREATOR_ID, USER_ID), "1");
        RecordId messageId = deliver();

        assertThat(refunder.refundAndAcknowledge(command(CouponType.CREATOR), messageId)).isFalse();

        assertThat(redisTemplate.opsForValue().get(CREATOR_BALANCE)).isEqualTo("90");
        assertThat(pendingCount()).isEqualTo(1);
    }

    @Test
    void 잔액_키가_없으면_만들지_않고_ACK만_한다() {
        RecordId messageId = deliver();

        assertThat(refunder.refundAndAcknowledge(command(CouponType.CREATOR), messageId)).isTrue();

        assertThat(redisTemplate.hasKey(CREATOR_BALANCE)).isFalse();
        assertThat(pendingCount()).isZero();
    }

    @Test
    void 실시간_집계가_이번_차감분보다_적으면_줄이지_않는다() {
        redisTemplate.opsForValue().set(CREATOR_BALANCE, "90");
        redisTemplate.opsForValue().set(ENTRY_TOTAL, "3");
        redisTemplate.opsForHash().put(ENTRANTS, String.valueOf(USER_ID), "3");
        RecordId messageId = deliver();

        refunder.refundAndAcknowledge(command(CouponType.CREATOR), messageId);

        assertThat(redisTemplate.opsForValue().get(ENTRY_TOTAL)).isEqualTo("3");
        assertThat(redisTemplate.opsForHash().get(ENTRANTS, String.valueOf(USER_ID))).isEqualTo("3");
    }

    private SpendCommand command(CouponType couponType) {
        return new SpendCommand(EVENT_ID, USER_ID, CREATOR_ID, "00000000-0000-4000-8000-000000000673", 5L, couponType);
    }

    private RecordId deliver() {
        redisTemplate.opsForStream().add(STREAM, Map.of("requestId", "00000000-0000-4000-8000-000000000673"));
        redisTemplate.opsForStream().createGroup(STREAM, ReadOffset.from("0"), GROUP);
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(GROUP, "refund-test"), StreamReadOptions.empty().count(1),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        return records.get(0).getId();
    }

    private long pendingCount() {
        return redisTemplate.opsForStream().pending(STREAM, GROUP, Range.unbounded(), 10).size();
    }

    private void cleanUpKeys() {
        redisTemplate.delete(List.of(STREAM, CREATOR_BALANCE, COMMON_BALANCE, ENTRY_TOTAL, ENTRANTS,
                EntryRedisKeys.maintenance(CREATOR_ID, USER_ID)));
    }
}

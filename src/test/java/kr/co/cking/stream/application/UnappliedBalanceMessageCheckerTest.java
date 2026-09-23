package kr.co.cking.stream.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.co.cking.stream.repository.DeadStreamMessageQueryRepository;

/**
 * 앱의 Consumer가 건드리지 않도록 테스트 전용 Stream 키·그룹으로 Checker를 직접 만들어 실제 Redis·MySQL에서 검증한다.
 */
@SpringBootTest
class UnappliedBalanceMessageCheckerTest {

    private static final String SPEND_KEY = "stream:ticket-deducted:unapplied-test";
    private static final String SPEND_GROUP = "cg:ticket-history:unapplied-test";
    private static final String EARN_KEY = "stream:ticket-earned:unapplied-test";
    private static final String EARN_GROUP = "cg:ticket-earn:unapplied-test";
    private static final Long MEMBER_ID = 97501L;
    private static final Long CREATOR_ID = 97601L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DeadStreamMessageQueryRepository deadStreamMessageQueryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UnappliedBalanceMessageChecker checker;

    @BeforeEach
    void setUp() {
        cleanUp();
        checker = new UnappliedBalanceMessageChecker(
                redisTemplate, deadStreamMessageQueryRepository, SPEND_KEY, SPEND_GROUP, EARN_KEY, EARN_GROUP);
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(SPEND_KEY);
        redisTemplate.delete(EARN_KEY);
        jdbcTemplate.update("DELETE FROM dead_stream_message WHERE source_stream_id LIKE 'unapplied-test-%'");
    }

    @Test
    void 메시지도_Dead_Stream도_없으면_false() {
        assertThat(checker.exists(MEMBER_ID, CREATOR_ID)).isFalse();
    }

    @Test
    void 아직_전달되지_않은_SPEND_메시지가_있으면_true() {
        add(SPEND_KEY, MEMBER_ID, CREATOR_ID);
        createGroupFromNow(SPEND_KEY, SPEND_GROUP);
        add(SPEND_KEY, MEMBER_ID, CREATOR_ID);

        assertThat(checker.exists(MEMBER_ID, CREATOR_ID)).isTrue();
    }

    @Test
    void 그룹이_아직_없어도_Stream에_남은_메시지가_있으면_true() {
        add(EARN_KEY, MEMBER_ID, CREATOR_ID);

        assertThat(checker.exists(MEMBER_ID, CREATOR_ID)).isTrue();
    }

    @Test
    void 전달됐지만_ACK되지_않은_SPEND_PEL_메시지가_있으면_true() {
        add(SPEND_KEY, MEMBER_ID, CREATOR_ID);
        createGroupFromZero(SPEND_KEY, SPEND_GROUP);
        readAll(SPEND_KEY, SPEND_GROUP);

        assertThat(checker.exists(MEMBER_ID, CREATOR_ID)).isTrue();
    }

    @Test
    void 전달됐지만_ACK되지_않은_EARN_PEL_메시지가_있으면_true() {
        add(EARN_KEY, MEMBER_ID, CREATOR_ID);
        createGroupFromZero(EARN_KEY, EARN_GROUP);
        readAll(EARN_KEY, EARN_GROUP);

        assertThat(checker.exists(MEMBER_ID, CREATOR_ID)).isTrue();
    }

    @Test
    void 모두_ACK했으면_false() {
        RecordId id = add(SPEND_KEY, MEMBER_ID, CREATOR_ID);
        createGroupFromZero(SPEND_KEY, SPEND_GROUP);
        readAll(SPEND_KEY, SPEND_GROUP);
        redisTemplate.opsForStream().acknowledge(SPEND_KEY, SPEND_GROUP, id);

        assertThat(checker.exists(MEMBER_ID, CREATOR_ID)).isFalse();
    }

    @Test
    void 다른_사용자나_다른_크리에이터의_미반영_메시지는_무시한다() {
        add(SPEND_KEY, MEMBER_ID + 1, CREATOR_ID);
        add(EARN_KEY, MEMBER_ID, CREATOR_ID + 1);
        createGroupFromZero(SPEND_KEY, SPEND_GROUP);
        createGroupFromZero(EARN_KEY, EARN_GROUP);
        readAll(SPEND_KEY, SPEND_GROUP);
        readAll(EARN_KEY, EARN_GROUP);

        assertThat(checker.exists(MEMBER_ID, CREATOR_ID)).isFalse();
    }

    @Test
    void PEL_앞뒤로_다른_사용자_메시지가_많아도_대상_PEL을_찾는다() {
        for (int i = 0; i < 1_500; i++) {
            add(SPEND_KEY, MEMBER_ID + 1, CREATOR_ID);
        }
        add(SPEND_KEY, MEMBER_ID, CREATOR_ID);
        createGroupFromZero(SPEND_KEY, SPEND_GROUP);
        redisTemplate.opsForStream().read(
                Consumer.from(SPEND_GROUP, "unapplied-test-consumer"),
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(2_000),
                StreamOffset.create(SPEND_KEY, ReadOffset.lastConsumed()));

        assertThat(checker.exists(MEMBER_ID, CREATOR_ID)).isTrue();
    }

    // 이슈 #243: COMMON 응모권으로 응모한 SPEND 메시지도 creatorId 필드는 실려 있지만,
    // 그 크리에이터 잔액을 차감하지 않았으므로 크리에이터 잔액 미반영 검사에서 빠져야 한다.
    // 아니면 공용 응모권 사용 메시지가 크리에이터 잔액 보정을 막는다.
    @Test
    void COMMON_SPEND_메시지는_크리에이터_잔액_미반영_검사에서_제외한다() {
        addSpendWithCouponType(MEMBER_ID, CREATOR_ID, "COMMON");
        createGroupFromZero(SPEND_KEY, SPEND_GROUP);
        readAll(SPEND_KEY, SPEND_GROUP);

        assertThat(checker.exists(MEMBER_ID, CREATOR_ID)).isFalse();
    }

    // couponType 필드가 없는(배포 전) 메시지는 CREATOR로 취급해 기존과 동일하게 검사 대상이다.
    @Test
    void couponType_필드가_없는_SPEND_메시지는_CREATOR로_취급해_검사_대상이다() {
        add(SPEND_KEY, MEMBER_ID, CREATOR_ID);
        createGroupFromZero(SPEND_KEY, SPEND_GROUP);
        readAll(SPEND_KEY, SPEND_GROUP);

        assertThat(checker.exists(MEMBER_ID, CREATOR_ID)).isTrue();
    }

    @Test
    void 미해결_SPEND_Dead_Stream이_있으면_true() {
        insertDeadStream("unapplied-test-1", "SPEND", MEMBER_ID, CREATOR_ID, "UNRESOLVED");

        assertThat(checker.exists(MEMBER_ID, CREATOR_ID)).isTrue();
    }

    @Test
    void event_id가_없는_미해결_EARN_Dead_Stream도_payload로_찾는다() {
        insertDeadStream("unapplied-test-2", "EARN", MEMBER_ID, CREATOR_ID, "UNRESOLVED");

        assertThat(checker.exists(MEMBER_ID, CREATOR_ID)).isTrue();
    }

    @Test
    void 해결된_Dead_Stream이나_다른_대상의_Dead_Stream은_무시한다() {
        insertDeadStream("unapplied-test-3", "SPEND", MEMBER_ID, CREATOR_ID, "RESOLVED");
        insertDeadStream("unapplied-test-4", "EARN", MEMBER_ID + 1, CREATOR_ID, "UNRESOLVED");
        insertDeadStream("unapplied-test-5", "EARN", MEMBER_ID, CREATOR_ID + 1, "UNRESOLVED");

        assertThat(checker.exists(MEMBER_ID, CREATOR_ID)).isFalse();
    }

    private RecordId add(String key, Long userId, Long creatorId) {
        return redisTemplate.opsForStream().add(MapRecord.create(key, Map.of(
                "userId", String.valueOf(userId), "creatorId", String.valueOf(creatorId))));
    }

    private RecordId addSpendWithCouponType(Long userId, Long creatorId, String couponType) {
        return redisTemplate.opsForStream().add(MapRecord.create(SPEND_KEY, Map.of(
                "userId", String.valueOf(userId), "creatorId", String.valueOf(creatorId),
                "couponType", couponType)));
    }

    private void readAll(String key, String group) {
        redisTemplate.opsForStream().read(
                Consumer.from(group, "unapplied-test-consumer"),
                StreamOffset.create(key, ReadOffset.lastConsumed()));
    }

    private void createGroupFromZero(String key, String group) {
        createGroup(key, group, "0");
    }

    private void createGroupFromNow(String key, String group) {
        createGroup(key, group, "$");
    }

    private void createGroup(String key, String group, String offset) {
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<String>) connection ->
                connection.streamCommands().xGroupCreate(
                        key.getBytes(StandardCharsets.UTF_8), group, ReadOffset.from(offset), true));
    }

    private void insertDeadStream(String sourceStreamId, String type, Long memberId, Long creatorId, String status) {
        String payload = "{\"userId\":\"%d\",\"creatorId\":\"%d\"}".formatted(memberId, creatorId);
        jdbcTemplate.update("""
                INSERT INTO dead_stream_message (source_stream_id, stream_type, payload, retry_count, resolution_status)
                VALUES (?, ?, ?, 3, ?)
                """, sourceStreamId, type, payload, status);
    }
}

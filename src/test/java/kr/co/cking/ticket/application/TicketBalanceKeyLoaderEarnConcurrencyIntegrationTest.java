package kr.co.cking.ticket.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import kr.co.cking.stream.application.UnappliedBalanceMessageChecker;
import kr.co.cking.stream.repository.DeadStreamMessageQueryRepository;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import kr.co.cking.ticket.domain.CouponType;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * 이슈 #275 시나리오 40(적재와 EARN 동시 실행)을 직접 검증한다: 진짜 {@code ticket-earn.lua}로
 * 적립을 만든 뒤, 그 메시지가 아직 반영되지 않은 상태·완전히 반영된 상태 각각에서
 * {@link TicketBalanceKeyLoader}가 적립분을 잃어버리지 않는지 확인한다.
 *
 * <p>앱의 실제 Consumer Group을 건드리지 않도록 EARN Stream 키·그룹은 테스트 전용을 쓴다
 * (UnappliedBalanceMessageCheckerTest와 동일 원칙). 응모권 Balance·maintenance 키는
 * 포맷이 고정이라(TicketRedisKeys) 실제 키 형식 그대로 쓰되, 충돌 없는 전용 테스트 ID를 쓴다.
 */
@SpringBootTest
class TicketBalanceKeyLoaderEarnConcurrencyIntegrationTest {

    private static final String EARN_KEY = "stream:ticket-earned:balance-loader-test";
    private static final String EARN_GROUP = "cg:ticket-earn:balance-loader-test";
    private static final String SPEND_KEY = "stream:ticket-deducted:balance-loader-test";
    private static final String SPEND_GROUP = "cg:ticket-history:balance-loader-test";
    private static final String COMMON_EARN_KEY = "stream:common-ticket-earned:balance-loader-test";
    private static final String COMMON_EARN_GROUP = "cg:common-ticket-earn:balance-loader-test";

    private static final Long CREATOR_ID = 97801L;
    private static final Long MEMBER_ID = 97802L;
    // 로더가 검사를 통과해 이 값으로 잘못 덮어썼다면 테스트가 바로 드러나도록, 실제 EARN
    // 적립분과 다른 값을 DB 잔액으로 세팅한다.
    private static final long STALE_DB_BALANCE = 999L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    @Qualifier("ticketEarnLuaScript")
    private DefaultRedisScript<List> ticketEarnLuaScript;

    @Autowired
    private DeadStreamMessageQueryRepository deadStreamMessageQueryRepository;

    private TicketEarnServiceImpl earnService;
    private TicketBalanceKeyLoader loader;

    @BeforeEach
    void setUp() {
        cleanUp();
        earnService = new TicketEarnServiceImpl(redisTemplate, ticketEarnLuaScript, EARN_KEY, new ObjectMapper());
        UnappliedBalanceMessageChecker checker = new UnappliedBalanceMessageChecker(
                redisTemplate, deadStreamMessageQueryRepository,
                SPEND_KEY, SPEND_GROUP, EARN_KEY, EARN_GROUP, COMMON_EARN_KEY, COMMON_EARN_GROUP);
        TicketMaintenanceLock lock = new TicketMaintenanceLock(redisTemplate);
        UserTicketBalanceRepository creatorRepository = mock(UserTicketBalanceRepository.class);
        UserCommonTicketBalanceRepository commonRepository = mock(UserCommonTicketBalanceRepository.class);
        // DB 잔액을 실제 EARN 적립분(3)과 다르게 세팅해, 로더가 검사를 건너뛰고 이 값으로
        // 덮어썼다면 테스트가 바로 드러나게 한다.
        when(creatorRepository.findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID)).thenReturn(Optional.of(
                UserTicketBalance.builder().memberId(MEMBER_ID).creatorId(CREATOR_ID)
                        .balance(STALE_DB_BALANCE).updatedAt(Instant.now()).build()));
        loader = new TicketBalanceKeyLoader(lock, checker, creatorRepository, commonRepository);
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(EARN_KEY);
        redisTemplate.delete(SPEND_KEY);
        redisTemplate.delete(COMMON_EARN_KEY);
        redisTemplate.delete(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID));
        redisTemplate.delete(TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID));
        // 두 테스트가 같은 (userId, creatorId) 잔액 키를 공유하므로, 하루 중복적립 가드도
        // 테스트마다 지워야 두 번째로 실행되는 테스트가 DUPLICATE_MISSION으로 막히지 않는다.
        redisTemplate.delete(TicketRedisKeys.earnGuard(MEMBER_ID, "ATTENDANCE", CREATOR_ID, "20260928"));
    }

    @Test
    void 방금_적립된_EARN_메시지가_미반영_상태면_적재를_거부해_적립분을_보존한다() {
        EarnResult earned = earnService.earn(earnCommand(20L));
        assertThat(earned.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        String balanceKey = TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID);
        assertThat(redisTemplate.opsForValue().get(balanceKey)).isEqualTo("3");

        // 그룹이 아직 없으므로 방금 XADD된 메시지는 hasUndelivered()가 잡는다.
        boolean loaded = loader.load(CouponType.CREATOR, CREATOR_ID, MEMBER_ID);

        assertThat(loaded).isFalse();
        assertThat(redisTemplate.opsForValue().get(balanceKey)).isEqualTo("3");
    }

    @Test
    void EARN_메시지가_전부_ACK된_뒤에는_이미_적립된_키를_DB_값으로_덮어쓰지_않는다() {
        EarnResult earned = earnService.earn(earnCommand(21L));
        assertThat(earned.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        String balanceKey = TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID);

        createGroupFromZero(EARN_KEY, EARN_GROUP);
        redisTemplate.opsForStream().read(
                Consumer.from(EARN_GROUP, "balance-loader-test-consumer"),
                StreamOffset.create(EARN_KEY, ReadOffset.lastConsumed()));
        redisTemplate.opsForStream().acknowledge(EARN_KEY, EARN_GROUP,
                redisTemplate.opsForStream().range(EARN_KEY, org.springframework.data.domain.Range.unbounded())
                        .get(0).getId());

        boolean loaded = loader.load(CouponType.CREATOR, CREATOR_ID, MEMBER_ID);

        // 미반영 메시지가 없어 조건은 통과하지만, SET NX가 이미 있는 키를 보고 스킵해야 한다.
        assertThat(loaded).isTrue();
        assertThat(redisTemplate.opsForValue().get(balanceKey)).isEqualTo("3");
        assertThat(redisTemplate.opsForValue().get(balanceKey)).isNotEqualTo(String.valueOf(STALE_DB_BALANCE));
    }

    private EarnCommand earnCommand(long missionId) {
        return new EarnCommand(UUID.randomUUID(), MEMBER_ID, CREATOR_ID, "ATTENDANCE", missionId,
                "2026-09-28", "attendance:creator:2026-09-28:" + missionId, 3L);
    }

    private void createGroupFromZero(String key, String group) {
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<String>) connection ->
                connection.streamCommands().xGroupCreate(
                        key.getBytes(StandardCharsets.UTF_8), group, ReadOffset.from("0"), true));
    }
}

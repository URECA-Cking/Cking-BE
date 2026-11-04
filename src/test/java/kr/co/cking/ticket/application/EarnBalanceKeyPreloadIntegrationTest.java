package kr.co.cking.ticket.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import kr.co.cking.stream.application.UnappliedBalanceMessageChecker;
import kr.co.cking.stream.repository.DeadStreamMessageQueryRepository;
import kr.co.cking.ticket.application.config.CommonTicketRedisKeys;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.application.dto.CommonEarnCommand;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import kr.co.cking.ticket.domain.UserCommonTicketBalance;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * #793: Redis 잔액 키가 유실된 사용자가 미션 적립을 하면 진짜 적립 Lua가 0이 아니라 DB 잔액 위에 쌓는지 확인한다.
 * 최초 적립(DB 행 없음)은 지금처럼 0에서 시작하고, 적재 조건을 못 맞추면 Redis에 아무것도 쓰지 않아야 한다.
 *
 * <p>앱의 실제 Stream·Consumer Group을 건드리지 않도록 Stream 키는 테스트 전용을 쓰고, 잔액·가드 키는 실제 형식에
 * 충돌 없는 전용 ID를 쓴다(TicketBalanceKeyLoaderEarnConcurrencyIntegrationTest와 같은 원칙).
 */
@SpringBootTest
class EarnBalanceKeyPreloadIntegrationTest {

    private static final String EARN_KEY = "stream:ticket-earned:earn-preload-test";
    private static final String EARN_GROUP = "cg:ticket-earn:earn-preload-test";
    private static final String SPEND_KEY = "stream:ticket-deducted:earn-preload-test";
    private static final String SPEND_GROUP = "cg:ticket-history:earn-preload-test";
    private static final String COMMON_EARN_KEY = "stream:common-ticket-earned:earn-preload-test";
    private static final String COMMON_EARN_GROUP = "cg:common-ticket-earn:earn-preload-test";

    private static final Long CREATOR_ID = 97901L;
    private static final Long MEMBER_ID = 97902L;
    private static final String PERIOD_KEY = "2026-11-04";
    private static final String GUARD_PERIOD = "20261104";
    private static final long AMOUNT = 1L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    @Qualifier("ticketEarnLuaScript")
    private DefaultRedisScript<List> ticketEarnLuaScript;

    @Autowired
    @Qualifier("commonTicketEarnLuaScript")
    private DefaultRedisScript<List> commonTicketEarnLuaScript;

    @Autowired
    private DeadStreamMessageQueryRepository deadStreamMessageQueryRepository;

    private final UserTicketBalanceRepository creatorRepository = mock(UserTicketBalanceRepository.class);
    private final UserCommonTicketBalanceRepository commonRepository = mock(UserCommonTicketBalanceRepository.class);

    private TicketEarnServiceImpl earnService;
    private CommonTicketEarnServiceImpl commonEarnService;

    @BeforeEach
    void setUp() {
        cleanUp();
        UnappliedBalanceMessageChecker checker = new UnappliedBalanceMessageChecker(
                redisTemplate, deadStreamMessageQueryRepository,
                SPEND_KEY, SPEND_GROUP, EARN_KEY, EARN_GROUP, COMMON_EARN_KEY, COMMON_EARN_GROUP);
        TicketBalanceKeyLoader loader = new TicketBalanceKeyLoader(
                new TicketMaintenanceLock(redisTemplate), checker, creatorRepository, commonRepository, redisTemplate);
        earnService = new TicketEarnServiceImpl(
                redisTemplate, ticketEarnLuaScript, EARN_KEY, new ObjectMapper(), null, loader);
        TicketEarnRequestClaimService claimService = mock(TicketEarnRequestClaimService.class);
        when(claimService.claim(any(CommonEarnCommand.class))).thenReturn(TicketEarnRequestClaim.PENDING);
        commonEarnService = new CommonTicketEarnServiceImpl(
                redisTemplate, commonTicketEarnLuaScript, COMMON_EARN_KEY, new ObjectMapper(), claimService, loader);
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(List.of(EARN_KEY, SPEND_KEY, COMMON_EARN_KEY,
                TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID),
                TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID),
                TicketRedisKeys.earnGuard(MEMBER_ID, "ATTENDANCE", CREATOR_ID, GUARD_PERIOD),
                CommonTicketRedisKeys.balance(MEMBER_ID),
                CommonTicketRedisKeys.maintenance(MEMBER_ID),
                CommonTicketRedisKeys.earnGuard(MEMBER_ID, "ATTENDANCE", GUARD_PERIOD)));
    }

    @Test
    void 잔액_키가_유실된_뒤_적립하면_DB_잔액_위에_쌓는다() {
        givenCreatorDbBalance(5L);

        EarnResult result = earnService.earn(creatorCommand());

        assertThat(result.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isEqualTo("6");
    }

    @Test
    void DB_행이_없는_최초_적립은_0에서_시작한다() {
        when(creatorRepository.findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID)).thenReturn(Optional.empty());

        EarnResult result = earnService.earn(creatorCommand());

        assertThat(result.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isEqualTo("1");
    }

    @Test
    void 유실인데_미반영_메시지가_있으면_아무것도_쓰지_않고_재시도_가능한_실패로_끝낸다() {
        givenCreatorDbBalance(5L);
        // 아직 Consumer가 읽지 않은 같은 회원·크리에이터의 EARN 메시지. 그룹이 없으니 처음부터 미전달로 본다.
        redisTemplate.opsForStream().add(EARN_KEY, Map.of(
                "userId", String.valueOf(MEMBER_ID), "creatorId", String.valueOf(CREATOR_ID), "amount", "2"));
        EarnCommand command = creatorCommand();

        EarnResult result = earnService.earn(command);

        assertThat(result.code()).isEqualTo(EarnResultCode.EARN_PROCESSING_FAILED);
        assertThat(redisTemplate.hasKey(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isFalse();
        assertThat(redisTemplate.hasKey(TicketRedisKeys.idemMission(command.requestId().toString()))).isFalse();
        assertThat(redisTemplate.hasKey(
                TicketRedisKeys.earnGuard(MEMBER_ID, "ATTENDANCE", CREATOR_ID, GUARD_PERIOD))).isFalse();
        assertThat(redisTemplate.opsForStream().size(EARN_KEY)).isEqualTo(1L);
    }

    @Test
    void 공용_잔액_키가_유실된_뒤_적립해도_DB_잔액_위에_쌓는다() {
        when(commonRepository.existsById(MEMBER_ID)).thenReturn(true);
        when(commonRepository.findById(MEMBER_ID)).thenReturn(Optional.of(UserCommonTicketBalance.builder()
                .memberId(MEMBER_ID).balance(4L).updatedAt(Instant.now()).build()));

        EarnResult result = commonEarnService.earn(
                new CommonEarnCommand(UUID.randomUUID(), MEMBER_ID, "ATTENDANCE", 3L, PERIOD_KEY, AMOUNT));

        assertThat(result.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(redisTemplate.opsForValue().get(CommonTicketRedisKeys.balance(MEMBER_ID))).isEqualTo("5");
    }

    private void givenCreatorDbBalance(long balance) {
        when(creatorRepository.findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID)).thenReturn(Optional.of(
                UserTicketBalance.builder().memberId(MEMBER_ID).creatorId(CREATOR_ID)
                        .balance(balance).updatedAt(Instant.now()).build()));
    }

    private EarnCommand creatorCommand() {
        return new EarnCommand(UUID.randomUUID(), MEMBER_ID, CREATOR_ID, "ATTENDANCE", 41L,
                PERIOD_KEY, "attendance:creator:" + PERIOD_KEY + ":41", AMOUNT);
    }
}

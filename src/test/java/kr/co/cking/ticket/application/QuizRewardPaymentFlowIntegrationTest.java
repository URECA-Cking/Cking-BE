package kr.co.cking.ticket.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.QuizRewardExecutionStore.FinishOutcome;
import kr.co.cking.ticket.application.config.QuizTicketEarnLuaConfig;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.application.dto.QuizRedisEarnOutcome;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.QuizRewardRequestStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 실제 MySQL과 Redis로 Quiz 보상 지급 전체 흐름을 확인한다(이슈 #533): 접수 → Worker(실행권 발급 → 실제
 * {@code quiz-ticket-earn.lua} → 결과 기록) → Stream 메시지 → {@link TicketEarnEventDispatcher} → 원장·DB 잔액 → APPLIED.
 * CI에서만 실행한다. {@code TicketEarnServiceImplTest}처럼 localhost Redis에 직접 연결하고, 전용 Stream 키와 테스트가 만든
 * 키만 지운다. Balance 키는 미리 만들어 두므로 적재는 하지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuizRewardEarnWorker.class, TicketQuizRewardService.class, QuizRewardRequestStore.class,
        QuizRewardExecutionClaimService.class, QuizRewardExecutionStore.class, QuizTicketEarnRedisExecutor.class,
        QuizTicketEarnLuaConfig.class, TicketEarnEventDispatcher.class, TicketEarnLedgerService.class,
        QuizRewardLedgerService.class, StoredExecutionQuizRewardExecutionPolicy.class,
        QuizRewardPaymentFlowIntegrationTest.RedisAndClockConfiguration.class})
@TestPropertySource(properties = {
        "cking.ticket.quiz-reward.worker-enabled=true",
        "cking.ticket.earn-stream-key=" + QuizRewardPaymentFlowIntegrationTest.STREAM_KEY})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuizRewardPaymentFlowIntegrationTest {

    static final String STREAM_KEY = "stream:ticket-earned:quiz-flow-test";

    @MockitoBean
    private TicketBalanceKeyLoader balanceKeyLoader;

    @Autowired
    private TicketQuizRewardService rewardService;
    @Autowired
    private QuizRewardEarnWorker worker;
    @Autowired
    private TicketEarnEventDispatcher dispatcher;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Member member;
    private Creator creator;
    private final List<String> requestIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        member = memberRepository.saveAndFlush(new Member("quiz-flow-" + suffix, null, null, MemberRole.USER));
        Member creatorMember = memberRepository.saveAndFlush(
                new Member("quiz-flow-creator-" + suffix, null, null, MemberRole.USER));
        creator = creatorRepository.saveAndFlush(new Creator(creatorMember.getMemberId(), "creator-" + suffix));
        redisTemplate.delete(STREAM_KEY);
        redisTemplate.opsForValue().set(balanceKey(), "0");
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(STREAM_KEY);
        redisTemplate.delete(balanceKey());
        for (String requestId : requestIds) {
            redisTemplate.delete(TicketRedisKeys.idemQuizReward(requestId));
        }
        jdbcTemplate.update("DELETE FROM ticket_ledger WHERE member_id = ?", member.getMemberId());
        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ?", member.getMemberId());
        jdbcTemplate.update("DELETE e FROM ticket_quiz_reward_execution e JOIN ticket_quiz_reward_request r "
                + "ON e.quiz_reward_request_id = r.quiz_reward_request_id WHERE r.member_id = ?", member.getMemberId());
        jdbcTemplate.update("DELETE FROM ticket_quiz_reward_request WHERE member_id = ?", member.getMemberId());
        for (String requestId : requestIds) {
            jdbcTemplate.update("DELETE FROM ticket_earn_request WHERE request_id = ?", requestId);
        }
        creatorRepository.delete(creator);
        memberRepository.deleteById(creator.getMemberId());
        memberRepository.delete(member);
    }

    private String balanceKey() {
        return TicketRedisKeys.balance(creator.getCreatorId(), member.getMemberId());
    }

    private List<Map<String, String>> streamMessages() {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().range(STREAM_KEY,
                Range.unbounded());
        return records == null ? List.of() : records.stream()
                .map(record -> record.getValue().entrySet().stream().collect(Collectors.toMap(
                        entry -> String.valueOf(entry.getKey()), entry -> String.valueOf(entry.getValue()))))
                .toList();
    }

    @Test
    void 접수한_요청을_Redis에_적립하고_Stream_메시지로_원장에_한_번만_반영한다() {
        QuizRewardCommand command = new QuizRewardCommand(Math.abs(UUID.randomUUID().getMostSignificantBits()
                % 1_000_000_000_000L) + 1, member.getMemberId(), creator.getCreatorId(), 3, UUID.randomUUID());
        requestIds.add(command.rewardRequestId().toString());

        rewardService.admit(command);
        QuizRewardWorkResult work = worker.run(command.rewardRequestId());

        assertThat(work.redisOutcome()).isEqualTo(QuizRedisEarnOutcome.ACCEPTED);
        assertThat(work.finishOutcome()).isEqualTo(FinishOutcome.RECORDED);
        assertThat(rewardService.findStatus(command.rewardRequestId())).contains(QuizRewardRequestStatus.ACCEPTED);
        List<Map<String, String>> messages = streamMessages();
        assertThat(messages).singleElement()
                .satisfies(fields -> assertThat(fields).containsEntry("sourceType", "QUIZ")
                        .containsEntry("requestId", command.rewardRequestId().toString()));

        // 정상 소비와 같은 분기로 반영하고, 같은 메시지가 다시 와도(PEL 재전달·Dead replay) 한 번만 반영한다.
        dispatcher.apply(messages.get(0));
        dispatcher.apply(messages.get(0));

        assertThat(rewardService.findStatus(command.rewardRequestId())).contains(QuizRewardRequestStatus.APPLIED);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket_ledger WHERE request_id = ?",
                Integer.class, command.rewardRequestId().toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM user_ticket_balance WHERE member_id = ? "
                + "AND creator_id = ?", Long.class, member.getMemberId(), creator.getCreatorId())).isEqualTo(3L);
        assertThat(redisTemplate.opsForValue().get(balanceKey())).isEqualTo("3");

        // 반영이 끝난 요청은 Worker가 다시 실행하지 않는다.
        QuizRewardWorkResult again = worker.run(command.rewardRequestId());
        assertThat(again.claimOutcome()).isEqualTo(QuizRewardExecutionClaim.Outcome.ALREADY_APPLIED);
        assertThat(streamMessages()).hasSize(1);
        assertThat(redisTemplate.opsForValue().get(balanceKey())).isEqualTo("3");
        verify(balanceKeyLoader, never()).load(any(), any(), any());
    }

    @TestConfiguration
    static class RedisAndClockConfiguration {

        @Bean
        LettuceConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory("localhost", 6379);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory redisConnectionFactory) {
            return new StringRedisTemplate(redisConnectionFactory);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-12T06:00:00Z"), ZoneOffset.UTC);
        }
    }
}

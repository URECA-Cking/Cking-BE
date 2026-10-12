package kr.co.cking.ticket.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.QuizRewardExecutionStore.FinishOutcome;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.application.dto.QuizRedisEarnOutcome;
import kr.co.cking.ticket.application.dto.QuizRedisEarnResult;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.repository.TicketQuizRewardRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 실제 MySQL로 Quiz 보상 Worker의 실행권 발급 → (가짜) Redis 실행 → 결과 기록이 V62 제약과 맞고, 결과별 요청 상태가
 * 의도대로 남는지, Balance 키 적재 전 미확정 Quiz 지급 확인이 동작하는지 확인한다(이슈 #533). CI에서만 실행한다.
 * Redis 실행·Balance 적재·키 확인은 mock이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuizRewardEarnWorker.class, TicketQuizRewardService.class, QuizRewardRequestStore.class,
        QuizRewardExecutionClaimService.class, QuizRewardExecutionStore.class,
        QuizRewardEarnWorkerIntegrationTest.FixedClockConfiguration.class})
@TestPropertySource(properties = "cking.ticket.quiz-reward.worker-enabled=true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuizRewardEarnWorkerIntegrationTest {

    @MockitoBean
    private QuizTicketEarnRedisExecutor redisExecutor;
    @MockitoBean
    private TicketBalanceKeyLoader balanceKeyLoader;
    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @Autowired
    private QuizRewardEarnWorker worker;
    @Autowired
    private TicketQuizRewardService admissionService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TicketQuizRewardRequestRepository quizRewardRequestRepository;

    private Member member;
    private Creator creator;
    private final List<String> requestIds = new ArrayList<>();
    private long nextAttemptId = System.nanoTime() & 0x3FFFFFFFFFFFL;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        member = memberRepository.saveAndFlush(new Member("quiz-worker-" + suffix, null, null, MemberRole.USER));
        Member creatorMember = memberRepository.saveAndFlush(
                new Member("quiz-worker-creator-" + suffix, null, null, MemberRole.USER));
        creator = creatorRepository.saveAndFlush(new Creator(creatorMember.getMemberId(), "creator-" + suffix));
        given(redisTemplate.hasKey(TicketRedisKeys.balance(creator.getCreatorId(), member.getMemberId())))
                .willReturn(true);
    }

    @AfterEach
    void cleanUp() {
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

    private UUID admitted() {
        QuizRewardCommand command = new QuizRewardCommand(nextAttemptId++, member.getMemberId(),
                creator.getCreatorId(), 3, UUID.randomUUID());
        requestIds.add(command.rewardRequestId().toString());
        admissionService.admit(command);
        return command.rewardRequestId();
    }

    private String requestStatus(UUID rewardRequestId) {
        return jdbcTemplate.queryForObject("SELECT status FROM ticket_quiz_reward_request WHERE reward_request_id = ?",
                String.class, rewardRequestId.toString());
    }

    private List<Map<String, Object>> executions(UUID rewardRequestId) {
        return jdbcTemplate.queryForList("SELECT e.execution_seq, e.status, e.outcome, e.reason, e.stream_id, "
                + "e.not_executed_confirmed FROM ticket_quiz_reward_execution e JOIN ticket_quiz_reward_request r "
                + "ON e.quiz_reward_request_id = r.quiz_reward_request_id WHERE r.reward_request_id = ? "
                + "ORDER BY e.execution_seq", rewardRequestId.toString());
    }

    @Test
    void 결과별로_V62_제약을_지키며_실행을_끝내고_요청_상태를_바꾼다() {
        record Case(QuizRedisEarnResult result, String requestStatus, boolean notExecuted) {
        }
        List<Case> cases = List.of(
                new Case(new QuizRedisEarnResult(QuizRedisEarnOutcome.ACCEPTED, "1-0", 3L, null, null),
                        "ACCEPTED", false),
                new Case(QuizRedisEarnResult.of(QuizRedisEarnOutcome.RETRYABLE_FAILURE, "XADD_FAILED"),
                        "RETRYABLE_FAILURE", true),
                new Case(QuizRedisEarnResult.of(QuizRedisEarnOutcome.BALANCE_MAINTENANCE, null),
                        "RETRYABLE_FAILURE", true),
                new Case(QuizRedisEarnResult.of(QuizRedisEarnOutcome.BALANCE_NOT_LOADED, null),
                        "RETRYABLE_FAILURE", true),
                new Case(QuizRedisEarnResult.of(QuizRedisEarnOutcome.REQUEST_ID_CONFLICT, null), "UNKNOWN", true),
                new Case(QuizRedisEarnResult.of(QuizRedisEarnOutcome.INVALID_REQUEST, "balanceKey"), "UNKNOWN", true),
                new Case(QuizRedisEarnResult.unknown("TIMEOUT"), "UNKNOWN", false),
                // 이 요청의 적립 실행으로 확인되지 않는 guard 실행권
                new Case(new QuizRedisEarnResult(QuizRedisEarnOutcome.ALREADY_ACCEPTED, "1-0", null,
                        UUID.randomUUID().toString(), null), "UNKNOWN", false));

        for (Case testCase : cases) {
            UUID rewardRequestId = admitted();
            given(redisExecutor.execute(any())).willReturn(testCase.result());

            QuizRewardWorkResult result = worker.run(rewardRequestId);

            assertThat(result.finishOutcome()).as(testCase.result().toString()).isEqualTo(FinishOutcome.RECORDED);
            assertThat(requestStatus(rewardRequestId)).as(testCase.result().toString())
                    .isEqualTo(testCase.requestStatus());
            Map<String, Object> execution = executions(rewardRequestId).get(0);
            assertThat(execution).containsEntry("status", "FINISHED");
            assertThat(jdbcTemplate.queryForObject("SELECT e.not_executed_confirmed "
                    + "FROM ticket_quiz_reward_execution e JOIN ticket_quiz_reward_request r "
                    + "ON e.quiz_reward_request_id = r.quiz_reward_request_id WHERE r.reward_request_id = ?",
                    Boolean.class, rewardRequestId.toString()))
                    .as(testCase.result().toString()).isEqualTo(testCase.notExecuted());
        }
    }

    @Test
    void 무반영이면_새_실행권으로_다시_실행하고_UNKNOWN이면_다시_실행하지_않는다() {
        UUID retryable = admitted();
        given(redisExecutor.execute(any())).willReturn(
                QuizRedisEarnResult.of(QuizRedisEarnOutcome.RETRYABLE_FAILURE, "XADD_FAILED"),
                new QuizRedisEarnResult(QuizRedisEarnOutcome.ACCEPTED, "1-0", 3L, null, null));

        worker.run(retryable);
        worker.run(retryable);

        assertThat(requestStatus(retryable)).isEqualTo("ACCEPTED");
        assertThat(executions(retryable)).extracting(row -> row.get("outcome"))
                .containsExactly("RETRYABLE_FAILURE", "ACCEPTED");

        UUID unknown = admitted();
        given(redisExecutor.execute(any())).willReturn(QuizRedisEarnResult.unknown("TIMEOUT"));
        worker.run(unknown);
        QuizRewardWorkResult again = worker.run(unknown);

        assertThat(again.claimOutcome()).isEqualTo(QuizRewardExecutionClaim.Outcome.UNKNOWN);
        assertThat(executions(unknown)).hasSize(1);
        verify(redisExecutor, times(3)).execute(any());
    }

    @Test
    void Redis_실행_중_원장_반영이_먼저_끝나면_APPLIED를_유지하고_실행_기록만_끝낸다() {
        UUID rewardRequestId = admitted();
        given(redisExecutor.execute(any())).willAnswer(invocation -> {
            // Worker는 Redis 실행 중 DB 잠금을 잡지 않으므로 다른 연결이 바로 반영할 수 있다.
            jdbcTemplate.update("UPDATE ticket_quiz_reward_request SET status = 'APPLIED', applied_at = ? "
                    + "WHERE reward_request_id = ?", now(), rewardRequestId.toString());
            jdbcTemplate.update("UPDATE ticket_earn_request SET status = 'ACCEPTED', accepted_at = ? "
                    + "WHERE request_id = ?", now(), rewardRequestId.toString());
            return new QuizRedisEarnResult(QuizRedisEarnOutcome.ACCEPTED, "1-0", 3L, null, null);
        });

        QuizRewardWorkResult result = worker.run(rewardRequestId);

        assertThat(result.finishOutcome()).isEqualTo(FinishOutcome.RECORDED_REQUEST_ALREADY_APPLIED);
        assertThat(requestStatus(rewardRequestId)).isEqualTo("APPLIED");
        assertThat(executions(rewardRequestId).get(0)).containsEntry("status", "FINISHED")
                .containsEntry("outcome", "ACCEPTED").containsEntry("stream_id", "1-0");
    }

    @Test
    void Balance_키를_적재하지_못하면_Lua를_부르지_않고_재시도_가능한_무반영으로_남긴다() {
        UUID rewardRequestId = admitted();
        given(redisTemplate.hasKey(anyString())).willReturn(false);
        given(balanceKeyLoader.load(any(), any(), any())).willReturn(false);

        worker.run(rewardRequestId);

        verify(redisExecutor, never()).execute(any());
        assertThat(requestStatus(rewardRequestId)).isEqualTo("RETRYABLE_FAILURE");
        assertThat(executions(rewardRequestId).get(0)).containsEntry("outcome", "BALANCE_NOT_LOADED")
                .containsEntry("reason", QuizRewardEarnWorker.BALANCE_LOAD_FAILED);
    }

    private Long quizRequestId(UUID rewardRequestId) {
        return jdbcTemplate.queryForObject("SELECT quiz_reward_request_id FROM ticket_quiz_reward_request "
                + "WHERE reward_request_id = ?", Long.class, rewardRequestId.toString());
    }

    /** 결과 없이 남은 실행(STARTED)과 요청 상태를 SQL로 만든다. */
    private void startedExecution(UUID rewardRequestId, int seq, String token, String requestStatus) {
        Long id = quizRequestId(rewardRequestId);
        jdbcTemplate.update("INSERT INTO ticket_quiz_reward_execution (quiz_reward_request_id, execution_seq, "
                + "execution_token, status, started_at) VALUES (?, ?, ?, 'STARTED', ?)", id, seq, token, now());
        jdbcTemplate.update("UPDATE ticket_quiz_reward_request SET status = ? WHERE quiz_reward_request_id = ?",
                requestStatus, id);
    }

    private void finishedExecution(UUID rewardRequestId, int seq, String outcome, String streamId,
                                   boolean notExecutedConfirmed, String requestStatus) {
        Long id = quizRequestId(rewardRequestId);
        jdbcTemplate.update("INSERT INTO ticket_quiz_reward_execution (quiz_reward_request_id, execution_seq, "
                        + "execution_token, status, outcome, stream_id, not_executed_confirmed, started_at, finished_at) "
                        + "VALUES (?, ?, ?, 'FINISHED', ?, ?, ?, ?, ?)", id, seq, UUID.randomUUID().toString(), outcome,
                streamId, notExecutedConfirmed, now(), now());
        jdbcTemplate.update("UPDATE ticket_quiz_reward_request SET status = ? WHERE quiz_reward_request_id = ?",
                requestStatus, id);
    }

    private long unsettledFor(UUID current, String currentToken) {
        return quizRewardRequestRepository.countUnsettled(member.getMemberId(), creator.getCreatorId(),
                quizRequestId(current), currentToken);
    }

    @Test
    void 같은_회원_Creator의_다른_미확정_Quiz_지급을_센다() {
        UUID current = admitted();
        String currentToken = UUID.randomUUID().toString();
        startedExecution(current, 1, currentToken, "EXECUTING");
        // 현재 실행권만 있으면 미확정 지급이 없다.
        assertThat(unsettledFor(current, currentToken)).isZero();

        // 다른 EXECUTING 요청
        UUID executing = admitted();
        startedExecution(executing, 1, UUID.randomUUID().toString(), "EXECUTING");
        assertThat(unsettledFor(current, currentToken)).isEqualTo(1);

        // 다른 UNKNOWN 요청
        UUID unknown = admitted();
        finishedExecution(unknown, 1, "UNKNOWN", null, false, "UNKNOWN");
        assertThat(unsettledFor(current, currentToken)).isEqualTo(2);

        // 원장에 반영되지 않은 ACCEPTED 요청. Stream 메시지가 유실됐어도 DB 상태만으로 센다.
        UUID accepted = admitted();
        finishedExecution(accepted, 1, "ACCEPTED", "1-0", false, "ACCEPTED");
        assertThat(unsettledFor(current, currentToken)).isEqualTo(3);
    }

    @Test
    void 현재_요청의_과거_실행이_무반영으로_확인되지_않았으면_센다() {
        UUID current = admitted();
        finishedExecution(current, 1, "UNKNOWN", null, false, "RETRYABLE_FAILURE");
        String currentToken = UUID.randomUUID().toString();
        startedExecution(current, 2, currentToken, "EXECUTING");

        assertThat(unsettledFor(current, currentToken)).isEqualTo(1);
    }

    @Test
    void 확정된_지급과_무반영_실행과_다른_회원_Creator는_세지_않는다() {
        UUID current = admitted();
        finishedExecution(current, 1, "RETRYABLE_FAILURE", null, true, "RETRYABLE_FAILURE");
        String currentToken = UUID.randomUUID().toString();
        startedExecution(current, 2, currentToken, "EXECUTING");
        // 원장 반영이 끝난 요청(실행이 결과 없이 남았어도)
        UUID applied = admitted();
        startedExecution(applied, 1, UUID.randomUUID().toString(), "EXECUTING");
        jdbcTemplate.update("UPDATE ticket_quiz_reward_request SET status = 'APPLIED', applied_at = ? "
                + "WHERE quiz_reward_request_id = ?", now(), quizRequestId(applied));
        // 무반영이 확인된 다른 요청, 아직 실행하지 않은 요청
        UUID retryable = admitted();
        finishedExecution(retryable, 1, "BALANCE_NOT_LOADED", null, true, "RETRYABLE_FAILURE");
        admitted();

        assertThat(unsettledFor(current, currentToken)).isZero();
        assertThat(quizRewardRequestRepository.countUnsettled(member.getMemberId() + 1_000_000,
                creator.getCreatorId(), quizRequestId(current), currentToken)).isZero();
    }

    @Test
    void Balance_키가_없고_미확정_지급이_있으면_적재도_Lua도_하지_않고_재시도_가능한_무반영으로_남긴다() {
        UUID unknown = admitted();
        finishedExecution(unknown, 1, "UNKNOWN", null, false, "UNKNOWN");
        UUID current = admitted();
        given(redisTemplate.hasKey(anyString())).willReturn(false);

        worker.run(current);

        verify(balanceKeyLoader, never()).load(any(), any(), any());
        verify(redisExecutor, never()).execute(any());
        assertThat(requestStatus(current)).isEqualTo("RETRYABLE_FAILURE");
        assertThat(executions(current).get(0)).containsEntry("outcome", "BALANCE_NOT_LOADED")
                .containsEntry("reason", QuizRewardEarnWorker.UNSETTLED_QUIZ_REWARD);
    }

    @Test
    void 다른_요청이_Redis_실행_중이면_Balance_키_적재를_막는다() {
        UUID first = admitted();
        UUID second = admitted();
        List<QuizRewardWorkResult> secondResults = new ArrayList<>();
        // 첫 요청의 Lua가 도는 동안(첫 요청은 EXECUTING, DB 잠금 없음) 두 번째 요청을 같은 회원·Creator로 실행한다.
        given(redisExecutor.execute(any())).willAnswer(invocation -> {
            given(redisTemplate.hasKey(anyString())).willReturn(false);
            secondResults.add(worker.run(second));
            return new QuizRedisEarnResult(QuizRedisEarnOutcome.ACCEPTED, "1-0", 3L, null, null);
        });

        worker.run(first);

        assertThat(secondResults).singleElement().satisfies(result ->
                assertThat(result.redisOutcome()).isEqualTo(QuizRedisEarnOutcome.BALANCE_NOT_LOADED));
        verify(balanceKeyLoader, never()).load(any(), any(), any());
        verify(redisExecutor, times(1)).execute(any());
        assertThat(executions(second).get(0)).containsEntry("reason", QuizRewardEarnWorker.UNSETTLED_QUIZ_REWARD);
        assertThat(requestStatus(first)).isEqualTo("ACCEPTED");
    }

    @Test
    void Balance_키가_없고_미확정_지급이_없으면_적재한_뒤_Lua를_부른다() {
        UUID current = admitted();
        given(redisTemplate.hasKey(anyString())).willReturn(false);
        given(balanceKeyLoader.load(any(), any(), any())).willReturn(true);
        given(redisExecutor.execute(any())).willReturn(
                new QuizRedisEarnResult(QuizRedisEarnOutcome.ACCEPTED, "1-0", 3L, null, null));

        worker.run(current);

        verify(balanceKeyLoader).load(any(), any(), any());
        verify(redisExecutor).execute(any());
        assertThat(requestStatus(current)).isEqualTo("ACCEPTED");
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-12T06:00:00Z"), ZoneOffset.UTC);
        }
    }
}

package kr.co.cking.ticket.application;

import jakarta.persistence.EntityManager;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.QuizRewardExecutionClaim.Outcome;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.TicketQuizRewardExecution;
import kr.co.cking.ticket.repository.TicketQuizRewardExecutionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.reset;

/**
 * 실제 MySQL로 Quiz 보상 실행권 발급의 단일성·롤백과 V62 실행 이력 제약을 확인한다(이슈 #533). CI에서만 실행한다.
 * Redis는 쓰지 않는다. 재시도 가능한 실패 상태는 결과 기록 단계가 아직 없어 SQL로 직접 만든다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TicketQuizRewardService.class, QuizRewardRequestStore.class, QuizRewardExecutionClaimService.class,
        QuizRewardExecutionStore.class, QuizRewardExecutionClaimIntegrationTest.FixedClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuizRewardExecutionClaimIntegrationTest {

    @MockitoSpyBean
    private TicketQuizRewardExecutionRepository executionRepository;

    @Autowired
    private TicketQuizRewardService admissionService;
    @Autowired
    private QuizRewardExecutionClaimService claimService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    private Member member;
    private Creator creator;
    private final List<String> requestIds = new ArrayList<>();
    private long nextAttemptId = System.nanoTime() & 0x3FFFFFFFFFFFL;

    @BeforeEach
    void setUp() {
        reset(executionRepository);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        member = memberRepository.saveAndFlush(new Member("quiz-exec-" + suffix, null, null, MemberRole.USER));
        Member creatorMember = memberRepository.saveAndFlush(
                new Member("quiz-exec-creator-" + suffix, null, null, MemberRole.USER));
        creator = creatorRepository.saveAndFlush(new Creator(creatorMember.getMemberId(), "creator-" + suffix));
    }

    @AfterEach
    void cleanUp() {
        reset(executionRepository);
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

    private QuizRewardCommand admitted() {
        QuizRewardCommand command = new QuizRewardCommand(nextAttemptId++, member.getMemberId(),
                creator.getCreatorId(), 3, UUID.randomUUID());
        requestIds.add(command.rewardRequestId().toString());
        admissionService.admit(command);
        return command;
    }

    private Map<String, Object> request(QuizRewardCommand command) {
        return jdbcTemplate.queryForMap("SELECT quiz_reward_request_id, status FROM ticket_quiz_reward_request "
                + "WHERE reward_request_id = ?", command.rewardRequestId().toString());
    }

    private List<Map<String, Object>> executions(QuizRewardCommand command) {
        return jdbcTemplate.queryForList("SELECT e.execution_seq, e.execution_token, e.status, e.outcome "
                + "FROM ticket_quiz_reward_execution e JOIN ticket_quiz_reward_request r "
                + "ON e.quiz_reward_request_id = r.quiz_reward_request_id WHERE r.reward_request_id = ? "
                + "ORDER BY e.execution_seq", command.rewardRequestId().toString());
    }

    /** 결과 기록 단계가 아직 없어, 마지막 실행을 무반영이 확인된 재시도 가능한 실패로 직접 만든다. */
    private void finishAsRetryable(QuizRewardCommand command, int seq) {
        Long requestId = (Long) request(command).get("quiz_reward_request_id");
        jdbcTemplate.update("UPDATE ticket_quiz_reward_execution SET status = 'FINISHED', outcome = 'RETRYABLE_FAILURE', "
                + "reason = 'XADD_FAILED', not_executed_confirmed = TRUE, finished_at = ? "
                + "WHERE quiz_reward_request_id = ? AND execution_seq = ?", now(), requestId, seq);
        jdbcTemplate.update("UPDATE ticket_quiz_reward_request SET status = 'RETRYABLE_FAILURE' "
                + "WHERE quiz_reward_request_id = ?", requestId);
    }

    @Test
    void 실행권을_커밋하고_요청을_EXECUTING으로_바꾸며_중복_발급은_막는다() {
        QuizRewardCommand command = admitted();

        QuizRewardExecutionClaim first = claimService.claim(command);
        QuizRewardExecutionClaim second = claimService.claim(command);

        assertThat(first.outcome()).isEqualTo(Outcome.CLAIMED);
        assertThat(first.executionSeq()).isEqualTo(1);
        assertThat(second).isEqualTo(QuizRewardExecutionClaim.rejected(Outcome.ALREADY_EXECUTING));
        assertThat(request(command)).containsEntry("status", "EXECUTING");
        assertThat(executions(command)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("execution_token", first.executionToken())
                        .containsEntry("status", "STARTED").containsEntry("outcome", null));
    }

    @Test
    void 동시에_발급해도_실행권은_하나만_생긴다() throws Exception {
        QuizRewardCommand command = admitted();
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<QuizRewardExecutionClaim>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return claimService.claim(command);
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<QuizRewardExecutionClaim> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS).outcome());
            }

            assertThat(outcomes).filteredOn(Outcome.CLAIMED::equals).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> outcome != Outcome.CLAIMED)
                    .containsOnly(Outcome.ALREADY_EXECUTING);
            assertThat(executions(command)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 무반영이_확인된_실패만_새_순번으로_다시_발급하고_이전_실행은_그대로_남는다() {
        QuizRewardCommand command = admitted();
        QuizRewardExecutionClaim first = claimService.claim(command);
        finishAsRetryable(command, 1);

        QuizRewardExecutionClaim retry = claimService.claim(command);

        assertThat(retry.outcome()).isEqualTo(Outcome.CLAIMED);
        assertThat(retry.executionSeq()).isEqualTo(2);
        assertThat(retry.executionToken()).isNotEqualTo(first.executionToken());
        List<Map<String, Object>> rows = executions(command);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("execution_token", first.executionToken())
                .containsEntry("status", "FINISHED").containsEntry("outcome", "RETRYABLE_FAILURE");
        assertThat(rows.get(1)).containsEntry("execution_token", retry.executionToken())
                .containsEntry("status", "STARTED");
    }

    @Test
    void 무반영_증거가_없는_RETRYABLE_FAILURE와_UNKNOWN_APPLIED는_다시_발급하지_않는다() {
        QuizRewardCommand command = admitted();
        claimService.claim(command);
        Long requestId = (Long) request(command).get("quiz_reward_request_id");
        jdbcTemplate.update("UPDATE ticket_quiz_reward_execution SET status = 'FINISHED', outcome = 'UNKNOWN', "
                + "finished_at = ? WHERE quiz_reward_request_id = ?", now(), requestId);

        jdbcTemplate.update("UPDATE ticket_quiz_reward_request SET status = 'RETRYABLE_FAILURE' "
                + "WHERE quiz_reward_request_id = ?", requestId);
        assertThat(claimService.claim(command).outcome()).isEqualTo(Outcome.NOT_RETRYABLE);
        jdbcTemplate.update("UPDATE ticket_quiz_reward_request SET status = 'UNKNOWN' "
                + "WHERE quiz_reward_request_id = ?", requestId);
        assertThat(claimService.claim(command).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(executions(command)).hasSize(1);
    }

    @Test
    void 실행_기록_저장_직후_실패하면_실행_기록이_롤백된다() {
        QuizRewardCommand command = admitted();
        List<Long> inserted = new ArrayList<>();
        try (MockedStatic<QuizRewardExecutionClaim> claimed =
                     mockStatic(QuizRewardExecutionClaim.class, CALLS_REAL_METHODS)) {
            claimed.when(() -> QuizRewardExecutionClaim.claimed(any(), anyString(), anyInt()))
                    .thenAnswer(invocation -> {
                        entityManager.flush();
                        inserted.add(((Number) entityManager.createNativeQuery(
                                "SELECT COUNT(*) FROM ticket_quiz_reward_execution e "
                                        + "JOIN ticket_quiz_reward_request r "
                                        + "ON e.quiz_reward_request_id = r.quiz_reward_request_id "
                                        + "WHERE r.reward_request_id = ?1 AND e.status = 'STARTED'")
                                .setParameter(1, command.rewardRequestId().toString()).getSingleResult()).longValue());
                        throw new IllegalStateException("저장 직후 실패");
                    });
            assertThatThrownBy(() -> claimService.claim(command)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("저장 직후 실패");
        }
        assertThat(inserted).containsExactly(1L);

        assertThat(executions(command)).isEmpty();
        assertThat(request(command)).containsEntry("status", "RECEIVED");
        reset(executionRepository);
        assertThat(claimService.claim(command).outcome()).isEqualTo(Outcome.CLAIMED);
    }

    @Test
    void V62는_진행_중_실행_중복_실행권_중복_순번_중복_대문자_실행권을_막는다() {
        QuizRewardCommand command = admitted();
        QuizRewardExecutionClaim first = claimService.claim(command);
        Long requestId = (Long) request(command).get("quiz_reward_request_id");
        String insert = "INSERT INTO ticket_quiz_reward_execution (quiz_reward_request_id, execution_seq, "
                + "execution_token, status, started_at) VALUES (?, ?, ?, 'STARTED', ?)";

        // 결과가 기록되지 않은 실행은 요청마다 하나다.
        assertThatThrownBy(() -> jdbcTemplate.update(insert, requestId, 2, UUID.randomUUID().toString(),
                now())).isInstanceOf(DataAccessException.class);
        finishAsRetryable(command, 1);
        // 실행권은 전역에서 유일하다.
        assertThatThrownBy(() -> jdbcTemplate.update(insert, requestId, 2, first.executionToken(), now()))
                .isInstanceOf(DataAccessException.class);
        // 순번은 요청 안에서 유일하다.
        assertThatThrownBy(() -> jdbcTemplate.update(insert, requestId, 1, UUID.randomUUID().toString(),
                now())).isInstanceOf(DataAccessException.class);
        // 실행권은 소문자 UUID다.
        assertThatThrownBy(() -> jdbcTemplate.update(insert, requestId, 2,
                UUID.randomUUID().toString().toUpperCase(), now())).isInstanceOf(DataAccessException.class);
        // STARTED에는 결과가 없다.
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO ticket_quiz_reward_execution "
                + "(quiz_reward_request_id, execution_seq, execution_token, status, outcome, started_at) "
                + "VALUES (?, 2, ?, 'STARTED', 'ACCEPTED', ?)", requestId, UUID.randomUUID().toString(),
                now())).isInstanceOf(DataAccessException.class);
        // 무반영 확인은 무반영 결과에만 있을 수 있다.
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE ticket_quiz_reward_execution SET outcome = 'UNKNOWN' "
                + "WHERE quiz_reward_request_id = ? AND execution_seq = 1", requestId))
                .isInstanceOf(DataAccessException.class);
        assertThat(executions(command)).hasSize(1);
    }

    @Test
    void 실행_기록_INSERT와_EXECUTING_UPDATE가_DB에_반영된_뒤_실패해도_모두_롤백된다() {
        QuizRewardCommand command = admitted();
        String requestId = command.rewardRequestId().toString();
        List<Object> seenBeforeFailure = new ArrayList<>();
        // 운영 코드에 hook을 두지 않고, 상태 변경 뒤 마지막 단계(결과 생성)에서 실패시킨다. 실패 직전에 flush해 INSERT와
        // UPDATE를 실제로 DB에 보낸 뒤, 같은 Transaction에서 반영된 것을 확인하고 예외를 던진다.
        try (MockedStatic<QuizRewardExecutionClaim> claimed =
                     mockStatic(QuizRewardExecutionClaim.class, CALLS_REAL_METHODS)) {
            claimed.when(() -> QuizRewardExecutionClaim.claimed(any(), anyString(), anyInt())).thenAnswer(invocation -> {
                entityManager.flush();
                seenBeforeFailure.add(entityManager.createNativeQuery(
                        "SELECT status FROM ticket_quiz_reward_request WHERE reward_request_id = ?1")
                        .setParameter(1, requestId).getSingleResult());
                seenBeforeFailure.add(((Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM ticket_quiz_reward_execution e JOIN ticket_quiz_reward_request r "
                                + "ON e.quiz_reward_request_id = r.quiz_reward_request_id "
                                + "WHERE r.reward_request_id = ?1 AND e.status = 'STARTED'")
                        .setParameter(1, requestId).getSingleResult()).longValue());
                throw new IllegalStateException("상태 변경 후 실패");
            });

            assertThatThrownBy(() -> claimService.claim(command)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("상태 변경 후 실패");
        }

        assertThat(seenBeforeFailure).containsExactly("EXECUTING", 1L);
        assertThat(executions(command)).isEmpty();
        assertThat(request(command)).containsEntry("status", "RECEIVED");
        assertThat(claimService.claim(command).outcome()).isEqualTo(Outcome.CLAIMED);
    }

    @Test
    void 무반영_확인_값은_0과_1만_허용한다() {
        QuizRewardCommand command = admitted();
        Long requestId = (Long) request(command).get("quiz_reward_request_id");
        String insert = "INSERT INTO ticket_quiz_reward_execution (quiz_reward_request_id, execution_seq, "
                + "execution_token, status, outcome, reason, not_executed_confirmed, started_at, finished_at) "
                + "VALUES (?, ?, ?, 'FINISHED', ?, ?, ?, ?, ?)";

        // 2·-1은 무반영 분류(RETRYABLE_FAILURE)여도 거부한다.
        assertThatThrownBy(() -> jdbcTemplate.update(insert, requestId, 1, UUID.randomUUID().toString(),
                "RETRYABLE_FAILURE", "XADD_FAILED", 2, now(), now())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, requestId, 1, UUID.randomUUID().toString(),
                "RETRYABLE_FAILURE", "XADD_FAILED", -1, now(), now())).isInstanceOf(DataAccessException.class);
        // 0은 결과가 있는 어떤 분류에도, 1은 무반영 분류에만 쓸 수 있다.
        jdbcTemplate.update(insert, requestId, 1, UUID.randomUUID().toString(), "UNKNOWN", "GUARD_PROCESSING", 0,
                now(), now());
        jdbcTemplate.update(insert, requestId, 2, UUID.randomUUID().toString(), "RETRYABLE_FAILURE", "XADD_FAILED",
                1, now(), now());

        assertThat(jdbcTemplate.queryForList("SELECT not_executed_confirmed FROM ticket_quiz_reward_execution "
                + "WHERE quiz_reward_request_id = ? ORDER BY execution_seq", Integer.class, requestId))
                .containsExactly(0, 1);
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

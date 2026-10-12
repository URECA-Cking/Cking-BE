package kr.co.cking.ticket.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnRewardPolicy;
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
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 MySQL로 Quiz 보상 접수의 원자성·멱등성·동시성을 확인한다(이슈 #533). CI에서만 실행한다. Redis·Stream·원장은
 * 쓰지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TicketQuizRewardService.class, QuizRewardRequestStore.class, TicketEarnRequestClaimService.class,
        TicketQuizRewardServiceIntegrationTest.FixedClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TicketQuizRewardServiceIntegrationTest {

    @Autowired
    private TicketQuizRewardService service;
    @Autowired
    private QuizRewardRequestStore store;
    @Autowired
    private TicketEarnRequestClaimService missionClaimService;
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
        member = memberRepository.saveAndFlush(new Member("quiz-reward-" + suffix, null, null, MemberRole.USER));
        Member creatorMember = memberRepository.saveAndFlush(
                new Member("quiz-reward-creator-" + suffix, null, null, MemberRole.USER));
        creator = creatorRepository.saveAndFlush(new Creator(creatorMember.getMemberId(), "creator-" + suffix));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ticket_quiz_reward_request WHERE member_id = ?", member.getMemberId());
        for (String requestId : requestIds) {
            jdbcTemplate.update("DELETE FROM ticket_earn_request WHERE request_id = ?", requestId);
        }
        creatorRepository.delete(creator);
        memberRepository.deleteById(creator.getMemberId());
        memberRepository.delete(member);
    }

    @Test
    void 전역_선점과_Quiz_요청이_함께_생기고_같은_요청은_재요청이다() {
        QuizRewardCommand command = command(attemptId(), 3, newRequestId());

        QuizRewardAdmission received = service.admit(command);
        QuizRewardAdmission replayed = service.admit(command);

        assertThat(received.outcome()).isEqualTo(QuizRewardAdmission.Outcome.RECEIVED);
        assertThat(received.status()).isEqualTo(QuizRewardRequestStatus.RECEIVED);
        assertThat(replayed).isEqualTo(QuizRewardAdmission.replayed(received.quizRewardRequestId(),
                QuizRewardRequestStatus.RECEIVED));
        assertThat(jdbcTemplate.queryForMap("SELECT reward_policy, status, payload_fingerprint FROM ticket_earn_request "
                + "WHERE request_id = ?", command.rewardRequestId().toString()))
                .containsEntry("reward_policy", "QUIZ").containsEntry("status", "PENDING")
                .containsEntry("payload_fingerprint", command.computeFingerprint());
        assertThat(jdbcTemplate.queryForMap("SELECT status, amount, correct_count FROM ticket_quiz_reward_request "
                + "WHERE reward_request_id = ?", command.rewardRequestId().toString()))
                .containsEntry("status", "RECEIVED").containsEntry("amount", 3L).containsEntry("correct_count", 3);
    }

    @Test
    void 같은_UUID에_다른_입력과_같은_attempt에_다른_UUID는_충돌이고_기존_요청은_그대로다() {
        long attemptId = attemptId();
        UUID requestId = newRequestId();
        service.admit(command(attemptId, 3, requestId));

        assertThat(service.admit(command(attemptId, 2, requestId)).outcome())
                .isEqualTo(QuizRewardAdmission.Outcome.REQUEST_ID_CONFLICT);
        assertThat(service.admit(command(attemptId, 3, newRequestId())).outcome())
                .isEqualTo(QuizRewardAdmission.Outcome.ATTEMPT_CONFLICT);
        assertThat(quizRequestCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT correct_count FROM ticket_quiz_reward_request "
                + "WHERE attempt_id = ?", Integer.class, attemptId)).isEqualTo(3);
        assertThat(quizClaimCount()).isEqualTo(1);
    }

    @Test
    void Mission이_먼저_쓴_UUID는_Quiz_재요청이_아니라_충돌이고_Mission_선점은_그대로다() {
        UUID requestId = newRequestId();
        EarnCommand mission = new EarnCommand(requestId, member.getMemberId(), creator.getCreatorId(), "LIKE", 3L,
                "2026-10-12", "like:1", 1L, EarnRewardPolicy.DAILY);
        assertThat(missionClaimService.claim(mission)).isEqualTo(TicketEarnRequestClaim.PENDING);

        assertThat(service.admit(command(attemptId(), 3, requestId)).outcome())
                .isEqualTo(QuizRewardAdmission.Outcome.REQUEST_ID_CONFLICT);
        assertThat(quizRequestCount()).isZero();
        assertThat(missionClaimService.claim(mission)).isEqualTo(TicketEarnRequestClaim.PENDING);
    }

    @Test
    void 회원이_없으면_전역_선점도_남기지_않는다() {
        UUID requestId = newRequestId();

        assertThatThrownBy(() -> service.admit(new QuizRewardCommand(attemptId(), Long.MAX_VALUE,
                creator.getCreatorId(), 3, requestId))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket_earn_request WHERE request_id = ?",
                Integer.class, requestId.toString())).isZero();
    }

    @Test
    void 같은_UUID_동시_접수는_한_번만_만들고_나머지는_재요청이다() throws Exception {
        QuizRewardCommand command = command(attemptId(), 3, newRequestId());

        List<Object> outcomes = concurrently(8, () -> service.admit(command).outcome());

        assertThat(outcomes).doesNotHaveAnyElementsOfTypes(Throwable.class);
        assertThat(outcomes).containsOnlyOnce(QuizRewardAdmission.Outcome.RECEIVED)
                .containsOnly(QuizRewardAdmission.Outcome.RECEIVED, QuizRewardAdmission.Outcome.REPLAYED);
        assertThat(quizRequestCount()).isEqualTo(1);
        assertThat(quizClaimCount()).isEqualTo(1);
    }

    @Test
    void 같은_attempt_다른_UUID_동시_접수는_한_번만_만들고_실패한_쪽은_전역_선점을_남기지_않는다() throws Exception {
        long attemptId = attemptId();
        List<UUID> uuids = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            uuids.add(newRequestId());
        }
        AtomicInteger next = new AtomicInteger();

        // 스레드마다 쓴 UUID와 결과(접수 결과 또는 예외)를 함께 남긴다.
        List<Object> outcomes = concurrently(8, () -> {
            UUID requestId = uuids.get(next.getAndIncrement());
            try {
                return Map.entry(requestId, (Object) service.admit(command(attemptId, 3, requestId)).outcome());
            } catch (RuntimeException exception) {
                return Map.entry(requestId, (Object) exception);
            }
        });

        long received = 0;
        for (Object outcome : outcomes) {
            @SuppressWarnings("unchecked")
            Map.Entry<UUID, Object> entry = (Map.Entry<UUID, Object>) outcome;
            Object result = entry.getValue();
            if (result == QuizRewardAdmission.Outcome.RECEIVED) {
                received++;
                continue;
            }
            if (result instanceof RuntimeException exception) {
                // 잠금 경쟁으로 롤백돼 다시 봤을 때 이긴 쪽이 아직 커밋 전인 경우만 허용한다. 정합성·호출 조건 오류는 실패다.
                assertThat(exception).isExactlyInstanceOf(QuizRewardAdmissionRetryableException.class)
                        .hasCauseInstanceOf(ConcurrencyFailureException.class);
                result = service.admit(command(attemptId, 3, entry.getKey())).outcome();
            }
            assertThat(result).isEqualTo(QuizRewardAdmission.Outcome.ATTEMPT_CONFLICT);
        }
        assertThat(received).isEqualTo(1);
        assertThat(quizRequestCount()).isEqualTo(1);
        assertThat(quizClaimCount()).isEqualTo(1);
    }

    @Test
    void 전역_선점_INSERT_뒤_Quiz_요청_INSERT가_실패하면_전역_선점도_함께_롤백되고_Mission_선점은_그대로_쓸_수_있다() {
        long attemptId = attemptId();
        service.admit(command(attemptId, 3, newRequestId()));
        UUID second = newRequestId();

        // 사전 비교를 거치지 않고 접수 Transaction을 바로 실행해, 첫 INSERT(전역 선점)는 성공하고 두 번째 INSERT(Quiz
        // 요청)가 attempt 고유 제약에 걸리게 한다.
        assertThatThrownBy(() -> store.insert(command(attemptId, 3, second)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket_earn_request WHERE request_id = ?",
                Integer.class, second.toString())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket_quiz_reward_request "
                + "WHERE reward_request_id = ?", Integer.class, second.toString())).isZero();
        assertThat(orphanQuizClaims()).isZero();
        EarnCommand mission = new EarnCommand(second, member.getMemberId(), creator.getCreatorId(), "LIKE", 3L,
                "2026-10-12", "like:1", 1L, EarnRewardPolicy.DAILY);
        assertThat(missionClaimService.claim(mission)).isEqualTo(TicketEarnRequestClaim.PENDING);
    }

    @Test
    void DB는_0점_보상과_보상량_불일치와_없는_상태를_거부한다() {
        String base = "INSERT INTO ticket_quiz_reward_request (reward_request_id, attempt_id, member_id, creator_id, "
                + "correct_count, amount, fingerprint_version, payload_fingerprint, status, created_at, applied_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'v', ?, ?, UTC_TIMESTAMP(6), NULL)";
        Object[][] invalid = {
                {0, 0L, "RECEIVED"},
                {3, 2L, "RECEIVED"},
                {3, 3L, "DONE"},
                {3, 3L, "APPLIED"}};
        for (Object[] values : invalid) {
            assertThatThrownBy(() -> jdbcTemplate.update(base, UUID.randomUUID().toString(), attemptId(),
                    member.getMemberId(), creator.getCreatorId(), values[0], values[1], "f".repeat(64), values[2]))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    private QuizRewardCommand command(long attemptId, int correctCount, UUID requestId) {
        return new QuizRewardCommand(attemptId, member.getMemberId(), creator.getCreatorId(), correctCount, requestId);
    }

    private UUID newRequestId() {
        UUID requestId = UUID.randomUUID();
        requestIds.add(requestId.toString());
        return requestId;
    }

    /** attempt 테이블이 없어 FK가 없다. 테스트끼리 겹치지 않는 값을 쓴다. */
    private static long attemptId() {
        return Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000_000L) + 1;
    }

    private int quizRequestCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket_quiz_reward_request WHERE member_id = ?",
                Integer.class, member.getMemberId());
    }

    private int quizClaimCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket_earn_request r JOIN ticket_quiz_reward_request q "
                + "ON r.request_id = q.reward_request_id WHERE q.member_id = ? AND r.reward_policy = 'QUIZ'",
                Integer.class, member.getMemberId())
                + orphanQuizClaims();
    }

    /** Quiz 요청 없이 남은 QUIZ 전역 선점 수. 원자성이 지켜지면 0이다. */
    private int orphanQuizClaims() {
        int count = 0;
        for (String requestId : requestIds) {
            count += jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket_earn_request r WHERE r.request_id = ? "
                    + "AND r.reward_policy = 'QUIZ' AND NOT EXISTS (SELECT 1 FROM ticket_quiz_reward_request q "
                    + "WHERE q.reward_request_id = r.request_id)", Integer.class, requestId);
        }
        return count;
    }

    private List<Object> concurrently(int count, Callable<Object> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    outcomes.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    outcomes.add(exception.getCause());
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}

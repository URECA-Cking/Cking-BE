package kr.co.cking.ticket.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.dto.QuizEarnEvent;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.TicketLedger;
import kr.co.cking.ticket.repository.TicketLedgerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.reset;

/**
 * 실제 MySQL로 Quiz 보상 EARN 반영(원장·DB 잔액·APPLIED)의 원자성·멱등성과 V61 원장 제약을 확인한다(이슈 #533).
 * CI에서만 실행한다. Redis는 쓰지 않고, 메시지는 정상 소비와 같은 분기({@link TicketEarnEventDispatcher})로 넣는다.
 * Redis 실행 단계가 아직 없으므로 요청을 EXECUTING으로 직접 바꾸고, 운영 기본 정책(fail-closed) 대신 상태만 보는
 * 테스트 정책({@link StatusOnlyTestQuizRewardExecutionPolicy})으로 원장 반영 동작을 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TicketQuizRewardService.class, QuizRewardRequestStore.class, QuizRewardLedgerService.class,
        TicketEarnEventDispatcher.class, TicketEarnLedgerService.class,
        QuizRewardLedgerServiceIntegrationTest.FixedClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuizRewardLedgerServiceIntegrationTest {

    @MockitoSpyBean
    private TicketLedgerRepository ticketLedgerRepository;

    @Autowired
    private TicketQuizRewardService admissionService;
    @Autowired
    private TicketEarnEventDispatcher dispatcher;
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
        reset(ticketLedgerRepository);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        member = memberRepository.saveAndFlush(new Member("quiz-ledger-" + suffix, null, null, MemberRole.USER));
        Member creatorMember = memberRepository.saveAndFlush(
                new Member("quiz-ledger-creator-" + suffix, null, null, MemberRole.USER));
        creator = creatorRepository.saveAndFlush(new Creator(creatorMember.getMemberId(), "creator-" + suffix));
    }

    @AfterEach
    void cleanUp() {
        reset(ticketLedgerRepository);
        jdbcTemplate.update("DELETE FROM ticket_ledger WHERE member_id = ?", member.getMemberId());
        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ?", member.getMemberId());
        jdbcTemplate.update("DELETE FROM ticket_quiz_reward_request WHERE member_id = ?", member.getMemberId());
        for (String requestId : requestIds) {
            jdbcTemplate.update("DELETE FROM ticket_earn_request WHERE request_id = ?", requestId);
        }
        creatorRepository.delete(creator);
        memberRepository.deleteById(creator.getMemberId());
        memberRepository.delete(member);
    }

    @Test
    void 원장_DB_잔액_APPLIED_ACCEPTED가_함께_커밋되고_중복_전달은_다시_반영하지_않는다() {
        QuizEarnEvent event = executing(3);

        dispatcher.apply(event.toStreamFields());
        dispatcher.apply(event.toStreamFields());

        String requestId = event.requestId().toString();
        assertThat(count("SELECT COUNT(*) FROM ticket_ledger WHERE request_id = ?", requestId)).isEqualTo(1);
        Map<String, Object> ledger = jdbcTemplate.queryForMap("SELECT type, delta_amount, mission_completion_id, "
                + "quiz_reward_request_id FROM ticket_ledger WHERE request_id = ?", requestId);
        assertThat(ledger).containsEntry("type", "EARN").containsEntry("delta_amount", 3L)
                .containsEntry("mission_completion_id", null);
        assertThat(ledger.get("quiz_reward_request_id")).isNotNull();
        assertThat(balance()).isEqualTo(3L);
        assertThat(jdbcTemplate.queryForMap("SELECT status, applied_at FROM ticket_quiz_reward_request "
                + "WHERE reward_request_id = ?", requestId)).containsEntry("status", "APPLIED")
                .extractingByKey("applied_at").isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ticket_earn_request WHERE request_id = ?",
                String.class, requestId)).isEqualTo("ACCEPTED");
    }

    @Test
    void 반영된_요청과_다른_메시지는_정상_중복으로_보지_않고_거부한다() {
        QuizEarnEvent event = executing(3);
        dispatcher.apply(event.toStreamFields());
        QuizRewardCommand otherAttempt = new QuizRewardCommand(attemptId(), member.getMemberId(),
                creator.getCreatorId(), 3, event.requestId());
        QuizEarnEvent other = new QuizEarnEvent(event.requestId(), otherAttempt.attemptId(), member.getMemberId(),
                creator.getCreatorId(), 3, 3L, QuizRewardCommand.FINGERPRINT_VERSION, otherAttempt.computeFingerprint(),
                "token-2");

        assertThatThrownBy(() -> dispatcher.apply(other.toStreamFields())).isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT COUNT(*) FROM ticket_ledger WHERE member_id = ?", member.getMemberId())).isEqualTo(1);
        assertThat(balance()).isEqualTo(3L);
    }

    @Test
    void 원장_저장이_실패하면_새로_만든_잔액과_상태_변경까지_모두_롤백된다() {
        QuizEarnEvent event = executing(3);
        willThrow(new IllegalStateException("ledger insert failed")).given(ticketLedgerRepository)
                .save(any(TicketLedger.class));

        assertThatThrownBy(() -> dispatcher.apply(event.toStreamFields())).isInstanceOf(IllegalStateException.class);

        String requestId = event.requestId().toString();
        assertThat(count("SELECT COUNT(*) FROM ticket_ledger WHERE request_id = ?", requestId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_ticket_balance WHERE member_id = ?", member.getMemberId()))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ticket_quiz_reward_request "
                + "WHERE reward_request_id = ?", String.class, requestId)).isEqualTo("EXECUTING");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ticket_earn_request WHERE request_id = ?",
                String.class, requestId)).isEqualTo("PENDING");
    }

    @Test
    void 실행을_시작하지_않은_요청의_메시지는_반영하지_않는다() {
        QuizRewardCommand command = command(3);
        admissionService.admit(command);
        QuizEarnEvent event = event(command);

        assertThatThrownBy(() -> dispatcher.apply(event.toStreamFields())).isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT COUNT(*) FROM ticket_ledger WHERE member_id = ?", member.getMemberId())).isZero();
    }

    @Test
    void 같은_회원_Creator의_첫_Balance_생성_경쟁도_재전달로_모두_한_번씩_반영된다() throws Exception {
        List<QuizEarnEvent> events = List.of(executing(2), executing(3));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (QuizEarnEvent event : events) {
                futures.add(executor.submit(() -> {
                    start.await();
                    dispatcher.apply(event.toStreamFields());
                    return null;
                }));
            }
            start.countDown();
            for (int index = 0; index < futures.size(); index++) {
                try {
                    futures.get(index).get(30, TimeUnit.SECONDS);
                } catch (ExecutionException exception) {
                    // Balance PK 경쟁으로 롤백된 쪽은 XACK되지 않아 재전달된다. 같은 메시지로 다시 반영한다.
                    dispatcher.apply(events.get(index).toStreamFields());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(count("SELECT COUNT(*) FROM ticket_ledger WHERE member_id = ?", member.getMemberId())).isEqualTo(2);
        assertThat(balance()).isEqualTo(5L);
    }

    @Test
    void 원장은_EARN_원인을_정확히_하나만_갖고_Quiz_요청당_한_줄이며_COMPENSATE는_그대로다() {
        QuizEarnEvent event = executing(3);
        dispatcher.apply(event.toStreamFields());
        Long quizRequestId = jdbcTemplate.queryForObject("SELECT quiz_reward_request_id FROM ticket_quiz_reward_request "
                + "WHERE reward_request_id = ?", Long.class, event.requestId().toString());
        String insert = "INSERT INTO ticket_ledger (member_id, creator_id, event_entry_id, mission_completion_id, "
                + "quiz_reward_request_id, delta_amount, type, request_id, created_at) "
                + "VALUES (?, ?, NULL, NULL, ?, ?, ?, ?, UTC_TIMESTAMP(6))";
        Long memberId = member.getMemberId();
        Long creatorId = creator.getCreatorId();

        // EARN인데 원인이 없다.
        assertThatThrownBy(() -> jdbcTemplate.update(insert, memberId, creatorId, null, 1L, "EARN",
                UUID.randomUUID().toString())).isInstanceOf(DataAccessException.class);
        // 같은 Quiz 요청을 두 번째 원장으로 반영한다(요청 ID도 달라 FK·UNIQUE에 걸린다).
        assertThatThrownBy(() -> jdbcTemplate.update(insert, memberId, creatorId, quizRequestId, 3L, "EARN",
                UUID.randomUUID().toString())).isInstanceOf(DataAccessException.class);
        // 없는 Quiz 요청을 가리킨다.
        assertThatThrownBy(() -> jdbcTemplate.update(insert, memberId, creatorId, Long.MAX_VALUE, 3L, "EARN",
                UUID.randomUUID().toString())).isInstanceOf(DataAccessException.class);
        // SPEND에 Quiz 원인을 붙인다.
        assertThatThrownBy(() -> jdbcTemplate.update(insert, memberId, creatorId, quizRequestId, -1L, "SPEND",
                event.requestId().toString())).isInstanceOf(DataAccessException.class);
        // COMPENSATE는 기존처럼 원인 없이 기록된다.
        assertThat(jdbcTemplate.update(insert, memberId, creatorId, null, 1L, "COMPENSATE", null)).isEqualTo(1);
    }

    private QuizEarnEvent executing(int correctCount) {
        QuizRewardCommand command = command(correctCount);
        admissionService.admit(command);
        jdbcTemplate.update("UPDATE ticket_quiz_reward_request SET status = 'EXECUTING' WHERE reward_request_id = ?",
                command.rewardRequestId().toString());
        return event(command);
    }

    private static QuizEarnEvent event(QuizRewardCommand command) {
        return new QuizEarnEvent(command.rewardRequestId(), command.attemptId(), command.memberId(),
                command.creatorId(), command.correctCount(), command.amount(), QuizRewardCommand.FINGERPRINT_VERSION,
                command.computeFingerprint(), "token-1");
    }

    private QuizRewardCommand command(int correctCount) {
        UUID requestId = UUID.randomUUID();
        requestIds.add(requestId.toString());
        return new QuizRewardCommand(attemptId(), member.getMemberId(), creator.getCreatorId(), correctCount,
                requestId);
    }

    private static long attemptId() {
        return Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000_000L) + 1;
    }

    private long balance() {
        return jdbcTemplate.queryForObject("SELECT balance FROM user_ticket_balance WHERE member_id = ? "
                + "AND creator_id = ?", Long.class, member.getMemberId(), creator.getCreatorId());
    }

    private int count(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, Integer.class, argument);
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        QuizRewardExecutionPolicy quizRewardExecutionPolicy() {
            return new StatusOnlyTestQuizRewardExecutionPolicy();
        }
    }
}

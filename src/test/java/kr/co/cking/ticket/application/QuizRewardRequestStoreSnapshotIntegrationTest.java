package kr.co.cking.ticket.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.QuizRewardRequestStatus;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.willAnswer;

/**
 * Quiz 보상 접수 비교가 두 테이블을 같은 스냅샷에서 읽는지 실제 MySQL로 확인한다(이슈 #533). CI에서만 실행한다.
 *
 * <p>DB 기본 격리 수준을 READ_COMMITTED로 바꾼 뒤, 비교 Transaction이 Quiz 요청을 읽은 직후 다른 연결에서 같은 요청의
 * 전역 선점과 Quiz 요청을 커밋한다. 같은 스냅샷이 아니면 전역 선점만 보여 정상 재요청을 REQUEST_ID_CONFLICT로 잘못
 * 판정한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TicketQuizRewardService.class, QuizRewardRequestStore.class,
        QuizRewardRequestStoreSnapshotIntegrationTest.FixedClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED")
class QuizRewardRequestStoreSnapshotIntegrationTest {

    @MockitoSpyBean
    private TicketQuizRewardRequestRepository quizRewardRequestRepository;

    @Autowired
    private TicketQuizRewardService service;
    @Autowired
    private QuizRewardRequestStore store;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Member member;
    private Creator creator;
    private QuizRewardCommand command;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        member = memberRepository.saveAndFlush(new Member("quiz-snapshot-" + suffix, null, null, MemberRole.USER));
        Member creatorMember = memberRepository.saveAndFlush(
                new Member("quiz-snapshot-creator-" + suffix, null, null, MemberRole.USER));
        creator = creatorRepository.saveAndFlush(new Creator(creatorMember.getMemberId(), "creator-" + suffix));
        command = new QuizRewardCommand(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000_000L) + 1,
                member.getMemberId(), creator.getCreatorId(), 3, UUID.randomUUID());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ticket_quiz_reward_request WHERE member_id = ?", member.getMemberId());
        jdbcTemplate.update("DELETE FROM ticket_earn_request WHERE request_id = ?",
                command.rewardRequestId().toString());
        creatorRepository.delete(creator);
        memberRepository.deleteById(creator.getMemberId());
        memberRepository.delete(member);
    }

    @Test
    void 비교_중_같은_요청이_커밋돼도_같은_스냅샷이라_충돌로_잘못_판정하지_않고_재요청이_된다() {
        String rewardRequestId = command.rewardRequestId().toString();
        AtomicBoolean committedConcurrently = new AtomicBoolean();
        willAnswer(invocation -> {
            Object found = invocation.callRealMethod();
            if (committedConcurrently.compareAndSet(false, true)) {
                // Quiz 요청을 읽은 직후, 다른 연결에서 같은 요청을 접수해 커밋한다.
                CompletableFuture.runAsync(() -> store.insert(command)).get(30, TimeUnit.SECONDS);
            }
            return found;
        }).given(quizRewardRequestRepository).findByRewardRequestId(rewardRequestId);

        QuizRewardAdmission admission = service.admit(command);

        assertThat(committedConcurrently).isTrue();
        assertThat(admission.outcome()).isEqualTo(QuizRewardAdmission.Outcome.REPLAYED);
        assertThat(admission.status()).isEqualTo(QuizRewardRequestStatus.RECEIVED);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket_quiz_reward_request "
                + "WHERE reward_request_id = ?", Integer.class, rewardRequestId)).isEqualTo(1);
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}

package kr.co.cking.ticket.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.co.cking.mission.MissionCompletion;
import kr.co.cking.mission.MissionCompletionRepository;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.domain.TicketLedger;
import kr.co.cking.ticket.domain.TicketLedgerType;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.TicketLedgerRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;

/**
 * 완료조건(이슈 #39): mission_completion·ticket_ledger·user_ticket_balance 반영과
 * at-least-once 재전달에 대한 멱등성을 검증한다.
 *
 * <p>member_id/creator_id는 명시적으로 고정 ID를 지정해서 INSERT한다 — 로컬 개발 DB의
 * member 테이블이 AUTO_INCREMENT 없이 구성돼 있어(OfficialSnapshotServiceIntegrationTest와
 * 동일한 제약) repository.save()의 IDENTITY 채번에 의존할 수 없다.
 */
@SpringBootTest
class TicketEarnLedgerServiceIntegrationTest {

    private static final long OWNER_MEMBER_ID = 97001L;
    private static final long OTHER_OWNER_MEMBER_ID = 97003L;
    private static final long MEMBER_ID = 97002L;
    private static final long CREATOR_ID = 97101L;
    private static final long OTHER_CREATOR_ID = 97102L;
    private static final long MISSION_ID = 97201L;

    @Autowired
    private TicketEarnLedgerService ticketEarnLedgerService;

    @Autowired
    private MissionCompletionRepository missionCompletionRepository;

    @Autowired
    private TicketLedgerRepository ticketLedgerRepository;

    @Autowired
    private UserTicketBalanceRepository userTicketBalanceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                OWNER_MEMBER_ID, "크리에이터 회원", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                OTHER_OWNER_MEMBER_ID, "다른 크리에이터 회원", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                MEMBER_ID, "적립 대상 회원", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, OWNER_MEMBER_ID, "테스트 크리에이터");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                OTHER_CREATOR_ID, OTHER_OWNER_MEMBER_ID, "다른 테스트 크리에이터");
        jdbcTemplate.update("INSERT INTO mission (mission_id, creator_id, type, reward_amount) VALUES (?, ?, ?, ?)",
                MISSION_ID, CREATOR_ID, "ATTENDANCE", 1);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM ticket_ledger WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ? AND creator_id IN (?, ?)",
                MEMBER_ID, CREATOR_ID, OTHER_CREATOR_ID);
        jdbcTemplate.update("DELETE FROM mission_completion WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM mission WHERE mission_id = ?", MISSION_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id IN (?, ?)", CREATOR_ID, OTHER_CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?, ?)",
                MEMBER_ID, OWNER_MEMBER_ID, OTHER_OWNER_MEMBER_ID);
    }

    private EarnCommand command(UUID requestId, String periodKey, long amount) {
        return new EarnCommand(requestId, MEMBER_ID, CREATOR_ID, "ATTENDANCE", MISSION_ID,
                periodKey, "attendance:%d:%s".formatted(CREATOR_ID, periodKey), amount);
    }

    @Test
    void 미션완료_Ledger_Balance를_한_트랜잭션으로_반영한다() {
        UUID requestId = UUID.randomUUID();

        ticketEarnLedgerService.apply(command(requestId, "2026-09-16", 3L));

        MissionCompletion completion = missionCompletionRepository.findByRequestId(requestId.toString())
                .orElseThrow();
        assertThat(completion.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(completion.getCreatorId()).isEqualTo(CREATOR_ID);
        assertThat(completion.getMissionId()).isEqualTo(MISSION_ID);

        TicketLedger ledger = ticketLedgerRepository.findByRequestId(requestId.toString()).orElseThrow();
        assertThat(ledger.getType()).isEqualTo(TicketLedgerType.EARN);
        assertThat(ledger.getMissionCompletionId()).isEqualTo(completion.getCompletionId());
        assertThat(ledger.getEventEntryId()).isNull();
        assertThat(ledger.getDeltaAmount()).isEqualTo(3L);
        assertThat(ledger.getBalanceBefore()).isEqualTo(0L);
        assertThat(ledger.getBalanceAfter()).isEqualTo(3L);

        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID)
                .orElseThrow();
        assertThat(balance.getBalance()).isEqualTo(3L);
    }

    @Test
    void 기존_잔액에_누적해서_더한다() {
        ticketEarnLedgerService.apply(command(UUID.randomUUID(), "2026-09-15", 2L));
        ticketEarnLedgerService.apply(command(UUID.randomUUID(), "2026-09-16", 5L));

        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID)
                .orElseThrow();
        assertThat(balance.getBalance()).isEqualTo(7L);
    }

    @Test
    void 같은_requestId를_재처리해도_잔액이_중복반영되지_않는다() {
        UUID requestId = UUID.randomUUID();
        EarnCommand command = command(requestId, "2026-09-16", 4L);

        ticketEarnLedgerService.apply(command);
        ticketEarnLedgerService.apply(command); // at-least-once 재전달 시뮬레이션

        Integer completionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mission_completion WHERE member_id = ?", Integer.class, MEMBER_ID);
        Integer ledgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_ledger WHERE member_id = ?", Integer.class, MEMBER_ID);
        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID)
                .orElseThrow();

        assertThat(completionCount).isEqualTo(1);
        assertThat(ledgerCount).isEqualTo(1);
        assertThat(balance.getBalance()).isEqualTo(4L);
    }

    // PR #54 리뷰 반영: PEL 재수신·다중 Consumer 환경처럼 같은 requestId가 동시에
    // 재전달돼도 findByRequestId 확인과 INSERT 사이 경쟁 상황에서 최종적으로
    // mission_completion·ticket_ledger가 각각 1건만 생성되고 balance도 한 번만
    // 증가해야 한다. 경합에서 진 스레드는 uk_completion_request 위반으로 트랜잭션이
    // 롤백되고 예외를 던진다 — 같은 트랜잭션 안에서 즉시 복구를 시도하면 이미
    // 오염된 Hibernate 세션 때문에 AssertionFailure가 나므로(재현 확인됨), 복구는
    // 재시도(재전달)에 맡기고 여기서는 "1건만 성공하고 나머지는 멱등 위반으로
    // 실패한다"까지만 검증한다.
    @Test
    void 동시에_같은_requestId가_재전달돼도_한_번만_반영된다() throws InterruptedException {
        UUID requestId = UUID.randomUUID();
        EarnCommand command = command(requestId, "2026-09-16", 4L);

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    try {
                        ticketEarnLedgerService.apply(command);
                        return true;
                    } catch (DataAccessException e) {
                        return false;
                    }
                }));
            }

            startLatch.countDown();

            long succeeded = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isGreaterThanOrEqualTo(1L);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        } finally {
            executor.shutdown();
        }

        Integer completionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mission_completion WHERE member_id = ?", Integer.class, MEMBER_ID);
        Integer ledgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_ledger WHERE member_id = ?", Integer.class, MEMBER_ID);
        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID)
                .orElseThrow();

        assertThat(completionCount).isEqualTo(1);
        assertThat(ledgerCount).isEqualTo(1);
        assertThat(balance.getBalance()).isEqualTo(4L);
    }

    // PR #54 리뷰 반영: userId/creatorId/missionId/periodKey/amount만 비교하던 기존
    // verifySameRequest()는 missionType·missionKey만 다른 재전달을 정상 재전달로
    // 오판할 수 있었다. payload fingerprint 비교로 바뀐 뒤 이 케이스가 제대로
    // IDEMPOTENCY 위반으로 잡히는지 확인한다.
    @Test
    void 같은_requestId에_missionType만_달라도_반영하지_않는다() {
        UUID requestId = UUID.randomUUID();
        ticketEarnLedgerService.apply(command(requestId, "2026-09-16", 4L));

        EarnCommand conflicting = new EarnCommand(requestId, MEMBER_ID, CREATOR_ID, "LIKE",
                MISSION_ID, "2026-09-16", "attendance:%d:2026-09-16".formatted(CREATOR_ID), 4L);

        assertThatThrownBy(() -> ticketEarnLedgerService.apply(conflicting))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missionId가_creatorId_소속이_아니면_반영하지_않는다() {
        // MISSION_ID는 CREATOR_ID 소속인데, OTHER_CREATOR_ID로 요청이 들어온 경우
        EarnCommand command = new EarnCommand(UUID.randomUUID(), MEMBER_ID, OTHER_CREATOR_ID, "ATTENDANCE",
                MISSION_ID, "2026-09-16", "attendance:%d:2026-09-16".formatted(OTHER_CREATOR_ID), 3L);

        assertThatThrownBy(() -> ticketEarnLedgerService.apply(command))
                .isInstanceOf(IllegalStateException.class);

        assertThat(missionCompletionRepository.findByRequestId(command.requestId().toString())).isEmpty();
        assertThat(userTicketBalanceRepository.findByMemberIdAndCreatorId(MEMBER_ID, OTHER_CREATOR_ID)).isEmpty();
    }

    @Test
    void 같은_requestId에_다른_내용이_들어오면_반영하지_않는다() {
        UUID requestId = UUID.randomUUID();
        ticketEarnLedgerService.apply(command(requestId, "2026-09-16", 4L));

        EarnCommand conflicting = command(requestId, "2026-09-16", 999L);

        assertThatThrownBy(() -> ticketEarnLedgerService.apply(conflicting))
                .isInstanceOf(IllegalStateException.class);

        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID)
                .orElseThrow();
        // 최초 반영분(4)만 남아있고 충돌한 재요청(999)은 반영되지 않아야 한다.
        assertThat(balance.getBalance()).isEqualTo(4L);
    }
}

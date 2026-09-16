package kr.co.cking.ticket.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
    private static final long MEMBER_ID = 97002L;
    private static final long CREATOR_ID = 97101L;
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
                MEMBER_ID, "적립 대상 회원", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, OWNER_MEMBER_ID, "테스트 크리에이터");
        jdbcTemplate.update("INSERT INTO mission (mission_id, creator_id, type, reward_amount) VALUES (?, ?, ?, ?)",
                MISSION_ID, CREATOR_ID, "ATTENDANCE", 1);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM ticket_ledger WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ? AND creator_id = ?", MEMBER_ID, CREATOR_ID);
        jdbcTemplate.update("DELETE FROM mission_completion WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM mission WHERE mission_id = ?", MISSION_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?)", MEMBER_ID, OWNER_MEMBER_ID);
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
}

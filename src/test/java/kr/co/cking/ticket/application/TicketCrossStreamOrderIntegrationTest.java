package kr.co.cking.ticket.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.SpendCommand;
import kr.co.cking.ticket.domain.CouponType;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;

/**
 * EARN(stream:ticket-earned)과 SPEND(stream:ticket-deducted)는 스트림·컨슈머 그룹이 달라 순서가
 * 보장되지 않는다. SPEND가 EARN보다 먼저 소비돼 실패해도 아무것도 남기지 않고(롤백 → XACK 안 됨),
 * EARN 반영 뒤 같은 SPEND를 재시도하면(PEL 재전달) 정확히 한 번만 반영되는지 검증한다.
 */
@SpringBootTest
class TicketCrossStreamOrderIntegrationTest {

    private static final long OWNER_MEMBER_ID = 98001L;
    private static final long MEMBER_ID = 98002L;
    private static final long CREATOR_ID = 98101L;
    private static final long MISSION_ID = 98201L;

    @Autowired
    private TicketSpendLedgerService ticketSpendLedgerService;

    @Autowired
    private TicketEarnLedgerService ticketEarnLedgerService;

    @Autowired
    private UserTicketBalanceRepository userTicketBalanceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long eventId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                OWNER_MEMBER_ID, "크리에이터 회원", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                MEMBER_ID, "응모 회원", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, OWNER_MEMBER_ID, "테스트 크리에이터");
        jdbcTemplate.update("INSERT INTO mission (mission_id, creator_id, type, reward_amount) VALUES (?, ?, ?, ?)",
                MISSION_ID, CREATOR_ID, "ATTENDANCE", 1);
        String requestId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO event (creator_id, title, start_at, end_at, winner_count, draw_method,
                                    status, request_id, created_by)
                VALUES (?, ?, '2026-09-01 00:00:00', '2026-12-01 00:00:00', 1, 'WEIGHTED', 'OPEN', ?, ?)
                """, CREATOR_ID, "테스트 이벤트", requestId, OWNER_MEMBER_ID);
        eventId = jdbcTemplate.queryForObject(
                "SELECT event_id FROM event WHERE request_id = ?", Long.class, requestId);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM ticket_ledger WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM event_entry WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM mission_completion WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM event WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM mission WHERE mission_id = ?", MISSION_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?)", MEMBER_ID, OWNER_MEMBER_ID);
    }

    private EarnCommand earn(String periodKey, long amount) {
        return new EarnCommand(UUID.randomUUID(), MEMBER_ID, CREATOR_ID, "ATTENDANCE", MISSION_ID,
                periodKey, "attendance:%d:%s".formatted(CREATOR_ID, periodKey), amount);
    }

    private SpendCommand spend(String requestId, long ticketCount) {
        return new SpendCommand(eventId, MEMBER_ID, CREATOR_ID, requestId, ticketCount, CouponType.CREATOR);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE member_id = ?", Integer.class, MEMBER_ID);
    }

    private long dbBalance() {
        return userTicketBalanceRepository.findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID)
                .orElseThrow().getBalance();
    }

    @Test
    void SPEND가_첫_EARN보다_먼저_소비돼_Balance_행이_없어도_EARN_뒤_재시도로_반영된다() {
        SpendCommand spend = spend(UUID.randomUUID().toString(), 3L);

        assertThatThrownBy(() -> ticketSpendLedgerService.apply(spend))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("event_entry")).isZero();
        assertThat(count("ticket_ledger")).isZero();

        ticketEarnLedgerService.apply(earn("2026-09-16", 5L));
        ticketSpendLedgerService.apply(spend); // PEL 재전달

        assertThat(dbBalance()).isEqualTo(2L);
        assertThat(count("event_entry")).isEqualTo(1);
        assertThat(count("ticket_ledger")).isEqualTo(2); // EARN 1 + SPEND 1
    }

    @Test
    void SPEND가_추가_EARN보다_먼저_소비돼_잔액이_부족해도_EARN_뒤_재시도로_반영된다() {
        ticketEarnLedgerService.apply(earn("2026-09-15", 1L));
        SpendCommand spend = spend(UUID.randomUUID().toString(), 3L);

        assertThatThrownBy(() -> ticketSpendLedgerService.apply(spend))
                .isInstanceOf(RuntimeException.class); // balance >= 0 CHECK 위반
        assertThat(dbBalance()).isEqualTo(1L);
        assertThat(count("event_entry")).isZero();
        assertThat(count("ticket_ledger")).isEqualTo(1); // 첫 EARN만

        ticketEarnLedgerService.apply(earn("2026-09-16", 5L));
        ticketSpendLedgerService.apply(spend); // PEL 재전달

        assertThat(dbBalance()).isEqualTo(3L);
        assertThat(count("event_entry")).isEqualTo(1);
        assertThat(count("ticket_ledger")).isEqualTo(3); // EARN 2 + SPEND 1
    }
}

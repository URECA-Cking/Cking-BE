package kr.co.cking.ticket.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.ticket.domain.TicketLedger;
import kr.co.cking.ticket.domain.TicketLedgerType;
import kr.co.cking.ticket.repository.TicketLedgerRepository;

@SpringBootTest
class TicketCompensationServiceIntegrationTest {

    private static final long OWNER_MEMBER_ID = 97301L;
    private static final long MEMBER_ID = 97302L;
    private static final long CREATOR_ID = 97401L;

    @Autowired
    private TicketCompensationService ticketCompensationService;

    @Autowired
    private TicketLedgerRepository ticketLedgerRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                OWNER_MEMBER_ID, "크리에이터 회원", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                MEMBER_ID, "보정 대상 회원", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, OWNER_MEMBER_ID, "테스트 크리에이터");
        jdbcTemplate.update("INSERT INTO user_ticket_balance (member_id, creator_id, balance) VALUES (?, ?, ?)",
                MEMBER_ID, CREATOR_ID, 10L);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM ticket_ledger WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ? AND creator_id = ?",
                MEMBER_ID, CREATOR_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?)", MEMBER_ID, OWNER_MEMBER_ID);
        redisTemplate.delete(EntryRedisKeys.balance(CREATOR_ID, MEMBER_ID));
    }

    @Test
    void 보정_전_Redis_값을_balanceBefore로_기록하고_delta를_계산한다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, MEMBER_ID), "3");

        ticketCompensationService.resyncRedisToDb(MEMBER_ID, CREATOR_ID, "정합성 배치 불일치 확인");

        TicketLedger ledger = ticketLedgerRepository.findAll().stream()
                .filter(l -> l.getMemberId().equals(MEMBER_ID))
                .findFirst()
                .orElseThrow();
        assertThat(ledger.getType()).isEqualTo(TicketLedgerType.COMPENSATE);
        assertThat(ledger.getBalanceBefore()).isEqualTo(3L);
        assertThat(ledger.getBalanceAfter()).isEqualTo(10L);
        assertThat(ledger.getDeltaAmount()).isEqualTo(7L);
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isEqualTo("10");
    }

    @Test
    void Redis_키가_없으면_기준값_없이_DB_값으로만_동기화한다() {
        ticketCompensationService.resyncRedisToDb(MEMBER_ID, CREATOR_ID, "Redis 키 유실");

        TicketLedger ledger = ticketLedgerRepository.findAll().stream()
                .filter(l -> l.getMemberId().equals(MEMBER_ID))
                .findFirst()
                .orElseThrow();
        assertThat(ledger.getBalanceBefore()).isEqualTo(10L);
        assertThat(ledger.getBalanceAfter()).isEqualTo(10L);
        assertThat(ledger.getDeltaAmount()).isEqualTo(0L);
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isEqualTo("10");
    }
}

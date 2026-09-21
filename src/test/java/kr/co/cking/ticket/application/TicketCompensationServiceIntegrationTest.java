package kr.co.cking.ticket.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.domain.TicketErrorCode;
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
        redisTemplate.delete(TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID));
        jdbcTemplate.update("DELETE FROM dead_stream_message WHERE source_stream_id LIKE 'comp-it-%'");
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

    @Test
    void 보정이_끝나면_maintenance_lock을_해제한다() {
        ticketCompensationService.resyncRedisToDb(MEMBER_ID, CREATOR_ID, "정합성 배치 불일치 확인");

        assertThat(redisTemplate.hasKey(TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID))).isFalse();
    }

    @Test
    void 이미_보정_lock이_잡혀_있으면_거부하고_남의_lock은_지우지_않는다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, MEMBER_ID), "3");
        redisTemplate.opsForValue().set(TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID), "other-token");

        assertThatThrownBy(() -> ticketCompensationService.resyncRedisToDb(MEMBER_ID, CREATOR_ID, "사유"))
                .isInstanceOf(BusinessException.class);

        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID)))
                .isEqualTo("other-token");
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isEqualTo("3");
    }

    @Test
    void 미해결_EARN_Dead_Stream이_있으면_Redis도_Ledger도_바꾸지_않고_lock을_해제한다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(CREATOR_ID, MEMBER_ID), "13");
        jdbcTemplate.update("""
                INSERT INTO dead_stream_message (source_stream_id, stream_type, payload, retry_count, resolution_status)
                VALUES ('comp-it-1', 'EARN', ?, 3, 'UNRESOLVED')
                """, "{\"userId\":\"%d\",\"creatorId\":\"%d\"}".formatted(MEMBER_ID, CREATOR_ID));

        assertThatThrownBy(() -> ticketCompensationService.resyncRedisToDb(MEMBER_ID, CREATOR_ID, "사유"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TicketErrorCode.INVALID_STATE));

        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isEqualTo("13");
        assertThat(ticketLedgerRepository.findAll()).noneMatch(l -> l.getMemberId().equals(MEMBER_ID));
        assertThat(redisTemplate.hasKey(TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID))).isFalse();
    }
}

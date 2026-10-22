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
import kr.co.cking.ticket.application.config.CommonTicketRedisKeys;
import kr.co.cking.ticket.domain.CommonTicketLedger;
import kr.co.cking.ticket.domain.TicketErrorCode;
import kr.co.cking.ticket.domain.TicketLedgerType;
import kr.co.cking.ticket.repository.CommonTicketLedgerRepository;

/** 이슈 #256: 실제 MySQL(CHECK 제약 포함)·Redis에서 공용 수동 보정을 검증한다. */
@SpringBootTest
class CommonTicketCompensationServiceIntegrationTest {

    private static final long MEMBER_ID = 97902L;

    @Autowired
    private CommonTicketCompensationService service;

    @Autowired
    private CommonTicketLedgerRepository ledgerRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)", MEMBER_ID, "공용 보정 회원", "USER");
        jdbcTemplate.update("INSERT INTO user_common_ticket_balance (member_id, balance) VALUES (?, ?)", MEMBER_ID, 10L);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM common_ticket_ledger WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM user_common_ticket_balance WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", MEMBER_ID);
        redisTemplate.delete(CommonTicketRedisKeys.balance(MEMBER_ID));
        redisTemplate.delete(CommonTicketRedisKeys.maintenance(MEMBER_ID));
        jdbcTemplate.update("DELETE FROM dead_stream_message WHERE source_stream_id LIKE 'common-comp-it-%'");
    }

    @Test
    void COMPENSATE_Ledger를_남기고_Redis를_DB_값으로_맞추며_lock을_해제한다() {
        redisTemplate.opsForValue().set(CommonTicketRedisKeys.balance(MEMBER_ID), "3");

        service.resyncRedisToDb(MEMBER_ID, "정합성 배치 불일치 확인");

        CommonTicketLedger ledger = ledgerRepository.findAll().stream()
                .filter(l -> l.getMemberId().equals(MEMBER_ID)).findFirst().orElseThrow();
        assertThat(ledger.getType()).isEqualTo(TicketLedgerType.COMPENSATE);
        assertThat(ledger.getBalanceBefore()).isEqualTo(3L);
        assertThat(ledger.getBalanceAfter()).isEqualTo(10L);
        assertThat(ledger.getDeltaAmount()).isEqualTo(7L);
        assertThat(redisTemplate.opsForValue().get(CommonTicketRedisKeys.balance(MEMBER_ID))).isEqualTo("10");
        assertThat(redisTemplate.hasKey(CommonTicketRedisKeys.maintenance(MEMBER_ID))).isFalse();
    }

    @Test
    void 미해결_공용_Dead_Stream이_있으면_거부하고_Redis와_Ledger를_건드리지_않는다() {
        redisTemplate.opsForValue().set(CommonTicketRedisKeys.balance(MEMBER_ID), "3");
        jdbcTemplate.update("""
                INSERT INTO dead_stream_message (source_stream_id, stream_type, payload, retry_count, resolution_status)
                VALUES ('common-comp-it-1', 'COMMON_EARN', ?, 3, 'UNRESOLVED')
                """, "{\"userId\":\"%d\"}".formatted(MEMBER_ID));

        assertThatThrownBy(() -> service.resyncRedisToDb(MEMBER_ID, "사유"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TicketErrorCode.INVALID_STATE);
        assertThat(redisTemplate.opsForValue().get(CommonTicketRedisKeys.balance(MEMBER_ID))).isEqualTo("3");
        assertThat(ledgerRepository.findAll()).noneMatch(l -> l.getMemberId().equals(MEMBER_ID));
        assertThat(redisTemplate.hasKey(CommonTicketRedisKeys.maintenance(MEMBER_ID))).isFalse();
    }
}

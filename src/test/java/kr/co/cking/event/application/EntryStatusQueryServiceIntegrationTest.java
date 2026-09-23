package kr.co.cking.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.event.application.dto.EntryStatusResponse;
import kr.co.cking.event.domain.EventErrorCode;

/**
 * 실시간 응모 현황 조회(FR-P2-045~051). OPEN/CLOSING이고 집계 키가 있으면 Redis
 * (realtime=true), 그 외에는 DB {@code event_entry} 집계(realtime=false)로 응답한다.
 */
@SpringBootTest
class EntryStatusQueryServiceIntegrationTest {

    private static final long OWNER_MEMBER_ID = 90301L;
    private static final long MEMBER_A_ID = 90302L;
    private static final long MEMBER_B_ID = 90303L;
    private static final long CREATOR_ID = 90304L;

    @Autowired
    private EntryStatusQueryService entryStatusQueryService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long eventId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                OWNER_MEMBER_ID, "현황조회 크리에이터 회원", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                MEMBER_A_ID, "현황조회 사용자A", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                MEMBER_B_ID, "현황조회 사용자B", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, OWNER_MEMBER_ID, "현황조회 크리에이터");
        eventId = insertEvent("OPEN");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
        redisTemplate.delete(List.of(EntryRedisKeys.entryTotal(eventId), EntryRedisKeys.entrants(eventId)));
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM event_entry WHERE event_id IN (SELECT event_id FROM event WHERE creator_id = ?)",
                CREATOR_ID);
        jdbcTemplate.update("DELETE FROM event WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?, ?)",
                OWNER_MEMBER_ID, MEMBER_A_ID, MEMBER_B_ID);
    }

    private long insertEvent(String status) {
        String requestId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO event (creator_id, title, start_at, end_at, winner_count, draw_method,
                                    status, request_id, created_by)
                VALUES (?, ?, '2026-09-01 00:00:00', '2099-01-01 00:00:00', 1, 'WEIGHTED', ?, ?, ?)
                """, CREATOR_ID, "현황조회 이벤트", status, requestId, OWNER_MEMBER_ID);
        return jdbcTemplate.queryForObject("SELECT event_id FROM event WHERE request_id = ?", Long.class, requestId);
    }

    private void insertEntry(long id, long memberId, long usedTicketCount) {
        jdbcTemplate.update("""
                INSERT INTO event_entry (member_id, event_id, request_id, used_ticket_count, applied_at)
                VALUES (?, ?, ?, ?, NOW(6))
                """, memberId, id, UUID.randomUUID().toString(), usedTicketCount);
    }

    @Test
    void 존재하지_않는_이벤트는_EVENT_NOT_FOUND다() {
        assertThatThrownBy(() -> entryStatusQueryService.getStatus(999_999_999L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(EventErrorCode.EVENT_NOT_FOUND));
    }

    @Test
    void userId에_해당하는_회원이_없으면_RESOURCE_NOT_FOUND다() {
        assertThatThrownBy(() -> entryStatusQueryService.getStatus(eventId, 999_999_998L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void OPEN이고_집계_키가_있으면_Redis_집계를_realtime으로_반환한다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.entryTotal(eventId), "7");
        redisTemplate.opsForHash().put(EntryRedisKeys.entrants(eventId), String.valueOf(MEMBER_A_ID), "7");
        // DB에는 다른 값을 넣어 실제로 Redis를 읽는지 구분한다.
        insertEntry(eventId, MEMBER_A_ID, 1L);

        EntryStatusResponse response = entryStatusQueryService.getStatus(eventId, MEMBER_A_ID);

        assertThat(response.realtime()).isTrue();
        assertThat(response.totalTicketCount()).isEqualTo(7L);
        assertThat(response.participantCount()).isEqualTo(1L);
        assertThat(response.myTicketCount()).isEqualTo(7L);
    }

    @Test
    void 집계_키가_없으면_OPEN이어도_DB_집계로_대체한다() {
        insertEntry(eventId, MEMBER_A_ID, 3L);
        insertEntry(eventId, MEMBER_B_ID, 4L);

        EntryStatusResponse response = entryStatusQueryService.getStatus(eventId, MEMBER_A_ID);

        assertThat(response.realtime()).isFalse();
        assertThat(response.totalTicketCount()).isEqualTo(7L);
        assertThat(response.participantCount()).isEqualTo(2L);
        assertThat(response.myTicketCount()).isEqualTo(3L);
    }

    @Test
    void userId를_전달하지_않으면_myTicketCount는_null이다() {
        insertEntry(eventId, MEMBER_A_ID, 3L);

        EntryStatusResponse response = entryStatusQueryService.getStatus(eventId, null);

        assertThat(response.myTicketCount()).isNull();
    }

    @Test
    void CLOSED_이벤트는_집계_키가_있어도_DB_집계를_사용한다() {
        long closedEventId = insertEvent("CLOSED");
        redisTemplate.opsForValue().set(EntryRedisKeys.entryTotal(closedEventId), "999");
        insertEntry(closedEventId, MEMBER_A_ID, 3L);

        EntryStatusResponse response = entryStatusQueryService.getStatus(closedEventId, null);

        assertThat(response.realtime()).isFalse();
        assertThat(response.totalTicketCount()).isEqualTo(3L);

        redisTemplate.delete(EntryRedisKeys.entryTotal(closedEventId));
    }

    @Test
    void 응모가_없으면_0으로_응답한다() {
        EntryStatusResponse response = entryStatusQueryService.getStatus(eventId, MEMBER_A_ID);

        assertThat(response.realtime()).isFalse();
        assertThat(response.totalTicketCount()).isZero();
        assertThat(response.participantCount()).isZero();
        assertThat(response.myTicketCount()).isZero();
    }
}

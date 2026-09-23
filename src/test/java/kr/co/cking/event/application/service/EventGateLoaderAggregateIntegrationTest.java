package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.event.domain.Event;

/**
 * 실시간 응모 현황(FR-P2-045~050) 집계 키를 Gate 최초 적재 시 DB {@code event_entry}
 * 집계값으로 초기화하는지 검증한다. 기본 Gate 동작(재적재 안전성 등)은
 * {@link EventGateLoaderIntegrationTest}가 다룬다.
 */
@SpringBootTest
class EventGateLoaderAggregateIntegrationTest {

    private static final long OWNER_MEMBER_ID = 90201L;
    private static final long MEMBER_A_ID = 90202L;
    private static final long MEMBER_B_ID = 90203L;
    private static final long CREATOR_ID = 90204L;

    @Autowired
    private EventGateLoader eventGateLoader;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long eventId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                OWNER_MEMBER_ID, "Gate 집계 크리에이터 회원", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                MEMBER_A_ID, "Gate 집계 사용자A", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                MEMBER_B_ID, "Gate 집계 사용자B", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, OWNER_MEMBER_ID, "Gate 집계 크리에이터");

        String requestId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO event (creator_id, title, start_at, end_at, winner_count, draw_method,
                                    status, request_id, created_by)
                VALUES (?, ?, '2026-09-01 00:00:00', '2099-01-01 00:00:00', 1, 'WEIGHTED', 'OPEN', ?, ?)
                """, CREATOR_ID, "Gate 집계 이벤트", requestId, OWNER_MEMBER_ID);
        eventId = jdbcTemplate.queryForObject(
                "SELECT event_id FROM event WHERE request_id = ?", Long.class, requestId);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
        redisTemplate.delete(List.of(
                EntryRedisKeys.status(eventId), EntryRedisKeys.endAt(eventId), EntryRedisKeys.cutoff(eventId),
                EntryRedisKeys.entryTotal(eventId), EntryRedisKeys.entrants(eventId)));
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM event_entry WHERE event_id IN (SELECT event_id FROM event WHERE creator_id = ?)",
                CREATOR_ID);
        jdbcTemplate.update("DELETE FROM event WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?, ?)",
                OWNER_MEMBER_ID, MEMBER_A_ID, MEMBER_B_ID);
    }

    private void insertEntry(long memberId, long usedTicketCount) {
        jdbcTemplate.update("""
                INSERT INTO event_entry (member_id, event_id, request_id, used_ticket_count, applied_at)
                VALUES (?, ?, ?, ?, NOW(6))
                """, memberId, eventId, UUID.randomUUID().toString(), usedTicketCount);
    }

    private Event event() {
        Event event = mock(Event.class);
        when(event.getEventId()).thenReturn(eventId);
        when(event.getEndAt()).thenReturn(Instant.parse("2099-01-01T00:00:00Z"));
        return event;
    }

    @Test
    void Gate가_처음_열릴_때_DB_집계로_실시간_응모_현황_키를_초기화한다() {
        insertEntry(MEMBER_A_ID, 3L);
        insertEntry(MEMBER_A_ID, 2L);
        insertEntry(MEMBER_B_ID, 4L);

        eventGateLoader.load(event());

        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.entryTotal(eventId))).isEqualTo("9");
        assertThat(redisTemplate.opsForHash().size(EntryRedisKeys.entrants(eventId))).isEqualTo(2L);
        assertThat(redisTemplate.opsForHash().get(EntryRedisKeys.entrants(eventId), String.valueOf(MEMBER_A_ID)))
                .isEqualTo("5");
        assertThat(redisTemplate.opsForHash().get(EntryRedisKeys.entrants(eventId), String.valueOf(MEMBER_B_ID)))
                .isEqualTo("4");
    }

    @Test
    void 기존_응모가_없으면_0으로_초기화한다() {
        eventGateLoader.load(event());

        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.entryTotal(eventId))).isEqualTo("0");
        assertThat(redisTemplate.opsForHash().size(EntryRedisKeys.entrants(eventId))).isZero();
    }

    @Test
    void Gate가_이미_열려_있으면_집계를_다시_초기화하지_않는다() {
        insertEntry(MEMBER_A_ID, 3L);
        redisTemplate.opsForValue().set(EntryRedisKeys.status(eventId), "OPEN");
        redisTemplate.opsForValue().set(EntryRedisKeys.endAt(eventId), "1");

        eventGateLoader.load(event());

        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.entryTotal(eventId))).isNull();
        assertThat(redisTemplate.hasKey(EntryRedisKeys.entrants(eventId))).isFalse();
    }
}

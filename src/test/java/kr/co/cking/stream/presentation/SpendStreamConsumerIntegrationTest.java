package kr.co.cking.stream.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.co.cking.event.domain.EventEntry;
import kr.co.cking.event.repository.EventEntryRepository;

/**
 * 완료조건(이슈 #62): cg:ticket-history 그룹으로 stream:ticket-deducted를 실제로
 * 소비해서 DB에 반영하고, DB 반영 후에만 XACK하는지, barrier 메시지는 DB 반영 없이
 * ACK하는지 end-to-end로 검증한다. 운영 stream 키와 겹치지 않도록 테스트 전용
 * 키/그룹으로 오버라이드한다.
 */
@SpringBootTest(properties = {
        "cking.entry.stream-key=stream:ticket-deducted:consumer-test",
        "cking.entry.history-consumer-group=cg:ticket-history:consumer-test"
})
class SpendStreamConsumerIntegrationTest {

    private static final String STREAM_KEY = "stream:ticket-deducted:consumer-test";
    private static final String CONSUMER_GROUP = "cg:ticket-history:consumer-test";
    private static final long AWAIT_TIMEOUT_MILLIS = 8000L;

    private static final long OWNER_MEMBER_ID = 95001L;
    private static final long MEMBER_ID = 95002L;
    private static final long CREATOR_ID = 95101L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EventEntryRepository eventEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long eventId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                OWNER_MEMBER_ID, "크리에이터 회원", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                MEMBER_ID, "응모 대상 회원", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, OWNER_MEMBER_ID, "스트림 테스트 크리에이터");

        String requestId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO event (creator_id, title, start_at, end_at, winner_count, draw_method,
                                    status, request_id, created_by)
                VALUES (?, ?, '2026-09-01 00:00:00', '2026-12-01 00:00:00', 1, 'WEIGHTED', 'OPEN', ?, ?)
                """, CREATOR_ID, "스트림 테스트 이벤트", requestId, OWNER_MEMBER_ID);
        eventId = jdbcTemplate.queryForObject(
                "SELECT event_id FROM event WHERE request_id = ?", Long.class, requestId);

        jdbcTemplate.update(
                "INSERT INTO user_ticket_balance (member_id, creator_id, balance, updated_at) VALUES (?, ?, ?, NOW(6))",
                MEMBER_ID, CREATOR_ID, 100L);
    }

    @AfterEach
    void tearDown() {
        // 스트림 키 자체를 지우면 그 안의 Consumer Group도 함께 사라져 이후 테스트가
        // NOGROUP으로 깨진다(그룹은 컨텍스트 기동 시 한 번만 생성됨) — 키는 남기고
        // DB 행만 정리한다.
        cleanUp();
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM ticket_ledger WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM event_entry WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ? AND creator_id = ?", MEMBER_ID, CREATOR_ID);
        jdbcTemplate.update("DELETE FROM event WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?)", MEMBER_ID, OWNER_MEMBER_ID);
    }

    @Test
    void 메시지를_소비해서_DB에_반영하고_XACK한다() {
        String requestId = UUID.randomUUID().toString();

        Map<String, String> fields = Map.of(
                "eventId", String.valueOf(eventId),
                "userId", String.valueOf(MEMBER_ID),
                "creatorId", String.valueOf(CREATOR_ID),
                "requestId", requestId,
                "ticketCount", "5"
        );

        redisTemplate.opsForStream().add(STREAM_KEY, fields);

        EventEntry entry = awaitEntry(requestId);

        assertThat(entry.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(entry.getEventId()).isEqualTo(eventId);
        assertThat(entry.getUsedTicketCount()).isEqualTo(5L);

        // DB 반영 후에만 XACK하므로, 반영이 끝난 시점엔 PEL이 비어 있어야 한다.
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(STREAM_KEY, CONSUMER_GROUP, Range.unbounded(), 10);
        assertThat(pending.isEmpty()).isTrue();
    }

    @Test
    void barrier_메시지는_DB_반영_없이_ACK한다() {
        Map<String, String> barrierFields = Map.of(
                "type", "EVENT_ENTRY_CLOSED",
                "eventId", String.valueOf(eventId)
        );

        redisTemplate.opsForStream().add(STREAM_KEY, barrierFields);

        awaitPelEmpty();

        Integer entryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_entry WHERE event_id = ?", Integer.class, eventId);
        assertThat(entryCount).isZero();
    }

    private EventEntry awaitEntry(String requestId) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            Optional<EventEntry> found = eventEntryRepository.findByRequestId(requestId);

            if (found.isPresent()) {
                return found.get();
            }

            sleep();
        }

        throw new AssertionError("SPEND Stream 메시지가 제한 시간 내에 반영되지 않았습니다. requestId=" + requestId);
    }

    private void awaitPelEmpty() {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(STREAM_KEY, CONSUMER_GROUP, Range.unbounded(), 10);

            if (pending != null && pending.isEmpty()) {
                return;
            }

            sleep();
        }

        throw new AssertionError("barrier 메시지가 제한 시간 내에 ACK되지 않았습니다.");
    }

    private void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

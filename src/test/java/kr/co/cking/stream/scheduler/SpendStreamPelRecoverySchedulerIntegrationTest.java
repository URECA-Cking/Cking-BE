package kr.co.cking.stream.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.co.cking.event.repository.EventEntryRepository;
import kr.co.cking.stream.domain.DeadStreamMessage;
import kr.co.cking.stream.domain.DeadStreamType;
import kr.co.cking.stream.presentation.SpendStreamListener;
import kr.co.cking.stream.repository.DeadStreamMessageRepository;
import kr.co.cking.ticket.application.TicketSpendLedgerService;
import kr.co.cking.ticket.application.dto.SpendCommand;
import tools.jackson.databind.ObjectMapper;

/**
 * 완료조건(Codex 리뷰 반영, 이슈 #62 확장): PEL에 남은 SPEND 메시지를 XCLAIM으로
 * 회수해 재처리하고, 최대 재시도를 넘긴 메시지는 dead_stream_message로 이동한다.
 * EarnStreamPelRecoverySchedulerIntegrationTest(이슈 #43)와 동일한 패턴.
 *
 * <p>member/creator/event는 미리 넣어 두지만 user_ticket_balance는 일부러 넣지
 * 않아, 처음 소비 시 "차감 대상 Balance가 없습니다" 예외로 DB 반영이 실패해
 * 메시지가 PEL에 남도록 유도한다. balance를 나중에 넣고 회수를 재시도하면
 * 성공하고, 끝까지 없으면 Dead Stream으로 이동한다.
 */
@SpringBootTest(properties = {
        "cking.entry.stream-key=stream:ticket-deducted:pel-test",
        "cking.entry.history-consumer-group=cg:ticket-history:pel-test",
        "cking.entry.spend-pel-min-idle-ms=100",
        "cking.entry.spend-pel-max-retry=1",
        "cking.scheduling.enabled=false"
})
class SpendStreamPelRecoverySchedulerIntegrationTest {

    private static final String STREAM_KEY = "stream:ticket-deducted:pel-test";
    private static final String CONSUMER_GROUP = "cg:ticket-history:pel-test";
    private static final String REDELIVERY_STREAM_KEY = "stream:ticket-deducted:pel-redelivery-test";
    private static final String REDELIVERY_CONSUMER_GROUP = "cg:ticket-history:pel-redelivery-test";
    private static final long AWAIT_TIMEOUT_MILLIS = 8000L;

    private static final long OWNER_MEMBER_ID = 94001L;
    private static final long MEMBER_ID = 94002L;
    private static final long CREATOR_ID = 94101L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private SpendStreamPelRecoveryScheduler scheduler;

    @Autowired
    private TicketSpendLedgerService ticketSpendLedgerService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DeadStreamMessageRepository deadStreamMessageRepository;

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
                CREATOR_ID, OWNER_MEMBER_ID, "PEL 테스트 크리에이터");

        String requestId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO event (creator_id, title, start_at, end_at, winner_count, draw_method,
                                    status, request_id, created_by)
                VALUES (?, ?, '2026-09-01 00:00:00', '2026-12-01 00:00:00', 1, 'WEIGHTED', 'OPEN', ?, ?)
                """, CREATOR_ID, "PEL 테스트 이벤트", requestId, OWNER_MEMBER_ID);
        eventId = jdbcTemplate.queryForObject(
                "SELECT event_id FROM event WHERE request_id = ?", Long.class, requestId);
        // user_ticket_balance는 의도적으로 넣지 않는다(첫 소비를 실패시키기 위함).
    }

    @AfterEach
    void tearDown() {
        // stream 키 자체를 지우면 consumer group도 함께 사라져 다음 테스트가
        // NOGROUP 에러를 맞는다(그룹은 컨텍스트 시작 시 한 번만 생성됨) — entry만 비운다.
        redisTemplate.opsForStream().trim(STREAM_KEY, 0);
        cleanUp();
    }

    private void cleanUp() {
        redisTemplate.delete(REDELIVERY_STREAM_KEY);
        deadStreamMessageRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM ticket_ledger WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM event_entry WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ? AND creator_id = ?", MEMBER_ID, CREATOR_ID);
        jdbcTemplate.update("DELETE FROM event WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?)", MEMBER_ID, OWNER_MEMBER_ID);
    }

    private Map<String, String> spendFields(String requestId) {
        return Map.of(
                "eventId", String.valueOf(eventId),
                "userId", String.valueOf(MEMBER_ID),
                "creatorId", String.valueOf(CREATOR_ID),
                "requestId", requestId,
                "ticketCount", "5"
        );
    }

    @Test
    void PEL에_남은_메시지를_XCLAIM으로_회수해서_재처리한다() throws InterruptedException {
        String requestId = UUID.randomUUID().toString();

        redisTemplate.opsForStream().add(STREAM_KEY, spendFields(requestId));

        // Balance가 아직 없어 첫 소비는 실패하고 메시지가 PEL에 남는다.
        awaitPending(1);
        assertThat(eventEntryRepository.findByRequestId(requestId)).isEmpty();

        jdbcTemplate.update(
                "INSERT INTO user_ticket_balance (member_id, creator_id, balance, updated_at) VALUES (?, ?, ?, NOW(6))",
                MEMBER_ID, CREATOR_ID, 100L);
        Thread.sleep(150); // spend-pel-min-idle-ms(100ms)보다 충분히 대기

        scheduler.recoverPending();

        awaitEntry(requestId);
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(STREAM_KEY, CONSUMER_GROUP, Range.unbounded(), 10);
        assertThat(pending.isEmpty()).isTrue();
    }

    @Test
    void DB_반영_후_ACK되지_않은_메시지를_재처리해도_중복_차감되지_않는다() throws InterruptedException {
        String requestId = UUID.randomUUID().toString();
        Map<String, String> fields = spendFields(requestId);
        jdbcTemplate.update(
                "INSERT INTO user_ticket_balance (member_id, creator_id, balance, updated_at) VALUES (?, ?, ?, NOW(6))",
                MEMBER_ID, CREATOR_ID, 100L);

        redisTemplate.opsForStream().add(REDELIVERY_STREAM_KEY, fields);
        redisTemplate.opsForStream().createGroup(
                REDELIVERY_STREAM_KEY, ReadOffset.from("0"), REDELIVERY_CONSUMER_GROUP);
        List<MapRecord<String, Object, Object>> delivered = redisTemplate.opsForStream().read(
                Consumer.from(REDELIVERY_CONSUMER_GROUP, "spend-before-ack-failure"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(REDELIVERY_STREAM_KEY, ReadOffset.lastConsumed())
        );
        assertThat(delivered).hasSize(1);

        ticketSpendLedgerService.apply(SpendCommand.fromStreamFields(fields));

        SpendStreamListener redeliveryListener = new SpendStreamListener(
                ticketSpendLedgerService, redisTemplate, REDELIVERY_STREAM_KEY, REDELIVERY_CONSUMER_GROUP);
        SpendStreamPelRecoveryScheduler redeliveryScheduler = new SpendStreamPelRecoveryScheduler(
                redisTemplate, redeliveryListener, deadStreamMessageRepository, objectMapper,
                REDELIVERY_STREAM_KEY, REDELIVERY_CONSUMER_GROUP, 100L, 5L);
        Thread.sleep(150);
        redeliveryScheduler.recoverPending();

        Integer entryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_entry WHERE request_id = ?", Integer.class, requestId);
        Integer ledgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_ledger WHERE request_id = ?", Integer.class, requestId);
        Long balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM user_ticket_balance WHERE member_id = ? AND creator_id = ?",
                Long.class, MEMBER_ID, CREATOR_ID);
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(REDELIVERY_STREAM_KEY, REDELIVERY_CONSUMER_GROUP, Range.unbounded(), 10);

        assertThat(entryCount).isEqualTo(1);
        assertThat(ledgerCount).isEqualTo(1);
        assertThat(balance).isEqualTo(95L);
        assertThat(pending.isEmpty()).isTrue();
    }

    @Test
    void 최대_재시도를_넘기면_Dead_Stream으로_이동하고_PEL에서_사라진다() throws InterruptedException {
        String requestId = UUID.randomUUID().toString();

        String recordId = redisTemplate.opsForStream().add(STREAM_KEY, spendFields(requestId)).getValue();

        // 1차 소비 실패(Balance 없음) → PEL 적재.
        awaitPending(1);

        // 회수 1회차: 재시도지만 Balance가 여전히 없어 다시 실패 → PEL에 남고 deliveryCount 증가.
        Thread.sleep(150);
        scheduler.recoverPending();
        Thread.sleep(150);

        // 회수 2회차: spend-pel-max-retry=1을 넘겨 Dead Stream으로 이동.
        scheduler.recoverPending();

        Optional<DeadStreamMessage> deadMessage =
                deadStreamMessageRepository.findBySourceStreamIdAndStreamType(recordId, DeadStreamType.SPEND);
        assertThat(deadMessage).isPresent();
        assertThat(deadMessage.get().getRequestId()).isEqualTo(requestId);
        assertThat(deadMessage.get().getEventId()).isEqualTo(eventId);
        assertThat(deadMessage.get().isUnresolved()).isTrue();

        PendingMessages pending = redisTemplate.opsForStream()
                .pending(STREAM_KEY, CONSUMER_GROUP, Range.unbounded(), 10);
        assertThat(pending.isEmpty()).isTrue();
        assertThat(eventEntryRepository.findByRequestId(requestId)).isEmpty();
    }

    // dead_stream_message.member_id는 FK라, 존재하지 않는 userId로 이관 자체가 실패할 수 있다.
    // 그 실패가 같은 배치의 나머지 메시지 처리까지 막으면 안 된다.
    @Test
    void Dead_Stream_이관에_실패한_메시지가_있어도_같은_배치의_나머지_메시지는_계속_이관된다() throws InterruptedException {
        long unknownMemberId = 999999912L; // member 테이블에 존재하지 않음 - dead_stream_message INSERT가 FK로 실패한다.
        String failingRequestId = UUID.randomUUID().toString();
        String recoverableRequestId = UUID.randomUUID().toString();

        Map<String, String> failingFields = Map.of(
                "eventId", String.valueOf(eventId),
                "userId", String.valueOf(unknownMemberId),
                "creatorId", String.valueOf(CREATOR_ID),
                "requestId", failingRequestId,
                "ticketCount", "5"
        );
        redisTemplate.opsForStream().add(STREAM_KEY, failingFields);
        String recoverableRecordId = redisTemplate.opsForStream()
                .add(STREAM_KEY, spendFields(recoverableRequestId)).getValue();
        awaitPending(2);

        // 회수 1회차: 둘 다 여전히 실패(Balance 없음) → PEL에 남고 deliveryCount 증가.
        Thread.sleep(150);
        scheduler.recoverPending();
        Thread.sleep(150);

        // 회수 2회차: 둘 다 한도를 넘겨 이관을 시도한다. unknownMemberId 쪽은 FK 위반으로
        // 이관 자체가 실패하지만, 예외를 삼키고 계속 진행해 MEMBER_ID 쪽은 정상 이관돼야 한다.
        scheduler.recoverPending();

        assertThat(deadStreamMessageRepository.findBySourceStreamIdAndStreamType(
                recoverableRecordId, DeadStreamType.SPEND)).isPresent();

        // 실패한 메시지는 이관되지 못한 채 PEL에 남는다(알려진 제약) - 그래도 다른 메시지를
        // 막지는 않았다는 게 이 테스트의 핵심이다.
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(STREAM_KEY, CONSUMER_GROUP, Range.unbounded(), 10);
        assertThat(pending.size()).isEqualTo(1);
    }

    private void awaitPending(int expectedCount) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(STREAM_KEY, CONSUMER_GROUP, Range.unbounded(), 10);

            if (pending.size() >= expectedCount) {
                return;
            }

            sleep(100);
        }

        throw new AssertionError("메시지가 제한 시간 내에 PEL에 쌓이지 않았습니다.");
    }

    private void awaitEntry(String requestId) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            if (eventEntryRepository.findByRequestId(requestId).isPresent()) {
                return;
            }

            sleep(100);
        }

        throw new AssertionError("SPEND Stream 메시지가 제한 시간 내에 반영되지 않았습니다. requestId=" + requestId);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

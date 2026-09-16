package kr.co.cking.snapshot.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.DrawSnapshot;
import kr.co.cking.snapshot.domain.DrawSnapshotCandidate;
import kr.co.cking.snapshot.repository.DrawSnapshotCandidateRepository;
import kr.co.cking.snapshot.repository.DrawSnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class OfficialSnapshotServiceIntegrationTest {

    private static final long OWNER_MEMBER_ID = 91001L;
    private static final long FIRST_MEMBER_ID = 91002L;
    private static final long SECOND_MEMBER_ID = 91003L;
    private static final long CREATOR_ID = 92001L;
    private static final long EVENT_ID = 93001L;

    @Autowired
    private OfficialSnapshotService service;

    @Autowired
    private DrawSnapshotRepository snapshotRepository;

    @Autowired
    private DrawSnapshotCandidateRepository candidateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        cleanUp();
        insertMember(OWNER_MEMBER_ID, "운영자");
        insertMember(FIRST_MEMBER_ID, "첫 번째 후보");
        insertMember(SECOND_MEMBER_ID, "두 번째 후보");
        jdbcTemplate.update("""
                INSERT INTO creator (creator_id, member_id, name)
                VALUES (?, ?, ?)
                """, CREATOR_ID, OWNER_MEMBER_ID, "테스트 크리에이터");
        jdbcTemplate.update("""
                INSERT INTO event (
                    event_id, creator_id, title, start_at, end_at, winner_count,
                    draw_method, status, closed_at, request_id, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                EVENT_ID,
                CREATOR_ID,
                "Snapshot 통합 테스트",
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 15, 0, 0),
                2,
                "WEIGHTED",
                "CLOSED",
                LocalDateTime.of(2026, 9, 15, 0, 0),
                "00000000-0000-0000-0000-000000000010",
                OWNER_MEMBER_ID
        );
        insertEntry(94001L, FIRST_MEMBER_ID, "00000000-0000-0000-0000-000000000001", 3L);
        insertEntry(94002L, FIRST_MEMBER_ID, "00000000-0000-0000-0000-000000000002", 7L);
        insertEntry(94003L, SECOND_MEMBER_ID, "00000000-0000-0000-0000-000000000003", 5L);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 응모를_회원별로_집계하여_공식_Snapshot을_한_번만_저장한다() {
        OfficialSnapshotResult first = service.createIfAbsent(EVENT_ID);
        OfficialSnapshotResult replay = service.createIfAbsent(EVENT_ID);

        assertThat(replay.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(snapshotRepository.countByEventId(EVENT_ID)).isEqualTo(1L);
        assertThat(first.candidateCount()).isEqualTo(2);
        assertThat(first.totalTicketCount()).isEqualTo(15L);

        List<CandidateRow> candidates = jdbcTemplate.query("""
                        SELECT candidate.member_id, candidate.ticket_count
                        FROM draw_snapshot_candidate candidate
                        JOIN draw_snapshot snapshot ON snapshot.id = candidate.snapshot_id
                        WHERE snapshot.event_id = ?
                        ORDER BY candidate.member_id ASC
                        """,
                (resultSet, rowNumber) -> new CandidateRow(
                        resultSet.getLong("member_id"),
                        resultSet.getLong("ticket_count")
                ),
                EVENT_ID
        );
        assertThat(candidates)
                .extracting(CandidateRow::memberId, CandidateRow::ticketCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(FIRST_MEMBER_ID, 10L),
                        org.assertj.core.groups.Tuple.tuple(SECOND_MEMBER_ID, 5L)
                );
        assertThat(first.snapshotHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void 동시에_생성해도_공식_Snapshot은_하나만_존재한다() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<OfficialSnapshotResult> first = executor.submit(() -> createAfterSignal(ready, start));
            Future<OfficialSnapshotResult> second = executor.submit(() -> createAfterSignal(ready, start));

            ready.await();
            start.countDown();

            assertThat(first.get().snapshotId()).isEqualTo(second.get().snapshotId());
        }
        assertThat(snapshotRepository.countByEventId(EVENT_ID)).isEqualTo(1L);
    }

    @Test
    void Candidate_Repository는_memberId_ASC로_조회한다() {
        OfficialSnapshotResult result = service.createIfAbsent(EVENT_ID);

        List<DrawSnapshotCandidate> candidates = candidateRepository
                .findAllBySnapshot_IdOrderByMemberIdAsc(result.snapshotId());

        assertThat(candidates)
                .extracting(DrawSnapshotCandidate::getMemberId, DrawSnapshotCandidate::getTicketCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(FIRST_MEMBER_ID, 10L),
                        org.assertj.core.groups.Tuple.tuple(SECOND_MEMBER_ID, 5L)
                );
    }

    @Test
    void Snapshot_저장에_실패하면_새_Candidate도_남지_않는다() {
        OfficialSnapshotResult existing = service.createIfAbsent(EVENT_ID);
        DrawSnapshot duplicate = snapshot(
                EVENT_ID,
                List.of(new CandidateValue(FIRST_MEMBER_ID, 99L))
        );

        assertThatThrownBy(() -> persist(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(snapshotRepository.countByEventId(EVENT_ID)).isEqualTo(1L);
        assertThat(candidateRepository.countBySnapshot_Id(existing.snapshotId())).isEqualTo(2L);
        assertThat(candidateCount(EVENT_ID, 99L)).isZero();
    }

    @Test
    void Candidate_저장에_실패하면_Snapshot도_rollback된다() {
        DrawSnapshot snapshot = snapshot(
                EVENT_ID,
                List.of(new CandidateValue(999_999_999L, 1L))
        );

        assertThatThrownBy(() -> persist(snapshot))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(snapshotRepository.countByEventId(EVENT_ID)).isZero();
        assertThat(candidateCount(EVENT_ID, null)).isZero();
    }

    private void insertMember(long memberId, String name) {
        jdbcTemplate.update("""
                INSERT INTO member (member_id, name, role)
                VALUES (?, ?, ?)
                """, memberId, name, "USER");
    }

    private void insertEntry(long entryId, long memberId, String requestId, long ticketCount) {
        jdbcTemplate.update("""
                INSERT INTO event_entry (
                    entry_id, member_id, event_id, request_id, used_ticket_count, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                entryId,
                memberId,
                EVENT_ID,
                requestId,
                ticketCount,
                LocalDateTime.of(2026, 9, 14, 0, 0)
        );
    }

    private OfficialSnapshotResult createAfterSignal(CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        return service.createIfAbsent(EVENT_ID);
    }

    private DrawSnapshot snapshot(long eventId, List<CandidateValue> candidates) {
        String hash = new SnapshotHashGenerator().generate(new SnapshotHashInput(
                eventId,
                2,
                "WEIGHTED",
                "WEIGHTED_V1",
                candidates
        )).value();
        return DrawSnapshot.create(
                eventId,
                2,
                "WEIGHTED",
                "WEIGHTED_V1",
                hash,
                candidates
        );
    }

    private void persist(DrawSnapshot snapshot) {
        transactionTemplate.executeWithoutResult(status -> snapshotRepository.saveAndFlush(snapshot));
    }

    private long candidateCount(long eventId, Long ticketCount) {
        String ticketCondition = ticketCount == null ? "" : " AND candidate.ticket_count = ?";
        String sql = """
                SELECT COUNT(*)
                FROM draw_snapshot_candidate candidate
                JOIN draw_snapshot snapshot ON snapshot.id = candidate.snapshot_id
                WHERE snapshot.event_id = ?
                """ + ticketCondition;
        Long count = ticketCount == null
                ? jdbcTemplate.queryForObject(sql, Long.class, eventId)
                : jdbcTemplate.queryForObject(sql, Long.class, eventId, ticketCount);
        return count == null ? 0L : count;
    }

    private void cleanUp() {
        jdbcTemplate.update("""
                DELETE candidate
                FROM draw_snapshot_candidate candidate
                JOIN draw_snapshot snapshot ON snapshot.id = candidate.snapshot_id
                WHERE snapshot.event_id = ?
                """, EVENT_ID);
        jdbcTemplate.update("DELETE FROM draw_snapshot WHERE event_id = ?", EVENT_ID);
        jdbcTemplate.update("DELETE FROM event_entry WHERE event_id = ?", EVENT_ID);
        jdbcTemplate.update("DELETE FROM event WHERE event_id = ?", EVENT_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update(
                "DELETE FROM member WHERE member_id IN (?, ?, ?)",
                OWNER_MEMBER_ID,
                FIRST_MEMBER_ID,
                SECOND_MEMBER_ID
        );
    }

    private record CandidateRow(long memberId, long ticketCount) {
    }
}

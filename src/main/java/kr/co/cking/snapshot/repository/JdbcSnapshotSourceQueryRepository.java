package kr.co.cking.snapshot.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.PrizeValue;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcSnapshotSourceQueryRepository implements SnapshotSourceQueryRepository {

    private final JdbcClient jdbcClient;

    @Override
    public Optional<SnapshotEventSource> findEventForUpdate(Long eventId) {
        return jdbcClient.sql("""
                        SELECT event_id, status, winner_count, draw_method, prize_algorithm_version
                        FROM event
                        WHERE event_id = :eventId
                        FOR UPDATE
                        """)
                .param("eventId", eventId)
                .query((resultSet, rowNumber) -> new SnapshotEventSource(
                        resultSet.getLong("event_id"),
                        EventStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("winner_count"),
                        resultSet.getString("draw_method"),
                        resultSet.getString("prize_algorithm_version")
                ))
                .optional();
    }

    @Override
    public List<Long> findMissingOfficialSnapshotEventIds(
            Instant closedBefore,
            Instant retryableBefore,
            int limit
    ) {
        if (closedBefore == null) {
            throw new IllegalArgumentException("closedBefore는 필수입니다.");
        }
        if (retryableBefore == null) {
            throw new IllegalArgumentException("retryableBefore는 필수입니다.");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit은 양수여야 합니다.");
        }
        return jdbcClient.sql("""
                        SELECT event.event_id
                        FROM event
                        LEFT JOIN draw_snapshot snapshot ON snapshot.event_id = event.event_id
                        LEFT JOIN snapshot_recovery_failure failure ON failure.event_id = event.event_id
                        WHERE event.status = 'CLOSED'
                          AND event.closed_at IS NOT NULL
                          AND event.closed_at <= :closedBefore
                          AND snapshot.id IS NULL
                          AND (failure.next_attempt_at IS NULL OR failure.next_attempt_at <= :retryableBefore)
                        ORDER BY event.closed_at ASC, event.event_id ASC
                        LIMIT :limit
                        """)
                .param("closedBefore", closedBefore)
                .param("retryableBefore", retryableBefore)
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    @Override
    public List<CandidateValue> findCandidates(Long eventId) {
        return jdbcClient.sql("""
                        SELECT member_id, SUM(used_ticket_count) AS ticket_count
                        FROM event_entry
                        WHERE event_id = :eventId
                        GROUP BY member_id
                        HAVING SUM(used_ticket_count) > 0
                        ORDER BY member_id ASC
                        """)
                .param("eventId", eventId)
                .query((resultSet, rowNumber) -> new CandidateValue(
                        resultSet.getLong("member_id"),
                        resultSet.getLong("ticket_count")
                ))
                .list();
    }

    @Override
    public List<PrizeValue> findPrizes(Long eventId) {
        return jdbcClient.sql("""
                        SELECT prize_key, display_name, priority, probability_weight, quantity
                        FROM event_prize
                        WHERE event_id = :eventId
                        ORDER BY priority ASC, prize_key ASC
                        """)
                .param("eventId", eventId)
                .query((resultSet, rowNumber) -> new PrizeValue(
                        resultSet.getString("prize_key"), resultSet.getString("display_name"),
                        resultSet.getInt("priority"), resultSet.getLong("probability_weight"),
                        resultSet.getInt("quantity")))
                .list();
    }
}

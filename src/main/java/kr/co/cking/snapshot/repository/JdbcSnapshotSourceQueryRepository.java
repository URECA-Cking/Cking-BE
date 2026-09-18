package kr.co.cking.snapshot.repository;

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
                        SELECT event_id, status, winner_count, draw_method
                        FROM event
                        WHERE event_id = :eventId
                        FOR UPDATE
                        """)
                .param("eventId", eventId)
                .query((resultSet, rowNumber) -> new SnapshotEventSource(
                        resultSet.getLong("event_id"),
                        EventStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("winner_count"),
                        resultSet.getString("draw_method")
                ))
                .optional();
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

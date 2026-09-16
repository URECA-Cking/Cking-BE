package kr.co.cking.snapshot.repository;

import java.util.List;
import java.util.Optional;
import kr.co.cking.snapshot.domain.CandidateValue;
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
                        resultSet.getString("status"),
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
}

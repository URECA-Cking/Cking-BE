package kr.co.cking.stream.repository;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DeadStreamMessageQueryRepository {

    private final JdbcClient jdbcClient;

    public List<String> findUnresolvedSpendSourceStreamIds(Long eventId) {
        return jdbcClient.sql("""
                        SELECT source_stream_id
                        FROM dead_stream_message
                        WHERE event_id = :eventId
                          AND stream_type = 'SPEND'
                          AND resolution_status = 'UNRESOLVED'
                        """)
                .param("eventId", eventId)
                .query(String.class)
                .list();
    }
}

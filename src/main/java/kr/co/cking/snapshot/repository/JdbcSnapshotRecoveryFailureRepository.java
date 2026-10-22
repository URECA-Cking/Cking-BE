package kr.co.cking.snapshot.repository;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 실패 횟수에 따라 최대 한 시간까지 다음 Snapshot 복구 시도를 늦춘다. */
@Repository
@RequiredArgsConstructor
public class JdbcSnapshotRecoveryFailureRepository implements SnapshotRecoveryFailureRepository {

    private final JdbcClient jdbcClient;

    @Override
    public void recordFailure(Long eventId, Instant failedAt, String failureCode, String failureMessage) {
        jdbcClient.sql("""
                        INSERT INTO snapshot_recovery_failure (
                            event_id, failure_count, next_attempt_at,
                            failure_code, failure_message, updated_at
                        ) VALUES (
                            :eventId, 1, DATE_ADD(:failedAt, INTERVAL 1 MINUTE),
                            :failureCode, :failureMessage, :failedAt
                        )
                        ON DUPLICATE KEY UPDATE
                            failure_count = failure_count + 1,
                            next_attempt_at = DATE_ADD(
                                :failedAt,
                                INTERVAL LEAST(60 * POW(2, failure_count), 3600) SECOND
                            ),
                            failure_code = VALUES(failure_code),
                            failure_message = VALUES(failure_message),
                            updated_at = VALUES(updated_at)
                        """)
                .param("eventId", eventId)
                .param("failedAt", failedAt)
                .param("failureCode", truncate(failureCode, 50))
                .param("failureMessage", truncate(failureMessage, 2000))
                .update();
    }

    @Override
    public void clear(Long eventId) {
        jdbcClient.sql("DELETE FROM snapshot_recovery_failure WHERE event_id = :eventId")
                .param("eventId", eventId)
                .update();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

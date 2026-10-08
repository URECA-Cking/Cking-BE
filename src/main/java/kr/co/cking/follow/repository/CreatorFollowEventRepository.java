package kr.co.cking.follow.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.co.cking.common.event.CreatorFollowCreated;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 팔로우 도메인이 소유하는 영속 이벤트 저장소. */
@Repository
@RequiredArgsConstructor
public class CreatorFollowEventRepository {
    private final JdbcTemplate jdbc;

    public void append(CreatorFollowCreated event, Instant firstRecoveryAt) {
        jdbc.update("""
                INSERT INTO creator_follow_event (event_id, member_id, creator_id, followed_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?)
                """, event.eventId().toString(), event.memberId(), event.creatorId(),
                Timestamp.from(event.followedAt()), Timestamp.from(firstRecoveryAt));
    }

    public Optional<CreatorFollowCreated> findPending(UUID id) {
        return jdbc.query("""
                SELECT member_id, creator_id, followed_at FROM creator_follow_event
                WHERE event_id = ? AND processed_at IS NULL FOR UPDATE
                """, (rs, n) -> new CreatorFollowCreated(id, rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant()),
                id.toString()).stream().findFirst();
    }

    public List<UUID> pendingIds(Instant now, int limit) {
        return jdbc.query("""
                SELECT event_id FROM creator_follow_event WHERE processed_at IS NULL AND next_attempt_at <= ?
                ORDER BY next_attempt_at, event_id LIMIT ?
                """, (rs, n) -> UUID.fromString(rs.getString(1)), Timestamp.from(now), limit);
    }

    public Optional<CreatorFollowCreated> findRecoverable(UUID id, Instant now) {
        return jdbc.query("""
                SELECT member_id, creator_id, followed_at FROM creator_follow_event
                WHERE event_id = ? AND processed_at IS NULL AND next_attempt_at <= ? FOR UPDATE
                """, (rs, n) -> new CreatorFollowCreated(id, rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant()),
                id.toString(), Timestamp.from(now)).stream().findFirst();
    }

    public void complete(UUID id, Instant now) {
        jdbc.update("UPDATE creator_follow_event SET processed_at = ? WHERE event_id = ?",
                Timestamp.from(now), id.toString());
    }

    public void defer(UUID id, Instant failedAt) {
        // MySQL의 SET 평가 순서를 이용해 증가 전 retry_count로 지연을 계산한다.
        jdbc.update("""
                UPDATE creator_follow_event
                SET next_attempt_at = TIMESTAMPADD(SECOND, LEAST(3600, 60 * POW(2, LEAST(retry_count, 6))), ?),
                    retry_count = LEAST(retry_count + 1, 2147483647)
                WHERE event_id = ? AND processed_at IS NULL
                """, Timestamp.from(failedAt), id.toString());
    }

    public int deleteExpired(Instant cutoff, int limit) {
        return jdbc.update("DELETE FROM creator_follow_event WHERE followed_at < ? ORDER BY followed_at, event_id LIMIT ?",
                Timestamp.from(cutoff), limit);
    }
}

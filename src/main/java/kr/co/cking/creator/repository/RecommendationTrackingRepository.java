package kr.co.cking.creator.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView;
import kr.co.cking.creator.application.dto.RecommendationEventCommand;
import kr.co.cking.creator.domain.RecommendationEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC 작업은 호출 서비스의 JPA 트랜잭션과 같은 DataSource/연결에 참여한다. */
@Repository
@RequiredArgsConstructor
public class RecommendationTrackingRepository {
    private final JdbcTemplate jdbc;

    public void saveSnapshot(UUID requestId, Long memberId, PersonalizedCreatorRecommendationView view,
                             Instant now, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO creator_recommendation_request
                  (request_id, member_id, policy_version, created_at, expires_at) VALUES (?, ?, ?, ?, ?)
                """, requestId.toString(), memberId, view.policyVersion(), Timestamp.from(now), Timestamp.from(expiresAt));
        int rank = 0;
        for (var item : view.items()) {
            jdbc.update("INSERT INTO creator_recommendation_card (request_id, creator_id, rank_no) VALUES (?, ?, ?)",
                    requestId.toString(), item.creatorId(), ++rank);
            for (var source : item.sources()) {
                jdbc.update("""
                        INSERT INTO creator_recommendation_source
                          (request_id, creator_id, source_type, source_key, taxonomy_version,
                           generation_id, method, model_version, source_rank) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, requestId.toString(), item.creatorId(), source.sourceType(), source.sourceKey(),
                        source.taxonomyVersion(), source.generationId(), source.method(), source.modelVersion(), source.sourceRank());
            }
        }
    }

    public Optional<RequestSnapshot> findRequest(UUID id) {
        return jdbc.query("SELECT member_id, expires_at FROM creator_recommendation_request WHERE request_id = ?",
                (rs, n) -> new RequestSnapshot(rs.getLong(1), rs.getTimestamp(2).toInstant()), id.toString())
                .stream().findFirst();
    }

    public boolean containsCard(UUID requestId, Long creatorId) {
        return !jdbc.queryForList("SELECT creator_id FROM creator_recommendation_card WHERE request_id = ? AND creator_id = ?",
                Long.class, requestId.toString(), creatorId).isEmpty();
    }

    public Optional<RecommendationEventCommand> findReceipt(UUID id) {
        return jdbc.query("""
                SELECT request_id, creator_id, event_type FROM creator_recommendation_event_receipt WHERE event_id = ?
                """, (rs, n) -> new RecommendationEventCommand(id, UUID.fromString(rs.getString(1)), rs.getLong(2),
                RecommendationEventType.valueOf(rs.getString(3))), id.toString()).stream().findFirst();
    }

    public void insertReceipt(Long memberId, RecommendationEventCommand event, Instant now) {
        jdbc.update("""
                INSERT INTO creator_recommendation_event_receipt
                  (event_id, request_id, creator_id, event_type, member_id, received_at) VALUES (?, ?, ?, ?, ?, ?)
                """, event.eventId().toString(), event.recommendationRequestId().toString(), event.creatorId(),
                event.eventType().name(), memberId, Timestamp.from(now));
    }

    public void insertInteractionIfAbsent(Long memberId, RecommendationEventCommand event, Instant now) {
        jdbc.update("""
                INSERT INTO creator_recommendation_interaction
                  (request_id, creator_id, event_type, member_id, event_id, received_at) VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE request_id = request_id
                """, event.recommendationRequestId().toString(), event.creatorId(), event.eventType().name(),
                memberId, event.eventId().toString(), Timestamp.from(now));
    }

    public Optional<Click> findLastClick(Long memberId, Long creatorId, Instant since, Instant until) {
        return jdbc.query("""
                SELECT request_id, event_id FROM creator_recommendation_event_receipt
                WHERE member_id = ? AND creator_id = ? AND event_type = 'CLICK'
                  AND received_at >= ? AND received_at <= ?
                ORDER BY received_at DESC, event_id DESC LIMIT 1
                """, (rs, n) -> new Click(rs.getString(1), rs.getString(2)), memberId, creatorId,
                Timestamp.from(since), Timestamp.from(until)).stream().findFirst();
    }

    public void insertConversionIfAbsent(Long memberId, Long creatorId, Instant followedAt, Click click, long windowSeconds) {
        jdbc.update("""
                INSERT INTO creator_recommendation_conversion
                  (request_id, creator_id, member_id, click_event_id, followed_at, attribution_policy, attribution_window_seconds)
                VALUES (?, ?, ?, ?, ?, 'LAST_CLICK_V1', ?)
                ON DUPLICATE KEY UPDATE request_id = request_id
                """, click.requestId(), creatorId, memberId, click.eventId(), Timestamp.from(followedAt), windowSeconds);
    }

    public int deleteExpiredHistory(Instant cutoff) {
        List<String> ids = jdbc.queryForList("""
                SELECT request_id FROM creator_recommendation_request WHERE created_at < ?
                ORDER BY created_at, request_id LIMIT 500
                """, String.class, Timestamp.from(cutoff));
        int deleted = 0;
        for (String id : ids) {
            deleted += jdbc.update("DELETE FROM creator_recommendation_request WHERE request_id = ?", id);
        }
        return deleted;
    }

    public record RequestSnapshot(Long memberId, Instant expiresAt) { }
    public record Click(String requestId, String eventId) { }
}

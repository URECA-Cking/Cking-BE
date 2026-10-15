package kr.co.cking.creator.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
        var cards = new ArrayList<Object[]>();
        var sources = new ArrayList<Object[]>();
        int rank = 0;
        for (var item : view.items()) {
            cards.add(new Object[] {requestId.toString(), item.creatorId(), ++rank});
            for (var source : item.sources()) {
                sources.add(new Object[] {requestId.toString(), item.creatorId(), source.sourceType().name(), source.sourceKey(),
                        source.taxonomyVersion(), source.generationId(), source.method(), source.modelVersion(), source.sourceRank()});
            }
        }
        insertRows("INSERT INTO creator_recommendation_card (request_id, creator_id, rank_no)", 3, cards);
        insertRows("""
                INSERT INTO creator_recommendation_source
                  (request_id, creator_id, source_type, source_key, taxonomy_version,
                   generation_id, method, model_version, source_rank)
                """, 9, sources);
    }

    /** 추천 스냅샷의 INSERT만 100행씩 묶는다. 연결 풀/드라이버의 전역 배치 옵션은 바꾸지 않는다. */
    private void insertRows(String insert, int columns, List<Object[]> rows) {
        String tuple = "(" + String.join(", ", Collections.nCopies(columns, "?")) + ")";
        for (int start = 0; start < rows.size(); start += 100) {
            var chunk = rows.subList(start, Math.min(start + 100, rows.size()));
            String sql = insert + " VALUES " + String.join(", ", Collections.nCopies(chunk.size(), tuple));
            Object[] parameters = chunk.stream().flatMap(Arrays::stream).toArray();
            jdbc.update(sql, parameters);
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
                ORDER BY received_at DESC, event_id DESC LIMIT 1 FOR UPDATE
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

    public int deleteExpiredHistory(Instant cutoff, int limit) {
        return jdbc.update("""
                DELETE FROM creator_recommendation_request WHERE created_at < ?
                ORDER BY created_at, request_id LIMIT ?
                """, Timestamp.from(cutoff), limit);
    }

    public record RequestSnapshot(Long memberId, Instant expiresAt) { }
    public record Click(String requestId, String eventId) { }
}

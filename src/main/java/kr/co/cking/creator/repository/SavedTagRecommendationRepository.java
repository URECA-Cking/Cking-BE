package kr.co.cking.creator.repository;

import java.util.List;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import kr.co.cking.creator.application.SavedTagRecommendationService.Score;
import static kr.co.cking.creator.application.ContentTagService.encode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 현재 확인 포인터를 PK 조인한다. 전역 Top-N을 먼저 적용하지 않는다. */
@Repository
@RequiredArgsConstructor
public class SavedTagRecommendationRepository {
    private final JdbcTemplate jdbc;
    public record Confirmed(long creatorId, String introduction, long revision, String name, String image,
                            String slug, long confirmationId, long automaticInputRevision, String inputHash,
                            String configHash, long confirmedInputRevision, String confirmedInputHash,
                            String taxonomyHash, String tags) { }
    public record Ranking(long sequence, String snapshotHash, String payloadHash, String modelVersion, String scores) { }

    public record Candidate(Confirmed row, BigDecimal score) { }
    private static final String CONFIRMED = """
        SELECT c.creator_id, c.activity_name, sp.intro_text, sp.content_revision, sp.profile_image_url, sp.slug,
               a.input_revision ar, a.input_hash ah, a.config_hash,
               cf.confirmation_id, cf.input_revision cr, cf.input_hash ch, cf.taxonomy_hash, cf.tags
        FROM creator_content_state st
        JOIN creator_space sp ON sp.creator_id=st.creator_id
        JOIN creator c ON c.creator_id=st.creator_id
        JOIN creator_content_automatic a ON a.automatic_revision=st.automatic_revision AND a.creator_id=st.creator_id
        JOIN creator_content_confirmation cf ON cf.confirmation_id=st.confirmation_id AND cf.creator_id=st.creator_id
        """;

    private static Confirmed confirmed(ResultSet r) throws SQLException {
        return new Confirmed(r.getLong("creator_id"), r.getString("intro_text"), r.getLong("content_revision"),
            r.getString("activity_name"), r.getString("profile_image_url"), r.getString("slug"), r.getLong("confirmation_id"),
            r.getLong("ar"), r.getString("ah"), r.getString("config_hash"), r.getLong("cr"), r.getString("ch"),
            r.getString("taxonomy_hash"), r.getString("tags"));
    }

    public List<Confirmed> confirmed() {
        return jdbc.query(CONFIRMED + " ORDER BY c.creator_id", (r, n) -> confirmed(r));
    }

    public List<Confirmed> confirmedByIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(
            CONFIRMED + " WHERE c.creator_id IN (:ids) ORDER BY c.creator_id", java.util.Map.of("ids", ids),
            (r, n) -> confirmed(r));
    }

    /** Only selected tag rows cross JDBC; public reads never load the global snapshot or score JSON. */
    public List<Candidate> candidates(List<String> parents, List<String> topics, String taxonomy, String config) {
        String sql = "SELECT eligible.*, ranked.score FROM (" + CONFIRMED + """
            WHERE JSON_OVERLAPS(cf.tags->'$.parents', CAST(:parentsJson AS JSON))
               OR JSON_OVERLAPS(cf.tags->'$.topics', CAST(:topicsJson AS JSON))
            ) eligible LEFT JOIN (
                SELECT creator_id, input_revision, confirmation_id, MAX(score) score
                FROM saved_tag_ranking_score
                WHERE application_sequence=(SELECT application_sequence FROM saved_tag_ranking_state WHERE state_id=1)
                    AND taxonomy_hash=:taxonomy AND config_hash=:config AND (
                    (tag_type='PARENT' AND tag_code IN (:parents)) OR
                    (tag_type='TOPIC' AND tag_code IN (:topics)))
                GROUP BY creator_id, input_revision, confirmation_id
            ) ranked ON ranked.creator_id=eligible.creator_id
                AND ranked.input_revision=eligible.content_revision
                AND ranked.confirmation_id=eligible.confirmation_id
            ORDER BY eligible.creator_id
            """;
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("parentsJson", encode(parents)).addValue("topicsJson", encode(topics))
            .addValue("taxonomy", taxonomy).addValue("config", config)
            .addValue("parents", parents.isEmpty() ? List.of("") : parents)
            .addValue("topics", topics.isEmpty() ? List.of("") : topics);
        return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
            .query(sql, params, (r, n) -> new Candidate(confirmed(r), r.getBigDecimal("score")));
    }

    public void replaceScores(List<Score> scores, List<Confirmed> rows, String taxonomy, String config, long sequence) {
        var identities = rows.stream().collect(java.util.stream.Collectors.toMap(Confirmed::creatorId, r -> r));
        jdbc.update("DELETE FROM saved_tag_ranking_score");
        jdbc.batchUpdate("""
            INSERT INTO saved_tag_ranking_score(tag_type,tag_code,creator_id,score,input_revision,confirmation_id,taxonomy_hash,config_hash,application_sequence)
            VALUES(?,?,?,?,?,?,?,?,?)
            """, scores, 500, (statement, score) -> {
                var row = identities.get(score.creatorId());
                statement.setString(1, score.type());
                statement.setString(2, score.code());
                statement.setLong(3, score.creatorId());
                statement.setBigDecimal(4, score.score());
                statement.setLong(5, row.revision());
                statement.setLong(6, row.confirmationId());
                statement.setString(7, taxonomy);
                statement.setString(8, config);
                statement.setLong(9, sequence);
            });
    }
    public Ranking ranking(boolean locking) {
        return jdbc.queryForObject("SELECT * FROM saved_tag_ranking_state WHERE state_id=1"+(locking?" FOR UPDATE":""),
            (r,n)->new Ranking(r.getLong("application_sequence"),r.getString("snapshot_hash"),r.getString("payload_hash"),
                    r.getString("model_version"),r.getString("scores")));
    }
    public void replace(long sequence,String snapshot,String hash,String model,String scores) {
        jdbc.update("UPDATE saved_tag_ranking_state SET application_sequence=?,snapshot_hash=?,payload_hash=?,model_version=?,scores=? WHERE state_id=1",
                sequence,snapshot,hash,model,scores);
    }
}

package kr.co.cking.creator.repository;

import java.util.List;
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

    public List<Confirmed> confirmed() {
        return jdbc.query("""
            SELECT c.creator_id, c.activity_name, sp.intro_text, sp.content_revision, sp.profile_image_url, sp.slug,
                   a.input_revision ar, a.input_hash ah, a.config_hash,
                   cf.confirmation_id, cf.input_revision cr, cf.input_hash ch, cf.taxonomy_hash, cf.tags
            FROM creator_content_state st
            JOIN creator_space sp ON sp.creator_id=st.creator_id
            JOIN creator c ON c.creator_id=st.creator_id
            JOIN creator_content_automatic a ON a.automatic_revision=st.automatic_revision AND a.creator_id=st.creator_id
            JOIN creator_content_confirmation cf ON cf.confirmation_id=st.confirmation_id AND cf.creator_id=st.creator_id
            ORDER BY c.creator_id
            """, (r,n) -> new Confirmed(r.getLong("creator_id"),r.getString("intro_text"),r.getLong("content_revision"),
                r.getString("activity_name"),r.getString("profile_image_url"),r.getString("slug"),r.getLong("confirmation_id"),
                r.getLong("ar"),r.getString("ah"),r.getString("config_hash"),r.getLong("cr"),r.getString("ch"),
                r.getString("taxonomy_hash"),r.getString("tags")));
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

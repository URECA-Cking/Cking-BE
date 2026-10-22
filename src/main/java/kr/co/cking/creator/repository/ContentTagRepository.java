package kr.co.cking.creator.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Creator-owned durable payloads, confirmation history and transactional outbox. */
@Repository
@RequiredArgsConstructor
public class ContentTagRepository {
    private final JdbcTemplate jdbc;
    public record Taxonomy(String version,String taxonomyHash,String criteriaHash,String configHash,String parents,String topics,String snapshot) {}
    public record Input(long creatorId,String introduction,long revision) {}
    public record Automatic(long creatorId,long revision,String inputHash,String configHash,String payloadHash,String payload,String automaticRevision) {}
    public record State(String automaticRevision,Long confirmationId) {}
    public record Confirmation(long revision,String inputHash,String taxonomyHash,String tags) {}
    public record Job(long creatorId,long generation,String status,String leaseToken,int attempts) {}
    public List<Taxonomy> taxonomies(boolean locking) {
        return jdbc.query("SELECT taxonomy_version,taxonomy_hash,criteria_hash,config_hash,parent_codes,topic_parents,taxonomy_snapshot FROM creator_content_taxonomy WHERE active=TRUE AND approved=TRUE"+(locking?" FOR SHARE":""),
                (r,n)->new Taxonomy(r.getString("taxonomy_version"),r.getString("taxonomy_hash"),r.getString("criteria_hash"),r.getString("config_hash"),r.getString("parent_codes"),r.getString("topic_parents"),r.getString("taxonomy_snapshot")));
    }
    public List<Input> inputs() {
        return jdbc.query("SELECT creator_id,intro_text,content_revision FROM creator_space ORDER BY creator_id",(r,n)->new Input(r.getLong("creator_id"),r.getString("intro_text"),r.getLong("content_revision")));
    }
    public List<Automatic> automatic(String revision) { return automatic(revision,false); }
    public List<Automatic> automatic(String revision,boolean locking) {
        return jdbc.query("SELECT creator_id,input_revision,input_hash,config_hash,payload_hash,payload,automatic_revision FROM creator_content_automatic WHERE automatic_revision=?"+(locking?" FOR UPDATE":""),(r,n)->new Automatic(r.getLong("creator_id"),r.getLong("input_revision"),r.getString("input_hash"),r.getString("config_hash"),r.getString("payload_hash"),r.getString("payload"),r.getString("automatic_revision")),revision);
    }
    public List<State> state(long creatorId) {
        return jdbc.query("SELECT automatic_revision,confirmation_id FROM creator_content_state WHERE creator_id=?",(r,n)->new State(r.getString("automatic_revision"),r.getObject("confirmation_id",Long.class)),creatorId);
    }
    public List<Confirmation> confirmation(long id) {
        return jdbc.query("SELECT input_revision,input_hash,taxonomy_hash,tags FROM creator_content_confirmation WHERE confirmation_id=?",(r,n)->new Confirmation(r.getLong("input_revision"),r.getString("input_hash"),r.getString("taxonomy_hash"),r.getString("tags")),id);
    }
    private static final String JOB_COLUMNS="creator_id,generation,status,lease_token,attempt_count";
    private static final org.springframework.jdbc.core.RowMapper<Job> JOB_MAPPER=(r,n)->new Job(r.getLong("creator_id"),r.getLong("generation"),r.getString("status"),r.getString("lease_token"),r.getInt("attempt_count"));
    public List<Job> claimable() {
        return jdbc.query("SELECT "+JOB_COLUMNS+" FROM creator_content_job WHERE (status='PENDING' OR (status='RUNNING' AND lease_until<UTC_TIMESTAMP(6))) AND next_retry_at<=UTC_TIMESTAMP(6) ORDER BY committed_at,creator_id LIMIT 1 FOR UPDATE SKIP LOCKED",JOB_MAPPER);
    }
    public List<Job> job(long creatorId) {
        return jdbc.query("SELECT "+JOB_COLUMNS+" FROM creator_content_job WHERE creator_id=? FOR UPDATE",JOB_MAPPER,creatorId);
    }
    public boolean hasAutomatic(long creatorId,long revision,String config) {
        return !jdbc.queryForList("SELECT automatic_revision FROM creator_content_automatic WHERE creator_id=? AND input_revision=? AND config_hash=? FOR SHARE",String.class,creatorId,revision,config).isEmpty();
    }
    public boolean hasCurrentAutomatic(long creatorId,long revision,String hash,String config) {
        return !jdbc.queryForList("SELECT a.automatic_revision FROM creator_content_state s JOIN creator_content_automatic a ON a.automatic_revision=s.automatic_revision WHERE s.creator_id=? AND a.input_revision=? AND a.input_hash=? AND a.config_hash=?",String.class,creatorId,revision,hash,config).isEmpty();
    }
    public record Processing(String status,int attempt_count,java.time.LocalDateTime committed_at,java.time.LocalDateTime claimed_at,java.time.LocalDateTime completed_at,java.time.LocalDateTime next_retry_at) {}
    public List<Processing> processing(long creatorId) {
        return jdbc.query("SELECT status,attempt_count,committed_at,claimed_at,completed_at,next_retry_at FROM creator_content_job WHERE creator_id=?",(r,n)->new Processing(r.getString("status"),r.getInt("attempt_count"),r.getObject("committed_at",java.time.LocalDateTime.class),r.getObject("claimed_at",java.time.LocalDateTime.class),r.getObject("completed_at",java.time.LocalDateTime.class),r.getObject("next_retry_at",java.time.LocalDateTime.class)),creatorId);
    }
    public long lastInsertId() { return java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class)); }
    public int update(String sql, Object... args) { return jdbc.update(sql, args); }
}

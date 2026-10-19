package kr.co.cking.creator.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.ContentIntro;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.repository.ContentTagRepository;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import static kr.co.cking.creator.domain.ContentTagErrorCode.*;

/** Space row lock serializes edits, automatic delivery and owner confirmation. */
@Service
@RequiredArgsConstructor
@Transactional
public class ContentTagService {
    private final ContentTagRepository repo;
    private final CreatorSpaceRepository spaces;
    private final CreatorRepository creators;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException(INVALID_CONTENT_TAG); }
    }
    public static JsonNode node(Object value) {
        try { return value instanceof String s ? JSON.readTree(s) : JSON.valueToTree(value); }
        catch (Exception e) { throw new BusinessException(INVALID_CONTENT_TAG); }
    }
    public static String digest(Object value) { return ContentIntro.sha256(encode(value)); }
    private static void require(boolean condition) { if (!condition) throw new BusinessException(INVALID_CONTENT_TAG); }
    private static String text(JsonNode n, String key) {
        JsonNode v = n.path(key); require(v.isTextual() && !v.asText().isBlank()); return v.asText();
    }
    private static long positive(JsonNode n, String key) {
        JsonNode v = n.path(key); require(v.isIntegralNumber() && v.canConvertToLong() && v.asLong() > 0); return v.asLong();
    }
    private CreatorSpace lock(long creatorId) {
        return spaces.findByCreatorIdForUpdate(creatorId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
    private Map<String,Object> taxonomy() {
        var rows = repo.rows("SELECT * FROM creator_content_taxonomy WHERE active=TRUE AND approved=TRUE FOR SHARE");
        if (rows.size() != 1) throw new BusinessException(CONTENT_TAXONOMY_UNAVAILABLE);
        var tax=rows.getFirst();
        JsonNode snapshot=node(field(tax,"taxonomy_snapshot"));
        var snapshotMap=JSON.convertValue(snapshot,Map.class);
        var hashRows=new ArrayList<kr.co.cking.interest.domain.InterestSubtopicHash.Row>();
        Map<String,String> links=new LinkedHashMap<>();
        for (JsonNode row:snapshot.path("subtopics")) {
            hashRows.add(new kr.co.cking.interest.domain.InterestSubtopicHash.Row(row.path("code").asText(),row.path("parent").asText(),row.path("name").asText(),row.path("definition").asText()));
            if (links.put(row.path("code").asText(),row.path("parent").asText())!=null)
                throw new BusinessException(CONTENT_TAXONOMY_UNAVAILABLE);
        }
        Set<String> activeParents=new HashSet<>();
        for (var row:repo.rows("SELECT c.interest_code FROM interest_category c JOIN interest_taxonomy t ON t.taxonomy_version=c.taxonomy_version WHERE c.active=TRUE AND t.active=TRUE"))
            activeParents.add(field(row,"interest_code"));
        Set<String> snapshotParents=new HashSet<>();
        for (var parent:snapshot.path("parents")) if (!parent.isTextual() || !snapshotParents.add(parent.asText()))
            throw new BusinessException(CONTENT_TAXONOMY_UNAVAILABLE);
        boolean validLinks=links.entrySet().stream().allMatch(e -> e.getKey().matches("[A-Z][A-Z0-9_]+")
                && snapshotParents.contains(e.getValue()) && e.getKey().startsWith(e.getValue()+"_"));
        if (!validLinks || !activeParents.equals(snapshotParents) || hashRows.isEmpty() || !digest(snapshotMap).equals(tax.get("criteria_hash"))
                || !kr.co.cking.interest.domain.InterestSubtopicHash.compute(hashRows).equals(tax.get("taxonomy_hash"))
                || !snapshot.path("version").asText().equals(tax.get("taxonomy_version"))
                || !snapshot.path("parents").equals(node(field(tax,"parent_codes")))
                || !node(links).equals(node(field(tax,"topic_parents"))))
            throw new BusinessException(CONTENT_TAXONOMY_UNAVAILABLE);
        return tax;
    }
    private static String field(Map<String,Object> row, String key) { return row.get(key).toString(); }

    /** One repeatable-read response avoids mixed-page introduction snapshots. */
    public Map<String,Object> inputs() {
        var tax = taxonomy();
        var rows = repo.rows("SELECT creator_id,intro_text,content_revision FROM creator_space ORDER BY creator_id");
        var items = rows.stream().map(r -> Map.of("creatorId",r.get("creator_id"),
                "introduction", ContentIntro.normalize(field(r,"intro_text")), "inputRevision",r.get("content_revision"),
                "inputHash", ContentIntro.hash(field(r,"intro_text")))).toList();
        var result = new LinkedHashMap<String,Object>();
        result.put("contractVersion","content-tags-v1"); result.put("taxonomyApproved",true);
        result.put("taxonomyHash",tax.get("taxonomy_hash")); result.put("items",items); result.put("nextCursor",null);
        return result;
    }

    public Map<String,Object> apply(long creatorId, Map<String,Object> body) {
        CreatorSpace space = lock(creatorId);
        var tax = taxonomy();
        JsonNode p = node(body), provenance = p.path("provenance"), record = p.path("record");
        require(positive(p,"creatorId") == creatorId);
        long revision = positive(p,"inputRevision");
        String hash = text(p,"inputHash"), automatic = text(p,"automaticRevision");
        require(automatic.matches("[0-9a-f]{64}") && automatic.equals(text(p,"idempotencyKey")));
        require("automatic".equals(text(p,"origin")) && p.path("creatorConfirmed").isBoolean() && !p.path("creatorConfirmed").asBoolean());
        if (space.getContentRevision() != revision || !ContentIntro.hash(space.getIntroText()).equals(hash))
            throw new BusinessException(CONTENT_INPUT_CONFLICT);
        Map<String,Object> provMap = JSON.convertValue(provenance, Map.class);
        String config = digest(provMap);
        if (!config.equals(tax.get("config_hash")) || !field(tax,"taxonomy_hash").equals(text(provenance,"taxonomyHash"))
                || !field(tax,"taxonomy_version").equals(text(provenance,"taxonomyVersion"))
                || !field(tax,"criteria_hash").equals(text(provenance,"criteriaHash")))
            throw new BusinessException(CONTENT_INPUT_CONFLICT);
        require("content-tags-v1".equals(text(provenance,"contractVersion")));
        require("grounded-detail-candidates-v2".equals(text(provenance,"promptVersion")));
        require("detail-limit-v2".equals(text(provenance,"policyVersion")));
        for (String key : List.of("promptHash","schemaHash","policyHash","criteriaHash","taxonomyHash"))
            require(text(provenance,key).matches("[0-9a-f]{64}"));
        text(provenance,"modelVersion");
        try { Instant.parse(text(p,"createdAt")); } catch (Exception e) { throw new BusinessException(INVALID_CONTENT_TAG); }
        require("automatic".equals(text(record,"kind")) && "pending".equals(text(record,"review_status")));
        require(Long.toString(creatorId).equals(text(record,"channel_id")));
        require(text(record,"input_hash").equals(ContentIntro.sha256(encode(ContentIntro.normalize(space.getIntroText())))));
        for (var pair : Map.of("model_version","modelVersion","prompt_version","promptVersion","prompt_hash","promptHash",
                "output_schema_hash","schemaHash","policy_version","policyVersion","be_hash","taxonomyHash",
                "taxonomy_version","taxonomyVersion","criteria_hash","criteriaHash").entrySet())
            require(text(record,pair.getKey()).equals(text(provenance,pair.getValue())));
        require(digest(JSON.convertValue(record.path("taxonomy_snapshot"),Map.class)).equals(tax.get("criteria_hash")));
        validateTags(record.path("result"),tax,ContentIntro.normalize(space.getIntroText()),true);
        String payloadHash = digest(body);
        var existing = repo.rows("SELECT * FROM creator_content_automatic WHERE automatic_revision=?",automatic);
        if (!existing.isEmpty()) {
            if (!payloadHash.equals(existing.getFirst().get("payload_hash"))) throw new BusinessException(CONTENT_PAYLOAD_CONFLICT);
            return Map.of("status","idempotent","automaticRevision",automatic);
        }
        if (!repo.rows("SELECT automatic_revision FROM creator_content_automatic WHERE creator_id=? AND input_revision=? AND config_hash=?",
                creatorId,revision,config).isEmpty()) throw new BusinessException(CONTENT_PAYLOAD_CONFLICT);
        repo.update("INSERT INTO creator_content_automatic(automatic_revision,creator_id,input_revision,input_hash,config_hash,payload_hash,payload,created_at) VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP(6))",
                automatic,creatorId,revision,hash,config,payloadHash,encode(body));
        repo.update("INSERT INTO creator_content_state(creator_id,automatic_revision) VALUES(?,?) ON DUPLICATE KEY UPDATE automatic_revision=?",creatorId,automatic,automatic);
        return Map.of("status","applied","automaticRevision",automatic);
    }

    private void validateTags(JsonNode result, Map<String,Object> tax, String intro, boolean evidence) {
        JsonNode parents = result.path("parents"), topics = result.path("topics");
        require(parents.isArray() && topics.isArray() && parents.size() <= 3 && topics.size() <= 9);
        Set<String> allowedParents = new HashSet<>(); node(field(tax,"parent_codes")).forEach(n -> allowedParents.add(n.asText()));
        JsonNode allowedTopics = node(field(tax,"topic_parents"));
        Set<String> selected = new HashSet<>(), selectedTopics = new HashSet<>();
        Map<String,Integer> counts = new HashMap<>();
        for (JsonNode parent : parents) {
            String code = evidence ? text(parent,"code") : parent.asText();
            require(allowedParents.contains(code) && selected.add(code));
            if (evidence) validateEvidence(parent,intro);
        }
        for (JsonNode topic : topics) {
            String code = evidence ? text(topic,"code") : topic.asText();
            String parent = allowedTopics.path(code).asText("");
            require(!parent.isEmpty() && selected.contains(parent) && selectedTopics.add(code));
            require(counts.merge(parent,1,Integer::sum) <= 3);
            if (evidence) { require(parent.equals(text(topic,"parent"))); validateEvidence(topic,intro); }
        }
        if (evidence) require(result.path("unclassified").isBoolean() && result.path("unclassified").asBoolean() == selected.isEmpty());
    }
    private void validateEvidence(JsonNode item, String intro) {
        require(intro.contains(text(item,"evidence")));
        require(item.path("core_evidence").isTextual());
        String core = item.path("core_evidence").asText();
        require(core.isEmpty() || intro.contains(core));
        require(item.path("repetition_evidence").isArray());
        for (JsonNode repetition : item.path("repetition_evidence")) require(repetition.isTextual() && !repetition.asText().isBlank() && intro.contains(repetition.asText()));
    }
    private long owner(long memberId) {
        return creators.findByMemberId(memberId).orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN)).getCreatorId();
    }
    public Map<String,Object> mine(long memberId) {
        long creatorId=owner(memberId);
        Map<String,Object> result=new LinkedHashMap<>(view(creatorId,false));
        var jobs=repo.rows("SELECT status,attempt_count,committed_at,claimed_at,completed_at,next_retry_at FROM creator_content_job WHERE creator_id=?",creatorId);
        if (!jobs.isEmpty()) result.put("processing",jobs.getFirst());
        return result;
    }
    public Map<String,Object> publicView(long creatorId) { return view(creatorId,true); }
    private Map<String,Object> view(long creatorId, boolean publicOnly) {
        CreatorSpace space = lock(creatorId);
        Map<String,Object> tax;
        try { tax = taxonomy(); } catch (BusinessException e) {
            return Map.of("status","disabled","parents",List.of(),"topics",List.of());
        }
        var state = repo.rows("SELECT * FROM creator_content_state WHERE creator_id=?",creatorId);
        if (state.isEmpty() || state.getFirst().get("automatic_revision") == null)
            return Map.of("status","processing","parents",List.of(),"topics",List.of());
        String automatic = field(state.getFirst(),"automatic_revision");
        var row = repo.rows("SELECT * FROM creator_content_automatic WHERE automatic_revision=?",automatic).getFirst();
        boolean current = ((Number)row.get("input_revision")).longValue() == space.getContentRevision()
                && ContentIntro.hash(space.getIntroText()).equals(row.get("input_hash")) && tax.get("config_hash").equals(row.get("config_hash"));
        if (!current) return Map.of("status","reanalysis_required","parents",List.of(),"topics",List.of());
        Object confirmationId = state.getFirst().get("confirmation_id");
        if (confirmationId != null) {
            var confirmations = repo.rows("SELECT * FROM creator_content_confirmation WHERE confirmation_id=?",confirmationId);
            if (!confirmations.isEmpty()) {
                var c = confirmations.getFirst();
                if (((Number)c.get("input_revision")).longValue() == space.getContentRevision()
                        && row.get("input_hash").equals(c.get("input_hash")) && tax.get("taxonomy_hash").equals(c.get("taxonomy_hash"))) {
                    JsonNode tags = node(field(c,"tags"));
                    Map<String,Object> result = new LinkedHashMap<>();
                    result.put("status","creator_confirmed");
                    result.put("parents",JSON.convertValue(tags.path("parents"),List.class));
                    result.put("topics",JSON.convertValue(tags.path("topics"),List.class));
                    if (!publicOnly) {
                        result.put("automaticRevision",automatic); result.put("inputHash",row.get("input_hash"));
                        result.put("inputRevision",row.get("input_revision"));
                        result.put("automaticResult",JSON.convertValue(node(field(row,"payload")).path("record").path("result"),Map.class));
                    }
                    return result;
                }
            }
        }
        if (publicOnly) return Map.of("status","unconfirmed","parents",List.of(),"topics",List.of());
        JsonNode payload = node(field(row,"payload"));
        return Map.of("status", payload.path("record").path("result").path("unclassified").asBoolean() ? "unclassified" : "unconfirmed",
                "automaticRevision",automatic,"inputHash",row.get("input_hash"),"inputRevision",row.get("input_revision"),
                "automaticResult",JSON.convertValue(payload.path("record").path("result"),Map.class));
    }
    public Map<String,Object> confirm(long memberId, Map<String,Object> body) {
        long creatorId = owner(memberId); CreatorSpace space = lock(creatorId); var tax = taxonomy(); JsonNode p = node(body);
        var states = repo.rows("SELECT * FROM creator_content_state WHERE creator_id=?",creatorId);
        String automatic = text(p,"automaticRevision"), hash = text(p,"inputHash");
        if (states.isEmpty() || !automatic.equals(states.getFirst().get("automatic_revision")) || !hash.equals(ContentIntro.hash(space.getIntroText())))
            throw new BusinessException(CONTENT_INPUT_CONFLICT);
        var row = repo.rows("SELECT * FROM creator_content_automatic WHERE automatic_revision=?",automatic).getFirst();
        if (((Number)row.get("input_revision")).longValue() != space.getContentRevision() || !hash.equals(row.get("input_hash"))
                || !tax.get("config_hash").equals(row.get("config_hash"))) throw new BusinessException(CONTENT_INPUT_CONFLICT);
        validateTags(p,tax,"",false);
        String tags = encode(Map.of("parents",body.get("parents"),"topics",body.get("topics")));
        repo.update("INSERT INTO creator_content_confirmation(creator_id,automatic_revision,input_revision,input_hash,taxonomy_hash,tags,confirmed_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(6))",
                creatorId,automatic,space.getContentRevision(),hash,tax.get("taxonomy_hash"),tags);
        Long id = ((Number)repo.rows("SELECT LAST_INSERT_ID() id").getFirst().get("id")).longValue();
        repo.update("UPDATE creator_content_state SET confirmation_id=? WHERE creator_id=?",id,creatorId);
        return view(creatorId,false);
    }

    public Map<String,Object> claim(Map<String,Object> body) {
        long seconds = positive(node(body),"leaseSeconds"); require(seconds <= 900);
        // SKIP LOCKED allows independent claims; no write to Space while claiming.
        var jobs = repo.rows("SELECT * FROM creator_content_job WHERE (status='PENDING' OR (status='RUNNING' AND lease_until<UTC_TIMESTAMP(6))) AND next_retry_at<=UTC_TIMESTAMP(6) ORDER BY committed_at,creator_id LIMIT 1 FOR UPDATE SKIP LOCKED");
        if (jobs.isEmpty()) return Map.of();
        var job = jobs.getFirst(); long creatorId = ((Number)job.get("creator_id")).longValue();
        if (((Number)job.get("attempt_count")).intValue() >= 5) {
            repo.update("UPDATE creator_content_job SET status='FAILED' WHERE creator_id=?",creatorId);
            LoggerFactory.getLogger(getClass()).error("Content job exhausted retries creatorId={}",creatorId);
            return Map.of();
        }
        String token = UUID.randomUUID().toString();
        repo.update("UPDATE creator_content_job SET status='RUNNING',lease_token=?,lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),claimed_at=UTC_TIMESTAMP(6),attempt_count=attempt_count+1 WHERE creator_id=?",token,seconds,creatorId);
        return Map.of("jobId",Long.toString(creatorId),"leaseToken",token,"generation",job.get("generation"));
    }
    public Map<String,Object> ack(long creatorId, Map<String,Object> body) {
        JsonNode p = node(body); String status = text(p,"status"); require(Set.of("completed","retry").contains(status));
        long generation = positive(p,"generation"); String token = text(p,"leaseToken");
        var jobs = repo.rows("SELECT * FROM creator_content_job WHERE creator_id=? FOR UPDATE",creatorId);
        if (jobs.isEmpty()) throw new BusinessException(CONTENT_LEASE_CONFLICT);
        var job = jobs.getFirst();
        if (!"RUNNING".equals(job.get("status")) || !token.equals(job.get("lease_token")) || generation != ((Number)job.get("generation")).longValue())
            throw new BusinessException(CONTENT_LEASE_CONFLICT);
        int attempts = ((Number)job.get("attempt_count")).intValue();
        String next = status.equals("completed") ? "COMPLETED" : attempts >= 5 ? "FAILED" : "PENDING";
        // Summary is allowlisted rather than trusting arbitrary worker text or profile fields.
        JsonNode summary = p.path("summary");
        String safe = encode(Map.of("failureCount",summary.path("failureCount").asInt(),"startedAt",summary.path("startedAt").asDouble(),"completedAt",summary.path("completedAt").asDouble()));
        int updated = repo.update("UPDATE creator_content_job SET status=?,lease_token=NULL,lease_until=NULL,completed_at=UTC_TIMESTAMP(6),next_retry_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),last_summary=? WHERE creator_id=? AND lease_until>=UTC_TIMESTAMP(6)",next,Math.min(3600,30*(1<<attempts)),safe,creatorId);
        if (updated != 1) throw new BusinessException(CONTENT_LEASE_CONFLICT);
        if (next.equals("FAILED")) LoggerFactory.getLogger(getClass()).error("Content job failed creatorId={}",creatorId);
        return Map.of("status",next);
    }
    public Map<String,Object> reconcile(Map<String,Object> body) {
        JsonNode p=node(body); require(p.path("inputs").isArray());
        var tax=taxonomy(); int completed=0; long previous=0;
        for (JsonNode input:p.path("inputs")) {
            long creatorId=positive(input,"creatorId"); require(creatorId>previous); previous=creatorId;
            CreatorSpace space=lock(creatorId);
            long revision=positive(input,"inputRevision"); String hash=text(input,"inputHash");
            if (revision!=space.getContentRevision() || !ContentIntro.hash(space.getIntroText()).equals(hash)) continue;
            var applied=repo.rows("SELECT a.automatic_revision FROM creator_content_state s JOIN creator_content_automatic a ON a.automatic_revision=s.automatic_revision WHERE s.creator_id=? AND a.input_revision=? AND a.input_hash=? AND a.config_hash=?",creatorId,revision,hash,tax.get("config_hash"));
            if (!applied.isEmpty()) completed+=repo.update("UPDATE creator_content_job SET status='COMPLETED',completed_at=UTC_TIMESTAMP(6) WHERE creator_id=? AND generation=? AND status IN ('PENDING','FAILED')",creatorId,revision);
        }
        return Map.of("completedCount",completed);
    }
    public Map<String,Object> renew(long creatorId, Map<String,Object> body) {
        JsonNode p=node(body);
        int updated=repo.update("UPDATE creator_content_job SET lease_until=TIMESTAMPADD(SECOND,900,UTC_TIMESTAMP(6)) WHERE creator_id=? AND generation=? AND lease_token=? AND status='RUNNING' AND lease_until>=UTC_TIMESTAMP(6)",
                creatorId,positive(p,"generation"),text(p,"leaseToken"));
        if (updated!=1) throw new BusinessException(CONTENT_LEASE_CONFLICT);
        return Map.of("status","RUNNING");
    }
    public Map<String,Object> retry(long creatorId) {
        lock(creatorId);
        int count = repo.update("UPDATE creator_content_job SET status='PENDING',attempt_count=0,lease_token=NULL,lease_until=NULL,next_retry_at=UTC_TIMESTAMP(6) WHERE creator_id=? AND status<>'RUNNING'",creatorId);
        if (count != 1) throw new BusinessException(CONTENT_LEASE_CONFLICT);
        return Map.of("status","PENDING");
    }
}

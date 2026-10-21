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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
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
    private final PlatformTransactionManager transactionManager;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE).build()).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException(INVALID_CONTENT_TAG); }
    }
    public static JsonNode node(Object value) {
        try { return value instanceof String s ? JSON.readTree(s) : JSON.valueToTree(value); }
        catch (Exception e) { throw new BusinessException(INVALID_CONTENT_TAG); }
    }
    public static String digest(Object value) {
        try { return ContentIntro.sha256(encode(value)); }
        catch (IllegalArgumentException e) { throw new BusinessException(INVALID_CONTENT_TAG); }
    }
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
    private ContentTagRepository.Taxonomy taxonomy() { return taxonomy(true); }
    ContentTagRepository.Taxonomy taxonomy(boolean locking) {
        var rows = repo.taxonomies(locking);
        if (rows.size() != 1) throw new BusinessException(CONTENT_TAXONOMY_UNAVAILABLE);
        var tax=rows.getFirst();
        JsonNode snapshot=node(tax.snapshot());
        var snapshotMap=JSON.convertValue(snapshot,Map.class);
        var hashRows=new ArrayList<kr.co.cking.interest.domain.InterestSubtopicHash.Row>();
        Map<String,String> links=new LinkedHashMap<>();
        for (JsonNode row:snapshot.path("subtopics")) {
            hashRows.add(new kr.co.cking.interest.domain.InterestSubtopicHash.Row(row.path("code").asText(),row.path("parent").asText(),row.path("name").asText(),row.path("definition").asText()));
            if (links.put(row.path("code").asText(),row.path("parent").asText())!=null)
                throw new BusinessException(CONTENT_TAXONOMY_UNAVAILABLE);
        }
        Set<String> snapshotParents=new HashSet<>();
        for (var parent:snapshot.path("parents")) if (!parent.isTextual() || !snapshotParents.add(parent.asText()))
            throw new BusinessException(CONTENT_TAXONOMY_UNAVAILABLE);
        boolean validLinks=links.entrySet().stream().allMatch(e -> e.getKey().matches("[A-Z][A-Z0-9_]+")
                && snapshotParents.contains(e.getValue()) && e.getKey().startsWith(e.getValue()+"_"));
        if (!validLinks || snapshotParents.isEmpty() || snapshotParents.stream().anyMatch(code -> !code.matches("[A-Z][A-Z0-9_]+")) || hashRows.isEmpty() || !digest(snapshotMap).equals(tax.criteriaHash())
                || !kr.co.cking.interest.domain.InterestSubtopicHash.compute(hashRows).equals(tax.taxonomyHash())
                || !snapshot.path("version").asText().equals(tax.version())
                || !snapshot.path("parents").equals(node(tax.parents()))
                || !node(links).equals(node(tax.topics())))
            throw new BusinessException(CONTENT_TAXONOMY_UNAVAILABLE);
        return tax;
    }

    /** One repeatable-read response avoids mixed-page introduction snapshots. */
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> inputs() {
        var tax = taxonomy(false);
        var items = repo.inputs().stream().map(r -> {
            String normalized = ContentIntro.normalize(r.introduction());
            return Map.of("creatorId",r.creatorId(),"introduction",normalized,
                    "inputRevision",r.revision(),"inputHash",ContentIntro.sha256(normalized));
        }).toList();
        var result = new LinkedHashMap<String,Object>();
        result.put("contractVersion","content-tags-v1"); result.put("taxonomyApproved",true);
        result.put("taxonomyHash",tax.taxonomyHash()); result.put("generationConfigHash",tax.configHash()); result.put("items",items); result.put("nextCursor",null);
        return result;
    }

    public Map<String,Object> apply(long creatorId, Map<String,Object> body) {
        JsonNode p = node(body), provenance = p.path("provenance"), record = p.path("record");
        require(positive(p,"creatorId") == creatorId);
        long revision = positive(p,"inputRevision");
        String hash = text(p,"inputHash"), automatic = text(p,"automaticRevision");
        require(automatic.matches("[0-9a-f]{64}") && automatic.equals(text(p,"idempotencyKey")));
        require("automatic".equals(text(p,"origin")) && p.path("creatorConfirmed").isBoolean() && !p.path("creatorConfirmed").asBoolean());
        String payloadHash = digest(body);
        var existing = repo.automatic(automatic);
        if (!existing.isEmpty()) {
            if (existing.getFirst().creatorId()!=creatorId || !payloadHash.equals(existing.getFirst().payloadHash())) throw new BusinessException(CONTENT_PAYLOAD_CONFLICT);
            return Map.of("status","idempotent","automaticRevision",automatic);
        }
        CreatorSpace space = lock(creatorId);
        // Recheck with a current read after the Space lock: another delivery may have committed.
        existing = repo.automatic(automatic,true);
        if (!existing.isEmpty()) {
            if (existing.getFirst().creatorId()!=creatorId || !payloadHash.equals(existing.getFirst().payloadHash())) throw new BusinessException(CONTENT_PAYLOAD_CONFLICT);
            return Map.of("status","idempotent","automaticRevision",automatic);
        }
        var tax = taxonomy();
        if (space.getContentRevision() != revision || !ContentIntro.hash(space.getIntroText()).equals(hash))
            throw new BusinessException(CONTENT_INPUT_CONFLICT);
        Map<String,Object> provMap = JSON.convertValue(provenance, Map.class);
        String config = digest(provMap);
        if (!config.equals(tax.configHash()) || !tax.taxonomyHash().equals(text(provenance,"taxonomyHash"))
                || !tax.version().equals(text(provenance,"taxonomyVersion"))
                || !tax.criteriaHash().equals(text(provenance,"criteriaHash")))
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
        require(digest(JSON.convertValue(record.path("taxonomy_snapshot"),Map.class)).equals(tax.criteriaHash()));
        validateTags(record.path("result"),tax,ContentIntro.normalize(space.getIntroText()),true);
        if (repo.hasAutomatic(creatorId,revision,config)) throw new BusinessException(CONTENT_PAYLOAD_CONFLICT);
        repo.update("INSERT INTO creator_content_automatic(automatic_revision,creator_id,input_revision,input_hash,config_hash,payload_hash,payload,created_at) VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP(6))",
                automatic,creatorId,revision,hash,config,payloadHash,encode(body));
        repo.update("INSERT INTO creator_content_state(creator_id,automatic_revision) VALUES(?,?) ON DUPLICATE KEY UPDATE automatic_revision=?",creatorId,automatic,automatic);
        return Map.of("status","applied","automaticRevision",automatic);
    }

    private void validateTags(JsonNode result, ContentTagRepository.Taxonomy tax, String intro, boolean evidence) {
        JsonNode parents = result.path("parents"), topics = result.path("topics");
        require(parents.isArray() && topics.isArray() && parents.size() <= 3 && topics.size() <= 9);
        Set<String> allowedParents = new HashSet<>(); node(tax.parents()).forEach(n -> allowedParents.add(n.asText()));
        JsonNode allowedTopics = node(tax.topics());
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
        var jobs=repo.processing(creatorId);
        if (!jobs.isEmpty()) result.put("processing",jobs.getFirst());
        return result;
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> publicView(long creatorId) { return view(creatorId,true); }
    private Map<String,Object> view(long creatorId, boolean publicOnly) {
        CreatorSpace space = publicOnly ? spaces.findByCreatorId(creatorId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND)) : lock(creatorId);
        ContentTagRepository.Taxonomy tax;
        try { tax = taxonomy(!publicOnly); } catch (BusinessException e) {
            return Map.of("status","disabled","parents",List.of(),"topics",List.of());
        }
        var state = repo.state(creatorId);
        if (state.isEmpty() || state.getFirst().automaticRevision() == null)
            return Map.of("status","processing","parents",List.of(),"topics",List.of());
        String automatic = state.getFirst().automaticRevision();
        var row = repo.automatic(automatic).getFirst();
        boolean current = row.revision() == space.getContentRevision()
                && ContentIntro.hash(space.getIntroText()).equals(row.inputHash()) && tax.configHash().equals(row.configHash());
        if (!current) return Map.of("status","reanalysis_required","parents",List.of(),"topics",List.of());
        Long confirmationId = state.getFirst().confirmationId();
        if (confirmationId != null) {
            var confirmations = repo.confirmation(confirmationId);
            if (!confirmations.isEmpty()) {
                var c = confirmations.getFirst();
                if (c.revision() == space.getContentRevision()
                        && row.inputHash().equals(c.inputHash()) && tax.taxonomyHash().equals(c.taxonomyHash())) {
                    JsonNode tags = node(c.tags());
                    Map<String,Object> result = new LinkedHashMap<>();
                    result.put("status","creator_confirmed");
                    result.put("parents",JSON.convertValue(tags.path("parents"),List.class));
                    result.put("topics",JSON.convertValue(tags.path("topics"),List.class));
                    if (!publicOnly) {
                        result.put("automaticRevision",automatic); result.put("inputHash",row.inputHash());
                        result.put("inputRevision",row.revision());
                        result.put("automaticResult",JSON.convertValue(node(row.payload()).path("record").path("result"),Map.class));
                    }
                    return result;
                }
            }
        }
        if (publicOnly) return Map.of("status","unconfirmed","parents",List.of(),"topics",List.of());
        JsonNode payload = node(row.payload());
        return Map.of("status", payload.path("record").path("result").path("unclassified").asBoolean() ? "unclassified" : "unconfirmed",
                "automaticRevision",automatic,"inputHash",row.inputHash(),"inputRevision",row.revision(),
                "automaticResult",JSON.convertValue(payload.path("record").path("result"),Map.class));
    }
    public Map<String,Object> confirm(long memberId, Map<String,Object> body) {
        long creatorId = owner(memberId); CreatorSpace space = lock(creatorId); var tax = taxonomy(); JsonNode p = node(body);
        var states = repo.state(creatorId);
        String automatic = text(p,"automaticRevision"), hash = text(p,"inputHash");
        if (states.isEmpty() || !automatic.equals(states.getFirst().automaticRevision()) || !hash.equals(ContentIntro.hash(space.getIntroText())))
            throw new BusinessException(CONTENT_INPUT_CONFLICT);
        var row = repo.automatic(automatic).getFirst();
        if (row.revision() != space.getContentRevision() || !hash.equals(row.inputHash())
                || !tax.configHash().equals(row.configHash())) throw new BusinessException(CONTENT_INPUT_CONFLICT);
        validateTags(p,tax,"",false);
        String tags = encode(Map.of("parents",body.get("parents"),"topics",body.get("topics")));
        repo.update("INSERT INTO creator_content_confirmation(creator_id,automatic_revision,input_revision,input_hash,taxonomy_hash,tags,confirmed_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(6))",
                creatorId,automatic,space.getContentRevision(),hash,tax.taxonomyHash(),tags);
        long id = repo.lastInsertId();
        repo.update("UPDATE creator_content_state SET confirmation_id=? WHERE creator_id=?",id,creatorId);
        return view(creatorId,false);
    }

    public Map<String,Object> claim(Map<String,Object> body) {
        long seconds = positive(node(body),"leaseSeconds"); require(seconds <= 900);
        // SKIP LOCKED allows independent claims; no write to Space while claiming.
        while (true) {
            var jobs = repo.claimable();
            if (jobs.isEmpty()) return Map.of();
            var job = jobs.getFirst(); long creatorId = job.creatorId();
            if (job.attempts() >= 5) {
                repo.update("UPDATE creator_content_job SET status='FAILED' WHERE creator_id=?",creatorId);
                LoggerFactory.getLogger(getClass()).error("Content job exhausted retries creatorId={}",creatorId);
                continue;
            }
            String token = UUID.randomUUID().toString();
            repo.update("UPDATE creator_content_job SET status='RUNNING',lease_token=?,lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),claimed_at=UTC_TIMESTAMP(6),attempt_count=attempt_count+1 WHERE creator_id=?",token,seconds,creatorId);
            return Map.of("jobId",Long.toString(creatorId),"leaseToken",token,"generation",job.generation());
        }
    }
    public Map<String,Object> ack(long creatorId, Map<String,Object> body) {
        JsonNode p = node(body); String status = text(p,"status"); require(Set.of("completed","retry").contains(status));
        long generation = positive(p,"generation"); String token = text(p,"leaseToken");
        var jobs = repo.job(creatorId);
        if (jobs.isEmpty()) throw new BusinessException(CONTENT_LEASE_CONFLICT);
        var job = jobs.getFirst();
        if (!"RUNNING".equals(job.status()) || !token.equals(job.leaseToken()) || generation != job.generation())
            throw new BusinessException(CONTENT_LEASE_CONFLICT);
        int attempts = job.attempts();
        String next = status.equals("completed") ? "COMPLETED" : attempts >= 5 ? "FAILED" : "PENDING";
        // Summary is allowlisted rather than trusting arbitrary worker text or profile fields.
        JsonNode summary = p.path("summary");
        String safe = encode(Map.of("failureCount",summary.path("failureCount").asInt(),"startedAt",summary.path("startedAt").asDouble(),"completedAt",summary.path("completedAt").asDouble()));
        int updated = repo.update("UPDATE creator_content_job SET status=?,lease_token=NULL,lease_until=NULL,completed_at=IF(?='COMPLETED',UTC_TIMESTAMP(6),NULL),next_retry_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),last_summary=? WHERE creator_id=? AND lease_until>=UTC_TIMESTAMP(6)",next,next,Math.min(3600,30*(1<<attempts)),safe,creatorId);
        if (updated != 1) throw new BusinessException(CONTENT_LEASE_CONFLICT);
        if (next.equals("FAILED")) LoggerFactory.getLogger(getClass()).error("Content job failed creatorId={}",creatorId);
        return Map.of("status",next);
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Map<String,Object> reconcile(Map<String,Object> body) {
        JsonNode p=node(body); require(p.path("inputs").isArray());
        var inputs=new ArrayList<ReconcileInput>(); long previous=0;
        for (JsonNode input:p.path("inputs")) {
            long id=positive(input,"creatorId"); require(id>previous); previous=id;
            inputs.add(new ReconcileInput(id,positive(input,"inputRevision"),text(input,"inputHash")));
        }
        var transaction=new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int completed=0;
        for (var input:inputs) completed+=Objects.requireNonNull(transaction.execute(status -> reconcileOne(input)));
        return Map.of("completedCount",completed);
    }
    private record ReconcileInput(long creatorId,long revision,String hash) {}
    private int reconcileOne(ReconcileInput input) {
        CreatorSpace space=lock(input.creatorId()); var tax=taxonomy();
        if (input.revision()!=space.getContentRevision() || !ContentIntro.hash(space.getIntroText()).equals(input.hash())) return 0;
        boolean applied=repo.hasCurrentAutomatic(input.creatorId(),input.revision(),input.hash(),tax.configHash());
        return !applied ? 0 : repo.update("UPDATE creator_content_job SET status='COMPLETED',completed_at=UTC_TIMESTAMP(6) WHERE creator_id=? AND generation=? AND status IN ('PENDING','FAILED')",input.creatorId(),input.revision());
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
        int count = repo.update("UPDATE creator_content_job SET status='PENDING',attempt_count=0,lease_token=NULL,lease_until=NULL,completed_at=NULL,next_retry_at=UTC_TIMESTAMP(6) WHERE creator_id=? AND status<>'RUNNING'",creatorId);
        if (count != 1) throw new BusinessException(CONTENT_LEASE_CONFLICT);
        return Map.of("status","PENDING");
    }
}

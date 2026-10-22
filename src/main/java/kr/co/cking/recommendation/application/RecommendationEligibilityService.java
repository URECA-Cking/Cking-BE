package kr.co.cking.recommendation.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.CreatorErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** 모델 판단은 배치의 책임이다. BE는 근거의 완전성·분야 일치·현재 소개만 검증한다. */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class RecommendationEligibilityService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final kr.co.cking.interest.application.InterestTaxonomyQueryService taxonomyQueryService;
    public record Ref(long generation, Long seed, long candidate, Set<String> interests) { }

    public static String introductionHash(String raw) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r','\n');
        int start=0, end=text.length();
        while(start<end && whitespace(text.codePointAt(start))) start+=Character.charCount(text.codePointAt(start));
        while(end>start && whitespace(text.codePointBefore(end))) end-=Character.charCount(text.codePointBefore(end));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.substring(start,end).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static boolean whitespace(int c) { return Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0x85; }
    private static final String EMPTY_HASH=introductionHash("");
    private static BusinessException invalid() { return new BusinessException(CreatorErrorCode.INVALID_RECOMMENDATION_RESULT); }
    private static JsonNode parse(String raw) {
        try { return JSON.readTree(raw); } catch(Exception e) { throw invalid(); }
    }
    private Set<String> taxonomy(JsonNode proof) {
        return taxonomy(proof, true, new HashMap<>());
    }
    /** 적재는 활성 분류체계만 받고, 조회는 근거에 적힌 등록 버전으로 판정해 분류체계 교체·분야 비활성 뒤에도 기존 추천을 유지한다. */
    private Set<String> taxonomy(JsonNode proof, boolean active, Map<String, Set<String>> cache) {
        requireHeader(proof);
        String version=proof.path("taxonomyVersion").asText(), hash=proof.path("taxonomyHash").asText();
        String key = version + ":" + hash;
        if (cache.containsKey(key)) return cache.get(key);
        var allowed = active ? taxonomyQueryService.activeCodes(version, hash) : taxonomyQueryService.registeredCodes(version, hash);
        if (allowed.isEmpty()) throw invalid();
        cache.put(key, allowed);
        return allowed;
    }
    private static void requireHeader(JsonNode proof) {
        if (proof==null || !proof.isObject() || proof.size()!=4 || !"RELATED_V1".equals(proof.path("policyVersion").asText())
                || !proof.path("taxonomyVersion").isTextual() || !proof.path("taxonomyHash").asText().matches("[0-9a-f]{64}")
                || !proof.path("profiles").isArray() || proof.path("profiles").size()>101) throw invalid();
    }
    private Map<Long,JsonNode> profiles(JsonNode proof, Set<String> allowed) {
        var result=new TreeMap<Long,JsonNode>(); long previous=0;
        for(var row:proof.path("profiles")) {
            long id=row.path("creatorId").asLong();
            if(!row.isObject() || row.size()!=3 || !row.path("creatorId").isIntegralNumber() || !row.path("creatorId").canConvertToLong()
                    || id<=previous || !row.path("introductionHash").asText().matches("[0-9a-f]{64}")
                    || !row.path("tags").isArray() || row.path("tags").size()>3) throw invalid();
            String last="";
            for(var tag:row.path("tags")) {
                if(!tag.isTextual() || !allowed.contains(tag.asText()) || last.compareTo(tag.asText())>=0) throw invalid();
                last=tag.asText();
            }
            previous=id; result.put(id,row);
        }
        return result;
    }
    private Map<Long,String> current(Set<Long> ids, boolean lock) {
        if(ids.isEmpty()) return Map.of();
        var result=new HashMap<Long,String>();
        new NamedParameterJdbcTemplate(jdbc).query("SELECT creator_id,intro_text FROM creator_space WHERE creator_id IN (:ids) ORDER BY creator_id"+(lock?" FOR UPDATE":""),
            Map.of("ids",ids), (org.springframework.jdbc.core.RowCallbackHandler) r -> result.put(r.getLong(1), introductionHash(r.getString(2))));
        return result;
    }
    private static Set<String> tags(JsonNode row) {
        var result=new HashSet<String>(); row.path("tags").forEach(t->result.add(t.asText())); return result;
    }
    private static boolean matches(Ref ref,Map<Long,JsonNode> profiles,Map<Long,String> inputs) {
        var candidate=profiles.get(ref.candidate());
        if(candidate==null || EMPTY_HASH.equals(inputs.get(ref.candidate())) || !candidate.path("introductionHash").asText().equals(inputs.get(ref.candidate()))) return false;
        var candidateTags=tags(candidate);
        if(candidateTags.isEmpty() || (!ref.interests().isEmpty() && Collections.disjoint(candidateTags,ref.interests()))) return false;
        if(ref.seed()!=null) {
            var seed=profiles.get(ref.seed());
            return seed!=null && !EMPTY_HASH.equals(inputs.get(ref.seed())) && seed.path("introductionHash").asText().equals(inputs.get(ref.seed()))
                && !Collections.disjoint(tags(seed),candidateTags);
        }
        return true;
    }
    public void save(String kind,long generation,JsonNode proof,Long seed,Set<Long> candidates,String interest) {
        if(proof==null) {
            log.warn("RECOMMENDATION_PROOF_MISSING sourceKind={} generationId={} candidateCount={} visible=false", kind, generation, candidates.size());
            return;
        }
        validateForStorage(proof, seed, candidates, interest);
        jdbc.update("INSERT INTO recommendation_eligibility(source_kind,generation_id,proof) VALUES(?,?,?)",kind,generation,proof.toString());
        warnIfStale(kind, generation, proof);
    }
    /** 소개 지문은 배치가 계산한 값이라 계산 뒤 소개가 바뀌었거나 지문이 틀리면 조회에서 조용히 제외된다. 적재 때 그 수를 남긴다. */
    private void warnIfStale(String kind,long generation,JsonNode proof) {
        var hashes=new HashMap<Long,String>();
        proof.path("profiles").forEach(row -> hashes.put(row.path("creatorId").asLong(), row.path("introductionHash").asText()));
        var inputs=current(hashes.keySet(),false);
        long stale=hashes.entrySet().stream().filter(e -> !e.getValue().equals(inputs.get(e.getKey()))).count();
        if(stale>0) log.warn("RECOMMENDATION_PROOF_STALE sourceKind={} generationId={} staleProfiles={} profiles={}", kind, generation, stale, hashes.size());
    }
    public void validateForStorage(JsonNode proof,Long seed,Set<Long> candidates,String interest) {
        if (proof == null) return; // 구 클라이언트 호환. save에서 반드시 관측 가능한 경고를 남긴다.
        var rows=profiles(proof,taxonomy(proof));
        var expected=new TreeSet<>(candidates); if(seed!=null) expected.add(seed);
        if(!rows.keySet().equals(expected)) throw invalid();
        var inputs = new HashMap<Long,String>();
        rows.forEach((id,row) -> inputs.put(id,row.path("introductionHash").asText()));
        for(long candidate:candidates) if(!matches(new Ref(0,seed,candidate,interest==null?Set.of():Set.of(interest)),rows,inputs)) throw invalid();
    }
    public boolean exists(String kind,long generation) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_eligibility WHERE source_kind=? AND generation_id=?",Long.class,kind,generation)>0;
    }
    public boolean same(String kind,long generation,JsonNode proof) {
        if (proof == null) return !exists(kind, generation);
        var stored=jdbc.queryForList("SELECT CAST(proof AS CHAR) FROM recommendation_eligibility WHERE source_kind=? AND generation_id=?",String.class,kind,generation);
        return stored.size()==1 && parse(stored.getFirst()).equals(parse(proof.toString()));
    }
    public Set<Ref> valid(String kind,List<Ref> refs) {
        if(refs.isEmpty()) return Set.of();
        var payloads=new HashMap<Long,Map<Long,JsonNode>>();
        // 같은 taxonomy를 쓰는 seed가 많아도 분야 코드는 요청당 한 번만 읽는다.
        var taxonomies = new HashMap<String, Set<String>>();
        new NamedParameterJdbcTemplate(jdbc).query("SELECT generation_id,CAST(proof AS CHAR) FROM recommendation_eligibility WHERE source_kind=:kind AND generation_id IN (:ids)",
            Map.of("kind",kind,"ids",refs.stream().map(Ref::generation).distinct().toList()),
            (org.springframework.jdbc.core.RowCallbackHandler) r -> {
                long generation=r.getLong(1);
                String reason="MALFORMED";
                try {
                    var p=parse(r.getString(2));
                    reason="HEADER"; requireHeader(p);
                    reason="TAXONOMY"; var allowed=taxonomy(p,false,taxonomies);
                    reason="PROFILES"; payloads.put(generation,profiles(p,allowed));
                } catch(BusinessException e) {
                    log.warn("RECOMMENDATION_PROOF_REJECTED sourceKind={} generationId={} reason={} visible=false", kind, generation, reason);
                }
            });
        var ids=new HashSet<Long>();
        refs.forEach(ref -> { ids.add(ref.candidate()); if (ref.seed()!=null) ids.add(ref.seed()); });
        var inputs=current(ids,false); var result=new HashSet<Ref>();
        for(var ref:refs) if(matches(ref,payloads.getOrDefault(ref.generation(),Map.of()),inputs)) result.add(ref);
        return result;
    }
}

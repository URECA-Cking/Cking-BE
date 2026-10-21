package kr.co.cking.creator.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.ContentIntro;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.SavedTagRecommendationRepository;
import kr.co.cking.creator.repository.SavedTagRecommendationRepository.Confirmed;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static kr.co.cking.creator.application.ContentTagService.*;
import static kr.co.cking.creator.domain.ContentTagErrorCode.*;

/** 저장 태그는 후보 자격, 임베딩은 순위다. 미준비/부족 결과를 인기순으로 보충하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class SavedTagRecommendationService {
    public static final String METHOD="SAVED_TAG_M2_V1";
    private final ContentTagService content;
    private final SavedTagRecommendationRepository repository;
    private final CreatorRepository creators;
    private final CreatorFollowQueryService follows;
    public record Tag(String type,String code,String name,String description) { }
    public record Score(String type,String code,long creatorId,BigDecimal score) { }
    public record Delivery(long applicationSequence,String snapshotHash,String method,String modelVersion,List<Score> scores) { }
    private record Snapshot(String version,String hash,String snapshotHash,List<Tag> tags,List<Confirmed> rows,Map<String,Object> wire) { }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SavedTagRecommendationService.class);
    private record Catalog(String version, String hash, String config, List<Tag> tags) { }

    private static void requireScore(boolean valid, String reason) {
        if (!valid) {
            log.warn("Saved tag ranking rejected: {}", reason);
            throw new BusinessException(INVALID_CONTENT_TAG, "저장 태그 추천 점수 오류: " + reason);
        }
    }

    private static void require(boolean yes) { if (!yes) throw new BusinessException(INVALID_CONTENT_TAG); }
    private static List<String> codes(JsonNode tags,String field) {
        require(tags.path(field).isArray());
        var result=new TreeSet<String>();
        for (JsonNode n:tags.path(field)) { require(n.isTextual() && result.add(n.asText())); }
        return List.copyOf(result);
    }
    private Catalog catalogData() {
        var tax=content.taxonomy(false);
        JsonNode taxonomy=node(tax.snapshot());
        var tags=new ArrayList<Tag>();
        // 콘텐츠 taxonomy는 관심 분야 taxonomy와 독립이다. 상위 설명은 해당 세부 정의로 구성한다.
        for (JsonNode parent:taxonomy.path("parents")) {
            var definitions=new ArrayList<String>();
            for (JsonNode sub:taxonomy.path("subtopics"))
                if (sub.path("parent").asText().equals(parent.asText())) definitions.add(sub.path("definition").asText());
            tags.add(new Tag("PARENT",parent.asText(),parent.asText(),definitions.isEmpty()?parent.asText():String.join("\n",definitions)));
        }
        for (JsonNode sub:taxonomy.path("subtopics")) tags.add(new Tag("TOPIC",sub.path("code").asText(),
                sub.path("name").asText(),sub.path("definition").asText()));
        tags.sort(Comparator.comparing(Tag::type).thenComparing(Tag::code));
        return new Catalog(tax.version(), tax.taxonomyHash(), tax.configHash(), List.copyOf(tags));
    }

    public Map<String, Object> catalog() {
        var catalog = catalogData();
        return Map.of("taxonomyVersion", catalog.version(), "taxonomyHash", catalog.hash(),
            "tags", catalog.tags(), "maxParents", 3, "maxTopics", 9);
    }

    /** A corrupt stored confirmation excludes one Creator, never the whole read. */
    private boolean eligible(Confirmed row, Catalog catalog) {
        try {
            String intro = ContentIntro.normalize(row.introduction());
            String hash = ContentIntro.hash(intro);
            if (intro.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                || row.revision() != row.automaticInputRevision() || row.revision() != row.confirmedInputRevision()
                || !hash.equals(row.inputHash()) || !hash.equals(row.confirmedInputHash())
                || !catalog.config().equals(row.configHash()) || !catalog.hash().equals(row.taxonomyHash())) return false;
            var saved = node(row.tags());
            var parents = codes(saved, "parents");
            var topics = codes(saved, "topics");
            var allowed = catalog.tags().stream().map(t -> t.type() + ":" + t.code()).collect(java.util.stream.Collectors.toSet());
            return !(parents.isEmpty() && topics.isEmpty())
                && parents.stream().allMatch(c -> allowed.contains("PARENT:" + c))
                && topics.stream().allMatch(c -> allowed.contains("TOPIC:" + c));
        } catch (BusinessException | IllegalArgumentException error) {
            log.warn("Skipping invalid saved tag confirmation for Creator {}", row.creatorId());
            return false;
        }
    }

    private Snapshot snapshot() {
        var catalog = catalogData();
        var tags = catalog.tags();
        var rows=new ArrayList<Confirmed>();
        var inputs=new ArrayList<Map<String,Object>>();
        for(var row:repository.confirmed()) {
            if (!eligible(row, catalog)) continue;
            String intro = ContentIntro.normalize(row.introduction());
            JsonNode saved = node(row.tags());
            var parents = codes(saved, "parents");
            var topics = codes(saved, "topics");
            rows.add(row);
            inputs.add(Map.of("creatorId",row.creatorId(),"introduction",intro,"inputRevision",row.revision(),
                    "confirmationId",row.confirmationId(),"parents",parents,"topics",topics));
        }
        var identity=new LinkedHashMap<String,Object>();
        identity.put("contractVersion","saved-tags-v1");
        identity.put("taxonomyVersion",catalog.version());
        identity.put("taxonomyHash",catalog.hash());
        identity.put("generationConfigHash",catalog.config());
        // Record 속성 순서는 Map 키 정렬 옵션의 대상이 아니다. Python sort_keys와 동일한 Map으로 고정한다.
        identity.put("tags",tags.stream().map(t->Map.of("type",t.type(),"code",t.code(),"name",t.name(),"description",t.description())).toList());
        identity.put("items",inputs);
        String hash=digest(identity);
        identity.put("snapshotHash",hash);
        return new Snapshot(catalog.version(),catalog.hash(),hash,List.copyOf(tags),List.copyOf(rows),identity);
    }
    public Map<String,Object> inputs() {
        var result=new LinkedHashMap<>(snapshot().wire());
        result.put("lastApplicationSequence",repository.ranking(false).sequence());
        return result;
    }

    @Transactional
    public Map<String,Object> apply(Delivery delivery) {
        var old=repository.ranking(true); // 첫 읽기로 잠가 동시 적재를 직렬화한다.
        require(delivery!=null && delivery.applicationSequence()>0 && METHOD.equals(delivery.method())
                && delivery.modelVersion()!=null && !delivery.modelVersion().isBlank() && delivery.modelVersion().length()<=255
                && delivery.snapshotHash()!=null && delivery.snapshotHash().matches("[0-9a-f]{64}") && delivery.scores()!=null);
        String hash=digest(delivery);
        if (delivery.applicationSequence()==old.sequence()) {
            if (!hash.equals(old.payloadHash())) throw new BusinessException(CONTENT_PAYLOAD_CONFLICT);
            return Map.of("applied",false,"applicationSequence",old.sequence(),"snapshotHash",delivery.snapshotHash());
        }
        if(delivery.applicationSequence()<old.sequence()) throw new BusinessException(CONTENT_INPUT_CONFLICT);
        var snapshot=snapshot();
        if(!snapshot.snapshotHash().equals(delivery.snapshotHash())) throw new BusinessException(CONTENT_INPUT_CONFLICT);
        Set<String> expected=new HashSet<>();
        for(var row:snapshot.rows()) {
            var saved=node(row.tags());
            codes(saved,"parents").forEach(c->expected.add(key("PARENT",c,row.creatorId())));
            codes(saved,"topics").forEach(c->expected.add(key("TOPIC",c,row.creatorId())));
        }
        Set<String> seen = new HashSet<>();
        for (var score : delivery.scores()) {
            requireScore(score != null && score.score() != null, "SCORE_REQUIRED");
            requireScore(score.score().scale() <= 8 && score.score().compareTo(BigDecimal.ONE) <= 0
                && score.score().compareTo(BigDecimal.ONE.negate()) >= 0, "SCORE_OUT_OF_RANGE_OR_PRECISION");
            String pair = key(score.type(), score.code(), score.creatorId());
            requireScore(seen.add(pair), "DUPLICATE_PAIR");
            requireScore(expected.remove(pair), "UNREGISTERED_PAIR");
        }
        requireScore(expected.isEmpty(), "MISSING_PAIR");
        repository.replaceScores(delivery.scores(), snapshot.rows(), snapshot.hash(),
            (String) snapshot.wire().get("generationConfigHash"), delivery.applicationSequence());
        repository.replace(delivery.applicationSequence(),delivery.snapshotHash(),hash,delivery.modelVersion(),encode(delivery.scores()));
        return Map.of("applied",true,"applicationSequence",delivery.applicationSequence(),"snapshotHash",delivery.snapshotHash());
    }
    private static String key(String type,String code,long creator) { return type+":"+code+":"+creator; }

    public Map<String,Object> find(long memberId,String version,List<String> parentCodes,List<String> topicCodes,int size) {
        require(size>=1 && size<=20 && parentCodes!=null && topicCodes!=null
                && parentCodes.size()<=3 && topicCodes.size()<=9
                && new HashSet<>(parentCodes).size()==parentCodes.size() && new HashSet<>(topicCodes).size()==topicCodes.size());
        if(parentCodes.isEmpty() && topicCodes.isEmpty()) return result("READY",List.of());
        var catalogData = catalogData();
        require(catalogData.version().equals(version));
        var selected = new HashSet<String>();
        parentCodes.forEach(c -> selected.add("PARENT:" + c));
        topicCodes.forEach(c -> selected.add("TOPIC:" + c));
        var catalog = new HashMap<String, Tag>();
        catalogData.tags().forEach(t -> catalog.put(t.type() + ":" + t.code(), t));
        require(catalog.keySet().containsAll(selected));
        var excluded = new HashSet<>(follows.findFollowedCreatorIds(memberId));
        creators.findByMemberId(memberId).ifPresent(c -> excluded.add(c.getCreatorId()));
        var candidates = repository.candidates(parentCodes, topicCodes, catalogData.hash(), catalogData.config()).stream()
            .filter(c -> !excluded.contains(c.row().creatorId()) && eligible(c.row(), catalogData))
            .filter(c -> !matches(c.row(), selected, catalog).isEmpty()).toList();
        var scores = new HashMap<Long, BigDecimal>();
        candidates.stream().filter(c -> c.score() != null).forEach(c -> scores.put(c.row().creatorId(), c.score()));
        var eligible = candidates.stream().map(c -> c.row()).filter(r -> scores.containsKey(r.creatorId())).toList();
        if (eligible.isEmpty()) return result(candidates.isEmpty() ? "READY" : "PENDING", List.of());
        var items=eligible.stream().sorted(Comparator.<Confirmed,BigDecimal>comparing(r->scores.get(r.creatorId())).reversed()
                .thenComparingLong(Confirmed::creatorId)).limit(size).map(r->{
                    var item=new LinkedHashMap<String,Object>();
                    item.put("creatorId",r.creatorId());
                    item.put("creatorName",r.name());
                    item.put("introText",r.introduction());
                    item.put("profileImageUrl",r.image());
                    item.put("slug",r.slug());
                    item.put("score",scores.get(r.creatorId()));
                    item.put("matchedTags",matches(r,selected,catalog));
                    return item;
                }).toList();
        return result("READY",items);
    }
    private static List<Tag> matches(Confirmed row,Set<String> selected,Map<String,Tag> catalog) {
        var saved=node(row.tags());
        var keys=new TreeSet<String>();
        codes(saved,"parents").forEach(c->keys.add("PARENT:"+c));
        codes(saved,"topics").forEach(c->keys.add("TOPIC:"+c));
        keys.retainAll(selected);
        return keys.stream().map(catalog::get).toList();
    }
    private static Map<String,Object> result(String status,List<?> items) {
        // 이 API는 아직 행동 수집 snapshot을 만들지 않는다. FE는 다른 응답 ID를 재사용하면 안 된다.
        var result=new LinkedHashMap<String,Object>();
        result.put("policyVersion",METHOD);
        result.put("rankingStatus",status);
        result.put("recommendationRequestId",null);
        result.put("items",items);
        return result;
    }
}

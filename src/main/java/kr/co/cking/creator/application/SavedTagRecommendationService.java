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

    private static void require(boolean yes) { if (!yes) throw new BusinessException(INVALID_CONTENT_TAG); }
    private static List<String> codes(JsonNode tags,String field) {
        require(tags.path(field).isArray());
        var result=new TreeSet<String>();
        for (JsonNode n:tags.path(field)) { require(n.isTextual() && result.add(n.asText())); }
        return List.copyOf(result);
    }
    private Snapshot snapshot() {
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
        Set<String> allowedParents=new HashSet<>(),allowedTopics=new HashSet<>();
        tags.forEach(t -> { if(t.type().equals("PARENT")) allowedParents.add(t.code()); else allowedTopics.add(t.code()); });
        var rows=new ArrayList<Confirmed>();
        var inputs=new ArrayList<Map<String,Object>>();
        for(var row:repository.confirmed()) {
            String intro=ContentIntro.normalize(row.introduction()), hash=ContentIntro.hash(intro);
            if (intro.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c))
                    || row.revision()!=row.automaticInputRevision() || row.revision()!=row.confirmedInputRevision()
                    || !hash.equals(row.inputHash()) || !hash.equals(row.confirmedInputHash())
                    || !tax.configHash().equals(row.configHash()) || !tax.taxonomyHash().equals(row.taxonomyHash())) continue;
            JsonNode saved=node(row.tags());
            var parents=codes(saved,"parents"); var topics=codes(saved,"topics");
            if (!allowedParents.containsAll(parents) || !allowedTopics.containsAll(topics)) continue;
            if (parents.isEmpty() && topics.isEmpty()) continue;
            rows.add(row);
            inputs.add(Map.of("creatorId",row.creatorId(),"introduction",intro,"inputRevision",row.revision(),
                    "confirmationId",row.confirmationId(),"parents",parents,"topics",topics));
        }
        var identity=new LinkedHashMap<String,Object>();
        identity.put("contractVersion","saved-tags-v1"); identity.put("taxonomyVersion",tax.version());
        identity.put("taxonomyHash",tax.taxonomyHash()); identity.put("generationConfigHash",tax.configHash());
        // Record 속성 순서는 Map 키 정렬 옵션의 대상이 아니다. Python sort_keys와 동일한 Map으로 고정한다.
        identity.put("tags",tags.stream().map(t->Map.of("type",t.type(),"code",t.code(),"name",t.name(),"description",t.description())).toList());
        identity.put("items",inputs);
        String hash=digest(identity);
        identity.put("snapshotHash",hash);
        return new Snapshot(tax.version(),tax.taxonomyHash(),hash,List.copyOf(tags),List.copyOf(rows),identity);
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
        for(var score:delivery.scores()) {
            require(score!=null && score.score()!=null && score.score().scale()<=8
                    && score.score().compareTo(BigDecimal.ONE)<=0 && score.score().compareTo(BigDecimal.ONE.negate())>=0
                    && expected.remove(key(score.type(),score.code(),score.creatorId())));
        }
        require(expected.isEmpty()); // 조회 후보 부족은 허용하되 전체 적격 snapshot의 점수 누락은 거부한다.
        repository.replace(delivery.applicationSequence(),delivery.snapshotHash(),hash,delivery.modelVersion(),encode(delivery.scores()));
        return Map.of("applied",true,"applicationSequence",delivery.applicationSequence(),"snapshotHash",delivery.snapshotHash());
    }
    private static String key(String type,String code,long creator) { return type+":"+code+":"+creator; }

    public Map<String,Object> find(long memberId,String version,List<String> parentCodes,List<String> topicCodes,int size) {
        require(size>=1 && size<=20 && parentCodes!=null && topicCodes!=null
                && parentCodes.size()<=3 && topicCodes.size()<=9
                && new HashSet<>(parentCodes).size()==parentCodes.size() && new HashSet<>(topicCodes).size()==topicCodes.size());
        if(parentCodes.isEmpty() && topicCodes.isEmpty()) return result("READY",List.of());
        var snapshot=snapshot();
        require(snapshot.version().equals(version));
        var selected=new HashSet<String>();
        parentCodes.forEach(c->selected.add("PARENT:"+c)); topicCodes.forEach(c->selected.add("TOPIC:"+c));
        var catalog=new HashMap<String,Tag>(); snapshot.tags().forEach(t->catalog.put(t.type()+":"+t.code(),t));
        require(catalog.keySet().containsAll(selected));
        var excluded=new HashSet<>(follows.findFollowedCreatorIds(memberId));
        creators.findByMemberId(memberId).ifPresent(c->excluded.add(c.getCreatorId()));
        var eligible=snapshot.rows().stream().filter(r->!excluded.contains(r.creatorId()))
                .filter(r->!matches(r,selected,catalog).isEmpty()).toList();
        if(eligible.isEmpty()) return result("READY",List.of());
        var ranking=repository.ranking(false);
        if(!snapshot.snapshotHash().equals(ranking.snapshotHash())) return result("PENDING",List.of());
        var scores=new HashMap<Long,BigDecimal>();
        for(JsonNode score:node(ranking.scores()))
            if(selected.contains(score.path("type").asText()+":"+score.path("code").asText()))
                scores.merge(score.path("creatorId").asLong(),score.path("score").decimalValue(),BigDecimal::max);
        if(eligible.stream().anyMatch(r->!scores.containsKey(r.creatorId()))) return result("PENDING",List.of());
        var items=eligible.stream().sorted(Comparator.<Confirmed,BigDecimal>comparing(r->scores.get(r.creatorId())).reversed()
                .thenComparingLong(Confirmed::creatorId)).limit(size).map(r->{
                    var item=new LinkedHashMap<String,Object>();
                    item.put("creatorId",r.creatorId()); item.put("creatorName",r.name()); item.put("introText",r.introduction());
                    item.put("profileImageUrl",r.image()); item.put("slug",r.slug());
                    item.put("score",scores.get(r.creatorId())); item.put("matchedTags",matches(r,selected,catalog));
                    return item;
                }).toList();
        return result("READY",items);
    }
    private static List<Tag> matches(Confirmed row,Set<String> selected,Map<String,Tag> catalog) {
        var saved=node(row.tags()); var keys=new TreeSet<String>();
        codes(saved,"parents").forEach(c->keys.add("PARENT:"+c)); codes(saved,"topics").forEach(c->keys.add("TOPIC:"+c));
        keys.retainAll(selected); return keys.stream().map(catalog::get).toList();
    }
    private static Map<String,Object> result(String status,List<?> items) {
        // 이 API는 아직 행동 수집 snapshot을 만들지 않는다. FE는 다른 응답 ID를 재사용하면 안 된다.
        var result=new LinkedHashMap<String,Object>(); result.put("policyVersion",METHOD);
        result.put("rankingStatus",status); result.put("recommendationRequestId",null); result.put("items",items); return result;
    }
}

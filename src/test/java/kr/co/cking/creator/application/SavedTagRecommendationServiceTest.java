package kr.co.cking.creator.application;

import java.math.BigDecimal;
import java.util.*;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.ContentIntro;
import kr.co.cking.creator.repository.*;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static kr.co.cking.creator.application.ContentTagService.*;
import static kr.co.cking.creator.application.SavedTagRecommendationService.*;

class SavedTagRecommendationServiceTest {
    final ContentTagService content=mock(ContentTagService.class);
    final SavedTagRecommendationRepository repo=mock(SavedTagRecommendationRepository.class);
    final CreatorRepository creators=mock(CreatorRepository.class);
    final CreatorFollowQueryService follows=mock(CreatorFollowQueryService.class);
    final kr.co.cking.interest.application.MemberInterestService interests=mock(kr.co.cking.interest.application.MemberInterestService.class);
    final SavedTagRecommendationService service=new SavedTagRecommendationService(content,repo,creators,follows,interests);
    final String taxHash="a".repeat(64), config="b".repeat(64);
    SavedTagRecommendationServiceTest() {
        var snapshot=Map.of("parents",List.of("FOOD","GAME"),"subtopics",List.of(
                Map.of("code","FOOD_BAKING","parent","FOOD","name","베이킹","definition","빵 만들기"),
                Map.of("code","FOOD_EATING","parent","FOOD","name","먹방","definition","먹방")));
        when(content.taxonomy(false)).thenReturn(new ContentTagRepository.Taxonomy("v1",taxHash,"c".repeat(64),config,"[]","{}",encode(snapshot)));
        when(repo.ranking(false)).thenReturn(new SavedTagRecommendationRepository.Ranking(0,null,null,null,null));
        when(repo.ranking(true)).thenReturn(new SavedTagRecommendationRepository.Ranking(0,null,null,null,null));
        when(repo.candidates(anyList(), anyList(), anyString(), anyString())).thenAnswer(invocation ->
            repo.confirmed().stream().map(r -> new SavedTagRecommendationRepository.Candidate(r, null)).toList());
        when(creators.findByMemberId(7L)).thenReturn(Optional.empty());
        when(follows.findFollowedCreatorIds(7L)).thenReturn(List.of());
        when(interests.findMine(7L)).thenReturn(new kr.co.cking.interest.application.dto.MemberInterests("interest-v2",List.of()));
    }
    SavedTagRecommendationRepository.Confirmed row(long id,String intro,long confirmedRevision,String taxonomy) {
        return new SavedTagRecommendationRepository.Confirmed(id,intro,1,"크리에이터","image","slug",id,1,
                ContentIntro.hash(intro),config,confirmedRevision,ContentIntro.hash(intro),taxonomy,
                encode(Map.of("parents",List.of("FOOD"),"topics",List.of("FOOD_BAKING"))));
    }
    Delivery delivery(List<Score> scores,long sequence) {
        return new Delivery(sequence,(String)service.inputs().get("snapshotHash"),METHOD,"fake",scores);
    }
    void ready(List<Score> scores) {
        var identities = new HashMap<Long, SavedTagRecommendationRepository.Confirmed>();
        repo.confirmed().forEach(r -> identities.put(r.creatorId(), r));
        doAnswer(invocation -> {
            List<String> parents = invocation.getArgument(0), topics = invocation.getArgument(1);
            return repo.confirmed().stream().map(r -> {
                var original = identities.get(r.creatorId());
                BigDecimal score = original == null || original.revision() != r.revision()
                    || original.confirmationId() != r.confirmationId() ? null : scores.stream()
                    .filter(v -> v.creatorId() == r.creatorId() && (v.type().equals("PARENT") ? parents : topics).contains(v.code()))
                    .map(Score::score).max(BigDecimal::compareTo).orElse(null);
                return new SavedTagRecommendationRepository.Candidate(r, score);
            }).toList();
        }).when(repo).candidates(anyList(), anyList(), anyString(), anyString());
    }
    @Test void fewerThanRequestedAndExactTopicOnly() {
        when(repo.confirmed()).thenReturn(List.of(row(21,"빵 소개",1,taxHash),row(22,"빵 소개",1,taxHash)));
        ready(List.of(new Score("TOPIC","FOOD_BAKING",21,new BigDecimal("0.2")),new Score("TOPIC","FOOD_BAKING",22,new BigDecimal("0.8"))));
        var result=service.find(7,"v1",List.of(),List.of("FOOD_BAKING"),20);
        assertThat(node(result).path("items")).hasSize(2);
        assertThat(node(result).path("items").get(0).path("creatorId").asLong()).isEqualTo(22);
        assertThat(node(result).path("items").get(0).path("matchedTags")).hasSize(1);
        assertThat(node(service.find(7,"v1",List.of(),List.of("FOOD_EATING"),20)).path("items")).isEmpty();
    }
    @Test void orDeduplicatesAndOnlyShowsIntersection() {
        when(repo.confirmed()).thenReturn(List.of(row(21,"빵 소개",1,taxHash)));
        ready(List.of(new Score("PARENT","FOOD",21,BigDecimal.ZERO),new Score("TOPIC","FOOD_BAKING",21,BigDecimal.ONE)));
        var result=node(service.find(7,"v1",List.of("FOOD","GAME"),List.of("FOOD_BAKING"),20));
        assertThat(result.path("items")).hasSize(1);
        assertThat(result.path("items").get(0).path("matchedTags")).hasSize(2);
        assertThat(result.toString()).doesNotContain("GAME");
    }
    @Test void currentIntroductionAndTaxonomyAreRequired() {
        when(repo.confirmed()).thenReturn(List.of(row(1," ",1,taxHash),row(2,"intro",2,taxHash),row(3,"intro",1,"old")));
        assertThat(node(service.inputs()).path("items")).isEmpty();
        assertThat(node(service.find(7,"v1",List.of("FOOD"),List.of(),20)).path("items")).isEmpty();
    }
    @Test void pendingRankingNeverFallsBack() {
        when(repo.confirmed()).thenReturn(List.of(row(21,"빵 소개",1,taxHash)));
        assertThat(service.find(7,"v1",List.of("FOOD"),List.of(),20)).containsEntry("rankingStatus","PENDING").containsEntry("items",List.of());
    }
    @Test void followsAndSelfAreExcludedBeforeLimit() {
        when(repo.confirmed()).thenReturn(List.of(row(1,"intro",1,taxHash),row(2,"intro",1,taxHash),row(21,"intro",1,taxHash)));
        var self = mock(kr.co.cking.creator.domain.Creator.class);
        when(self.getCreatorId()).thenReturn(2L);
        when(creators.findByMemberId(7L)).thenReturn(Optional.of(self));
        when(follows.findFollowedCreatorIds(7L)).thenReturn(List.of(1L));
        ready(List.of(new Score("PARENT","FOOD",1,BigDecimal.ONE),new Score("PARENT","FOOD",2,BigDecimal.ONE),new Score("PARENT","FOOD",21,BigDecimal.ZERO)));
        assertThat(node(service.find(7,"v1",List.of("FOOD"),List.of(),1)).path("items").get(0).path("creatorId").asLong()).isEqualTo(21);
    }
    @Test void duplicateVersionAndSizeAreValidated() {
        assertThatThrownBy(()->service.find(7,"bad",List.of("FOOD"),List.of(),20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.find(7,"v1",List.of("FOOD","FOOD"),List.of(),20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.find(7,"v1",List.of(),List.of(),21)).isInstanceOf(BusinessException.class);
        assertThat(service.find(7,null,List.of(),List.of(),20)).containsEntry("items",List.of());
    }
    @Test void partialOrUnrelatedScoresAreRejected() {
        when(repo.confirmed()).thenReturn(List.of(row(21,"intro",1,taxHash)));
        assertThatThrownBy(()->service.apply(delivery(List.of(),1))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.apply(delivery(List.of(new Score("PARENT","GAME",21,BigDecimal.ONE)),1))).isInstanceOf(BusinessException.class);
        verify(repo,never()).replace(anyLong(),anyString(),anyString(),anyString(),anyString());
    }
    @Test void fullScoresCommitEmptySnapshotIsAlsoValidAndRetriesAreIdempotent() {
        var payload=delivery(List.of(),1);
        assertThat(service.apply(payload)).containsEntry("applied",true);
        when(repo.ranking(true)).thenReturn(new SavedTagRecommendationRepository.Ranking(1,payload.snapshotHash(),digest(payload),"fake","[]"));
        assertThat(service.apply(payload)).containsEntry("applied",false);
        assertThatThrownBy(()->service.apply(new Delivery(1,payload.snapshotHash(),METHOD,"different",List.of()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.apply(new Delivery(0,payload.snapshotHash(),METHOD,"fake",List.of()))).isInstanceOf(BusinessException.class);
    }
    @Test void confirmationChangeInvalidatesRankingWithoutIntroChange() {
        when(repo.confirmed()).thenReturn(List.of(row(21,"intro",1,taxHash)));
        ready(List.of(new Score("PARENT", "FOOD", 21, BigDecimal.ONE)));
        when(repo.confirmed()).thenReturn(List.of(row(22,"intro",1,taxHash)));
        assertThat(service.find(7,"v1",List.of("FOOD"),List.of(),20)).containsEntry("rankingStatus","PENDING");
    }
    @Test void completeScoresAreAcceptedButDuplicatesAndOutOfRangeAreRejected() {
        when(repo.confirmed()).thenReturn(List.of(row(21,"intro",1,taxHash)));
        var parent=new Score("PARENT","FOOD",21,BigDecimal.ZERO);
        var topic=new Score("TOPIC","FOOD_BAKING",21,new BigDecimal("0.81000000"));
        assertThat(service.apply(delivery(List.of(parent,topic),1))).containsEntry("applied",true);
        assertThatThrownBy(()->service.apply(delivery(List.of(parent,parent,topic),2))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.apply(delivery(List.of(parent,new Score("TOPIC","FOOD_BAKING",21,new BigDecimal("1.1"))),2)))
                .isInstanceOf(BusinessException.class);
    }
    @Test void staleSequenceAndChangedSnapshotAreRejected() {
        when(repo.ranking(true)).thenReturn(new SavedTagRecommendationRepository.Ranking(5,"old","old","fake","[]"));
        assertThatThrownBy(()->service.apply(delivery(List.of(),4))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.apply(new Delivery(6,"a".repeat(64),METHOD,"fake",List.of()))).isInstanceOf(BusinessException.class);
        verify(repo,never()).replace(anyLong(),anyString(),anyString(),anyString(),anyString());
    }

    @Test void catalogNeverReadsCandidatesOrRanking() {
        clearInvocations(repo);
        assertThat(service.catalog()).containsEntry("taxonomyVersion", "v1");
        verifyNoInteractions(repo);
    }

    @Test void unrelatedAndNewCandidateDoNotInvalidateExistingScores() {
        when(repo.confirmed()).thenReturn(List.of(row(21, "intro", 1, taxHash)));
        ready(List.of(new Score("PARENT", "FOOD", 21, BigDecimal.ONE)));
        when(repo.confirmed()).thenReturn(List.of(row(21, "intro", 1, taxHash), row(22, "new intro", 1, taxHash)));
        var result = service.find(7, "v1", List.of("FOOD"), List.of(), 20);
        assertThat(result).containsEntry("rankingStatus", "READY");
        assertThat(node(result).path("items")).hasSize(1);
        verify(repo, never()).ranking(false);
    }

    @Test void corruptStoredTagsOnlyExcludeThatCreator() {
        var original = row(22, "intro", 1, taxHash);
        for (String tags : List.of("{\"parents\":[\"FOOD\",\"FOOD\"],\"topics\":[]}",
                "{\"parents\":[3],\"topics\":[]}", "bad-json")) {
            var corrupt = new SavedTagRecommendationRepository.Confirmed(22, original.introduction(), 1,
                original.name(), original.image(), original.slug(), 22, 1, original.inputHash(), config,
                1, original.confirmedInputHash(), taxHash, tags);
            when(repo.confirmed()).thenReturn(List.of(row(21, "intro", 1, taxHash), corrupt));
            assertThat(node(service.inputs()).path("items")).hasSize(1);
            assertThat(service.catalog()).containsEntry("taxonomyVersion", "v1");
        }
    }

    @Test void snapshotHashMatchesPythonGoldenVector() {
        when(repo.confirmed()).thenReturn(List.of(row(21, "빵\r\n소개 e\u0301", 1, taxHash)));
        assertThat(service.inputs().get("snapshotHash")).isEqualTo("b3543714ded21a34f8f28972379229c7bed9d543fbd77c2bd7076d0e37d1b333");
    }

    @Test void memberInterestUsesSavedParentOnlyAndNeverFallsBackToFollowOrPopularity() {
        when(interests.findMine(7L)).thenReturn(new kr.co.cking.interest.application.dto.MemberInterests("interest-v2",List.of("FOOD")));
        when(follows.findFollowedCreatorIds(7L)).thenReturn(List.of(90L));
        when(repo.confirmed()).thenReturn(List.of(row(21,"빵 소개",1,taxHash),tagged(22,"GAME",null),row(23," ",1,taxHash)));
        ready(List.of(new Score("PARENT","FOOD",21,new BigDecimal("0.2")),new Score("PARENT","GAME",22,BigDecimal.ONE)));
        var result=node(service.forMember(7,"INTEREST",20));
        assertThat(result.path("policyVersion").asText()).isEqualTo("SAVED_TAG_INTEREST_V1");
        assertThat(result.path("items")).hasSize(1);
        assertThat(result.path("items").get(0).path("interestCodes").get(0).asText()).isEqualTo("FOOD");
        assertThat(result.path("items").get(0).path("seedCreatorIds")).isEmpty();
        when(interests.findMine(7L)).thenReturn(new kr.co.cking.interest.application.dto.MemberInterests("interest-v2",List.of("UNKNOWN")));
        assertThat(node(service.forMember(7,"INTEREST",20)).path("items")).isEmpty();
        when(interests.findMine(7L)).thenReturn(new kr.co.cking.interest.application.dto.MemberInterests("interest-v2",List.of()));
        assertThat(node(service.forMember(7,"INTEREST",20)).path("items")).isEmpty();
    }

    SavedTagRecommendationRepository.Confirmed tagged(long id,String parent,String topic) {
        var r=row(id,"관련 소개",1,taxHash);
        return new SavedTagRecommendationRepository.Confirmed(r.creatorId(),r.introduction(),r.revision(),r.name(),r.image(),r.slug(),
            r.confirmationId(),r.automaticInputRevision(),r.inputHash(),r.configHash(),r.confirmedInputRevision(),r.confirmedInputHash(),r.taxonomyHash(),
            encode(Map.of("parents",List.of(parent),"topics",topic==null?List.of():List.of(topic))));
    }

    @Test void followUsesAllCurrentConfirmedSeedsAndSameParentAllowsSiblingTopics() {
        var seed1=row(1,"빵 소개",1,taxHash);
        var seed2=tagged(2,"GAME",null);
        when(follows.findFollowedCreatorIds(7L)).thenReturn(List.of(1L,2L,3L));
        when(repo.confirmedByIds(List.of(1L,2L,3L))).thenReturn(List.of(seed1,seed2,row(3," ",1,taxHash)));
        when(repo.confirmed()).thenReturn(List.of(seed1,seed2,tagged(21,"FOOD","FOOD_EATING"),tagged(22,"GAME",null)));
        ready(List.of(new Score("PARENT","FOOD",1,BigDecimal.ONE),new Score("PARENT","GAME",2,BigDecimal.ONE),
            new Score("PARENT","FOOD",21,new BigDecimal("0.2")),new Score("PARENT","GAME",22,new BigDecimal("0.9"))));
        var result=node(service.forMember(7,"FOLLOW",20));
        assertThat(result.path("items")).hasSize(2);
        assertThat(result.path("items").get(0).path("creatorId").asLong()).isEqualTo(22);
        assertThat(result.path("items").get(0).path("seedCreatorIds").get(0).asLong()).isEqualTo(2);
        assertThat(result.path("items").get(1).path("seedCreatorIds").get(0).asLong()).isEqualTo(1);
        assertThat(result.path("items").get(1).path("matchedTags").toString()).doesNotContain("FOOD_BAKING");
        assertThat(node(service.forMember(7,"FOLLOW",1)).path("items")).hasSize(1);
    }

    @Test void missingStaleSeedsAndMissingInterestHaveNoAlternativeSource() {
        when(follows.findFollowedCreatorIds(7L)).thenReturn(List.of(1L));
        when(repo.confirmedByIds(List.of(1L))).thenReturn(List.of(row(1,"intro",2,taxHash)));
        assertThat(node(service.forMember(7,"FOLLOW",20)).path("items")).isEmpty();
        assertThat(node(service.forMember(7,"INTEREST",20)).path("items")).isEmpty();
        assertThatThrownBy(()->service.forMember(7,"POPULAR",20)).isInstanceOf(BusinessException.class);
    }
}

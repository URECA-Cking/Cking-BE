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
    final SavedTagRecommendationService service=new SavedTagRecommendationService(content,repo,creators,follows);
    final String taxHash="a".repeat(64), config="b".repeat(64);
    SavedTagRecommendationServiceTest() {
        var snapshot=Map.of("parents",List.of("FOOD","GAME"),"subtopics",List.of(
                Map.of("code","FOOD_BAKING","parent","FOOD","name","베이킹","definition","빵 만들기"),
                Map.of("code","FOOD_EATING","parent","FOOD","name","먹방","definition","먹방")));
        when(content.taxonomy(false)).thenReturn(new ContentTagRepository.Taxonomy("v1",taxHash,"c".repeat(64),config,"[]","{}",encode(snapshot)));
        when(repo.ranking(false)).thenReturn(new SavedTagRecommendationRepository.Ranking(0,null,null,null,null));
        when(repo.ranking(true)).thenReturn(new SavedTagRecommendationRepository.Ranking(0,null,null,null,null));
        when(creators.findByMemberId(7L)).thenReturn(Optional.empty());
        when(follows.findFollowedCreatorIds(7L)).thenReturn(List.of());
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
        String snapshotHash=(String)service.inputs().get("snapshotHash");
        when(repo.ranking(false)).thenReturn(new SavedTagRecommendationRepository.Ranking(1,
                snapshotHash,"hash","fake",encode(scores)));
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
        when(repo.confirmed()).thenReturn(List.of(row(1,"intro",1,taxHash),row(21,"intro",1,taxHash)));
        when(follows.findFollowedCreatorIds(7L)).thenReturn(List.of(1L));
        ready(List.of(new Score("PARENT","FOOD",1,BigDecimal.ONE),new Score("PARENT","FOOD",21,BigDecimal.ZERO)));
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
        String hash=(String)service.inputs().get("snapshotHash");
        when(repo.confirmed()).thenReturn(List.of(row(22,"intro",1,taxHash)));
        when(repo.ranking(false)).thenReturn(new SavedTagRecommendationRepository.Ranking(1,hash,"h","fake","[]"));
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
}

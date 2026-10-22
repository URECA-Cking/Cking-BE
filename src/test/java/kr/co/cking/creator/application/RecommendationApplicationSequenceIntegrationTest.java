package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.dto.CreatorSimilarityResultCommand;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSimilarityCandidateRepository;
import kr.co.cking.interest.application.InterestRecommendationResultService;
import kr.co.cking.interest.application.dto.InterestRecommendationCommand;
import kr.co.cking.interest.domain.InterestCategory;
import kr.co.cking.interest.domain.InterestTaxonomy;
import kr.co.cking.interest.domain.InterestTaxonomyHash;
import kr.co.cking.interest.repository.InterestCategoryRepository;
import kr.co.cking.interest.repository.InterestRecommendationCandidateRepository;
import kr.co.cking.interest.repository.InterestTaxonomyRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 커밋된 fixture만 사용해 두 추천 종류의 부분 적용과 실제 트랜잭션 롤백을 확인한다. */
@SpringBootTest
class RecommendationApplicationSequenceIntegrationTest {
    @Autowired CreatorSimilarityResultService similarity;
    @Autowired InterestRecommendationResultService interests;
    @Autowired CreatorRepository creators;
    @Autowired MemberRepository members;
    @Autowired InterestTaxonomyRepository taxonomies;
    @Autowired InterestCategoryRepository categories;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean CreatorSimilarityCandidateRepository similarityCandidates;
    @MockitoSpyBean InterestRecommendationCandidateRepository interestCandidates;
    private Member member;
    private Creator creator;
    private String version;
    private String taxonomyHash;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        member = members.saveAndFlush(new Member("seq-" + suffix, null, null, MemberRole.USER));
        creator = creators.saveAndFlush(new Creator(member.getMemberId(), "seq-" + suffix));
        version = "s" + suffix;
        taxonomyHash = InterestTaxonomyHash.compute(List.of(new InterestTaxonomyHash.Row("SPORTS", "스포츠", "설명")));
        taxonomies.saveAndFlush(new InterestTaxonomy(version, taxonomyHash, false, Instant.now()));
        categories.saveAndFlush(new InterestCategory(version, "SPORTS", "스포츠", "설명", 1, true));
    }

    @AfterEach
    void cleanUp() {
        reset(similarityCandidates, interestCandidates);
        if (version != null) {
            jdbc.update("delete from interest_recommendation_state where taxonomy_version = ?", version);
            jdbc.update("delete from interest_recommendation_candidate where generation_id in "
                    + "(select generation_id from interest_recommendation_generation where taxonomy_version = ?)", version);
            jdbc.update("delete from interest_recommendation_generation where taxonomy_version = ?", version);
            jdbc.update("delete from interest_category where taxonomy_version = ?", version);
            jdbc.update("delete from interest_taxonomy where taxonomy_version = ?", version);
        }
        if (creator != null) {
            new SimilarityTestCleaner(jdbc).delete(List.of(member), List.of(creator));
            creators.delete(creator);
        }
        if (member != null) members.delete(member);
    }

    @Test
    void B_일부_적용_뒤_A_복귀는_두_대상에서_지연_B를_차단한다() {
        putSimilarity(1, "a");
        putInterest(1, "a");
        putSimilarity(2, "b"); // 관심 분야 B는 아직 도착하지 않았다.
        assertThat(putSimilarity(3, "a").applied()).isTrue();
        assertThat(putInterest(3, "a").applied()).isTrue();
        assertThat(putSimilarity(3, "a").applied()).isFalse();
        assertThat(putInterest(3, "a").applied()).isFalse();
        assertStale(() -> putSimilarity(2, "b"));
        assertStale(() -> putInterest(2, "b")); // 저장된 적 없는 B도 차단한다.
    }

    @Test
    void 후보_저장_실패는_새_세대와_포인터_변경을_함께_롤백한다() {
        long originalSimilarity = putSimilarity(1, "a").generationId();
        long originalInterest = putInterest(1, "a").generationId();
        doThrow(new IllegalStateException("저장 실패 주입")).when(similarityCandidates).saveAll(any());
        doThrow(new IllegalStateException("저장 실패 주입")).when(interestCandidates).saveAll(any());
        assertThatThrownBy(() -> putSimilarity(2, "b")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> putInterest(2, "b")).isInstanceOf(IllegalStateException.class);
        reset(similarityCandidates, interestCandidates);
        assertThat(putSimilarity(1, "a").generationId()).isEqualTo(originalSimilarity);
        assertThat(putInterest(1, "a").generationId()).isEqualTo(originalInterest);
        // 실패한 번호는 DB에 남지 않아 동일 실행으로 재개할 수 있다.
        assertThat(putSimilarity(2, "b").applied()).isTrue();
        assertThat(putInterest(2, "b").applied()).isTrue();
    }

    @Test
    void 다른_실행의_동시_요청은_도착_순서와_무관하게_큰_번호로_수렴한다() throws Exception {
        for (boolean interest : List.of(false, true)) {
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            try {
                var tasks = List.of(1L, 2L).stream().map(sequence -> executor.submit(() -> {
                    start.await();
                    try {
                        if (interest) putInterest(sequence, sequence == 1L ? "a" : "b");
                        else putSimilarity(sequence, sequence == 1L ? "a" : "b");
                    } catch (BusinessException ex) {
                        assertThat(sequence).isEqualTo(1L);
                        assertThat(ex.getErrorCode().code()).isEqualTo("STALE_RECOMMENDATION_INPUT");
                    }
                    return true;
                })).toList();
                start.countDown();
                for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
                if (interest) {
                    assertThat(putInterest(2, "b").applied()).isFalse();
                    assertStale(() -> putInterest(1, "a"));
                } else {
                    assertThat(putSimilarity(2, "b").applied()).isFalse();
                    assertStale(() -> putSimilarity(1, "a"));
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void 삭제된_후보가_있어도_과거_실행은_409이고_새_실행은_404다() {
        long similarityId = putSimilarity(5, "a").generationId();
        long interestId = putInterest(5, "a").generationId();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Member owner = members.saveAndFlush(new Member("removed-" + suffix, null, null, MemberRole.USER));
        Creator removed = creators.saveAndFlush(new Creator(owner.getMemberId(), "removed-" + suffix));
        long removedId = removed.getCreatorId();
        creators.delete(removed);
        members.delete(owner);

        for (long sequence : List.of(3L, 6L)) {
            var similarCommand = new CreatorSimilarityResultCommand(sequence, creator.getCreatorId(), "M4", "model-v1",
                    "b".repeat(64), List.of(new CreatorSimilarityResultCommand.Candidate(creator.getCreatorId(),
                    removedId, new BigDecimal("0.9"), 1, "M4", "model-v1", "b".repeat(64))));
            var interestCommand = new InterestRecommendationCommand(sequence, version, taxonomyHash, "SPORTS",
                    "INTEREST_M3_V1", "model-v1", "b".repeat(64), List.of(new InterestRecommendationCommand.Candidate(
                    "SPORTS", removedId, new BigDecimal("0.9"), 1, "INTEREST_M3_V1", "model-v1", "b".repeat(64))));
            Runnable similarRequest = () -> similarity.replace(creator.getCreatorId(), similarCommand);
            Runnable interestRequest = () -> interests.replace("SPORTS", interestCommand);
            for (Runnable request : List.of(similarRequest, interestRequest)) {
                if (sequence == 3L) assertStale(request);
                else assertThatThrownBy(request::run).isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
            }
        }
        assertThat(putSimilarity(5, "a").generationId()).isEqualTo(similarityId);
        assertThat(putInterest(5, "a").generationId()).isEqualTo(interestId);
    }

    private CreatorSimilarityResultService.StoreResult putSimilarity(long sequence, String hash) {
        return similarity.replace(creator.getCreatorId(), new CreatorSimilarityResultCommand(sequence,
                creator.getCreatorId(), "M4", "model-v1", hash.repeat(64), List.of()));
    }

    private InterestRecommendationResultService.StoreResult putInterest(long sequence, String hash) {
        return interests.replace("SPORTS", new InterestRecommendationCommand(sequence, version, taxonomyHash,
                "SPORTS", "INTEREST_M3_V1", "model-v1", hash.repeat(64), List.of()));
    }

    private void assertStale(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode().code()).isEqualTo("STALE_RECOMMENDATION_INPUT"));
    }
}

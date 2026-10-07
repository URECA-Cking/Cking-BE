package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.co.cking.creator.application.dto.CreatorSimilarityResultCommand;
import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView;
import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView.Item;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.follow.application.CreatorFollowService;
import kr.co.cking.interest.application.InterestRecommendationResultService;
import kr.co.cking.interest.application.dto.InterestRecommendationCommand;
import kr.co.cking.interest.domain.InterestCategory;
import kr.co.cking.interest.domain.InterestTaxonomy;
import kr.co.cking.interest.domain.InterestTaxonomyHash;
import kr.co.cking.interest.domain.InterestTaxonomyHash.Row;
import kr.co.cking.interest.domain.MemberInterest;
import kr.co.cking.interest.repository.InterestCategoryRepository;
import kr.co.cking.interest.repository.InterestTaxonomyRepository;
import kr.co.cking.interest.repository.MemberInterestRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 MySQL로 관심 분야 후보 조회 SQL과 정책 분기를 끝까지 확인한다. 테스트 전용 분류체계를 쓰고 매번 롤백해 공유 DB의
 * 실제 적재 결과와 섞이지 않는다.
 */
@SpringBootTest
@Transactional
class PersonalizedRecommendationInterestIntegrationTest {

    private static final String METHOD = "INTEREST_M3_V1";

    @Autowired CreatorRecommendationQueryService recommendationService;
    @Autowired InterestRecommendationResultService interestResultService;
    @Autowired CreatorSimilarityResultService similarityResultService;
    @Autowired CreatorFollowService followService;
    @Autowired InterestTaxonomyRepository taxonomyRepository;
    @Autowired InterestCategoryRepository categoryRepository;
    @Autowired MemberInterestRepository memberInterestRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired CreatorRepository creatorRepository;
    @Autowired CreatorSpaceRepository spaceRepository;

    private String version;
    private String taxonomyHash;
    private Member fan;
    private Creator first;
    private Creator second;
    private Creator third;

    @BeforeEach
    void setUp() {
        version = newVersion();
        taxonomyHash = registerTaxonomy(version, "설명");
        fan = member("fan");
        first = creatorWithSpace("first");
        second = creatorWithSpace("second");
        third = creatorWithSpace("third");
    }

    @Test
    void 관심_분야만_있으면_INTEREST_정책으로_분야_후보를_추천한다() {
        select(version, "FOOD");
        ingest(version, taxonomyHash, "FOOD", candidate("FOOD", first, "0.9", 1), candidate("FOOD", second, "0.8", 2));

        PersonalizedCreatorRecommendationView result = recommendationService.findForMember(fan.getMemberId(), 10);

        assertThat(result.policyVersion()).isEqualTo("INTEREST_PERSONALIZED_V1");
        assertThat(result.items()).extracting(Item::creatorId).containsExactly(first.getCreatorId(), second.getCreatorId());
        assertThat(result.items().getFirst().aggregateScore()).isEqualByComparingTo("0.01639344");
        assertThat(result.items().getFirst().interestCodes()).containsExactly("FOOD");
        assertThat(result.items().getFirst().seedCreatorIds()).isEmpty();
        assertThat(result.items().get(1).aggregateScore()).isEqualByComparingTo("0.01612903");
    }

    @Test
    void 관심_분야와_팔로우가_모두_유효하면_HYBRID로_섞고_출처를_함께_반환한다() {
        select(version, "FOOD");
        ingest(version, taxonomyHash, "FOOD", candidate("FOOD", first, "0.9", 1), candidate("FOOD", second, "0.8", 2));
        Creator seed = creatorWithSpace("seed");
        followService.follow(fan.getMemberId(), seed.getCreatorId());
        similarity(seed, first, 2, third, 1);

        PersonalizedCreatorRecommendationView result = recommendationService.findForMember(fan.getMemberId(), 10);

        assertThat(result.policyVersion()).isEqualTo("HYBRID_PERSONALIZED_V1");
        // first: 0.5 × 0.01639344(FOOD 1위) + 0.5 × 0.01612903(seed 2위), third: 0.5 × 0.01639344(seed 1위),
        // second: 0.5 × 0.01612903(FOOD 2위)
        assertThat(result.items()).extracting(Item::creatorId)
                .containsExactly(first.getCreatorId(), third.getCreatorId(), second.getCreatorId());
        assertThat(result.items().getFirst().aggregateScore()).isEqualByComparingTo("0.01626124");
        assertThat(result.items().getFirst().interestCodes()).containsExactly("FOOD");
        assertThat(result.items().getFirst().seedCreatorIds()).containsExactly(seed.getCreatorId());
        assertThat(result.items().get(1).interestCodes()).isEmpty();
        assertThat(result.items().get(1).seedCreatorIds()).containsExactly(seed.getCreatorId());
        assertThat(result.items().get(2).seedCreatorIds()).isEmpty();
    }

    @Test
    void 선택하지_않은_분야의_활성_후보는_쓰지_않는다() {
        select(version, "FOOD");
        ingest(version, taxonomyHash, "FITNESS", candidate("FITNESS", first, "0.9", 1));

        PersonalizedCreatorRecommendationView result = recommendationService.findForMember(fan.getMemberId(), 10);

        assertThat(result.policyVersion()).isEqualTo("FOLLOW_PERSONALIZED_V2");
        assertThat(result.items()).isEmpty();
    }

    @Test
    void 본인_기팔로우_Space_없는_Creator는_제외하고_남은_후보가_없으면_분야는_유효하지_않다() {
        Creator own = creatorWithSpace(fan, "own");
        Creator followed = creatorWithSpace("followed");
        Creator noSpace = creator("no-space");
        followService.follow(fan.getMemberId(), followed.getCreatorId());
        select(version, "FOOD", "FITNESS");
        ingest(version, taxonomyHash, "FOOD",
                candidate("FOOD", own, "0.9", 1), candidate("FOOD", followed, "0.8", 2), candidate("FOOD", noSpace, "0.7", 3));
        ingest(version, taxonomyHash, "FITNESS", candidate("FITNESS", first, "0.9", 1));

        PersonalizedCreatorRecommendationView result = recommendationService.findForMember(fan.getMemberId(), 10);

        // FOOD는 제외 후 후보가 없어 분모에서 빠지므로 FITNESS 한 분야만 유효하다(0.01639344 그대로).
        // 팔로우한 followed의 seed 후보는 없어 팔로우 그룹은 무효다.
        assertThat(result.policyVersion()).isEqualTo("INTEREST_PERSONALIZED_V1");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().creatorId()).isEqualTo(first.getCreatorId());
        assertThat(result.items().getFirst().aggregateScore()).isEqualByComparingTo("0.01639344");
        assertThat(result.items().getFirst().interestCodes()).containsExactly("FITNESS");
    }

    @Test
    void 빈_세대로_교체된_분야는_유효하지_않아_이전_후보가_남지_않는다() {
        select(version, "FOOD");
        ingest(version, taxonomyHash, "FOOD", candidate("FOOD", first, "0.9", 1));
        interestResultService.replace("FOOD", new InterestRecommendationCommand(2L,
                version, taxonomyHash, "FOOD", METHOD, "model-v1", hash(), List.of()));

        PersonalizedCreatorRecommendationView result = recommendationService.findForMember(fan.getMemberId(), 10);

        assertThat(result.policyVersion()).isEqualTo("FOLLOW_PERSONALIZED_V2");
        assertThat(result.items()).isEmpty();
    }

    @Test
    void 선택한_버전과_다른_분류체계_버전의_후보로_대체하지_않는다() {
        String otherVersion = newVersion();
        String otherHash = registerTaxonomy(otherVersion, "다른 분류 기준문");
        select(version, "FOOD");
        ingest(otherVersion, otherHash, "FOOD", candidate("FOOD", first, "0.9", 1));

        PersonalizedCreatorRecommendationView result = recommendationService.findForMember(fan.getMemberId(), 10);

        assertThat(result.policyVersion()).isEqualTo("FOLLOW_PERSONALIZED_V2");
        assertThat(result.items()).isEmpty();
    }

    @Test
    void 팔로우도_관심_분야도_없으면_V2_정책의_빈_목록이다() {
        PersonalizedCreatorRecommendationView result = recommendationService.findForMember(fan.getMemberId(), 10);

        assertThat(result.policyVersion()).isEqualTo("FOLLOW_PERSONALIZED_V2");
        assertThat(result.items()).isEmpty();
    }

    private void select(String taxonomyVersion, String... codes) {
        for (String code : codes) {
            memberInterestRepository.saveAndFlush(new MemberInterest(fan.getMemberId(), taxonomyVersion, code, Instant.now()));
        }
    }

    private void ingest(
            String taxonomyVersion, String hashOfTaxonomy, String code, InterestRecommendationCommand.Candidate... candidates) {
        String inputHash = hash();
        List<InterestRecommendationCommand.Candidate> withHash = java.util.Arrays.stream(candidates)
                .map(c -> new InterestRecommendationCommand.Candidate(
                        c.interestCode(), c.creatorId(), c.score(), c.rank(), METHOD, "model-v1", inputHash))
                .toList();
        interestResultService.replace(code, new InterestRecommendationCommand(1L,
                taxonomyVersion, hashOfTaxonomy, code, METHOD, "model-v1", inputHash, withHash));
    }

    private InterestRecommendationCommand.Candidate candidate(String code, Creator creator, String score, int rank) {
        return new InterestRecommendationCommand.Candidate(
                code, creator.getCreatorId(), new BigDecimal(score), rank, METHOD, "model-v1", "unused");
    }

    private void similarity(Creator seed, Creator firstCandidate, int firstRank, Creator secondCandidate, int secondRank) {
        // rank와 점수 순서가 일치해야 하므로 rank가 작은 쪽에 높은 점수를 준다.
        String inputHash = hash();
        List<CreatorSimilarityResultCommand.Candidate> candidates = new java.util.ArrayList<>();
        candidates.add(new CreatorSimilarityResultCommand.Candidate(
                seed.getCreatorId(), firstCandidate.getCreatorId(), new BigDecimal(firstRank == 1 ? "0.9" : "0.5"),
                firstRank, "M4", "model-v1", inputHash));
        candidates.add(new CreatorSimilarityResultCommand.Candidate(
                seed.getCreatorId(), secondCandidate.getCreatorId(), new BigDecimal(secondRank == 1 ? "0.9" : "0.5"),
                secondRank, "M4", "model-v1", inputHash));
        candidates.sort(java.util.Comparator.comparing(CreatorSimilarityResultCommand.Candidate::rank));
        similarityResultService.replace(seed.getCreatorId(),
                new CreatorSimilarityResultCommand(1L, seed.getCreatorId(), "M4", "model-v1", inputHash, candidates));
    }

    /** 버전마다 해시가 UNIQUE라 서로 다른 분류체계는 분류 기준문을 다르게 한다. */
    private String registerTaxonomy(String taxonomyVersion, String description) {
        List<Row> rows = List.of(new Row("FOOD", "요리", description), new Row("FITNESS", "운동", description));
        String hash = InterestTaxonomyHash.compute(rows);
        taxonomyRepository.saveAndFlush(new InterestTaxonomy(taxonomyVersion, hash, false, Instant.now()));
        categoryRepository.saveAndFlush(new InterestCategory(taxonomyVersion, "FOOD", "요리", description, 1, true));
        categoryRepository.saveAndFlush(new InterestCategory(taxonomyVersion, "FITNESS", "운동", description, 2, true));
        return hash;
    }

    private Member member(String prefix) {
        return memberRepository.saveAndFlush(new Member(prefix + "-" + suffix(), null, null, MemberRole.USER));
    }

    private Creator creator(String prefix) {
        return creatorRepository.saveAndFlush(new Creator(member("owner-" + prefix).getMemberId(), prefix + "-" + suffix()));
    }

    private Creator creatorWithSpace(String prefix) {
        return withSpace(creator(prefix));
    }

    private Creator creatorWithSpace(Member owner, String prefix) {
        return withSpace(creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), prefix + "-" + suffix())));
    }

    private Creator withSpace(Creator creator) {
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                creator.getMemberId(), "소개", "profile", "banner", "creator-{creatorId}");
        spaceRepository.saveAndFlush(CreatorSpace.fromTemplate(
                creator.getCreatorId(), template, "creator-" + creator.getCreatorId()));
        return creator;
    }

    private String newVersion() {
        return "t" + suffix();
    }

    private String hash() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

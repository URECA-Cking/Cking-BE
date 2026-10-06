package kr.co.cking.creator.application;

import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSimilarityCandidate;
import kr.co.cking.creator.domain.CreatorSimilarityGeneration;
import kr.co.cking.creator.domain.CreatorSimilarityState;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSimilarityCandidateRepository;
import kr.co.cking.creator.repository.CreatorSimilarityGenerationRepository;
import kr.co.cking.creator.repository.CreatorSimilarityStateRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.follow.application.CreatorFollowService;
import kr.co.cking.follow.repository.CreatorFollowRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 MySQL에서 여러 seed의 현재 활성 후보 bulk query와 개인화 집계를 함께 검증한다. */
@SpringBootTest
class CreatorRecommendationIntegrationTest {

    @Autowired CreatorRecommendationQueryService recommendationQueryService;
    @Autowired CreatorFollowService followService;
    @Autowired CreatorFollowRepository followRepository;
    @Autowired CreatorRepository creatorRepository;
    @Autowired CreatorSpaceRepository spaceRepository;
    @Autowired CreatorSimilarityGenerationRepository generationRepository;
    @Autowired CreatorSimilarityCandidateRepository candidateRepository;
    @Autowired CreatorSimilarityStateRepository stateRepository;
    @Autowired MemberRepository memberRepository;

    private final List<Member> members = new ArrayList<>();
    private final List<Creator> creators = new ArrayList<>();
    private final List<CreatorSpace> spaces = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        followRepository.deleteAll();
        stateRepository.deleteAll();
        candidateRepository.deleteAll();
        generationRepository.deleteAll();
        spaceRepository.deleteAll(spaces);
        creatorRepository.deleteAll(creators);
        memberRepository.deleteAll(members);
    }

    @Test
    void 여러_seed의_M2_M4_후보를_합치고_본인과_기팔로우를_제외한다() {
        Member fan = member("fan");
        Creator firstSeed = creator("seed-one");
        Creator secondSeed = creator("seed-two");
        Creator ownCreator = creator(fan, "own");
        Creator repeatedCandidate = creator("repeated");
        Creator singleCandidate = creator("single");
        createSpace(repeatedCandidate, "반복 소개", "repeated-profile");
        createSpace(singleCandidate, "단일 소개", "single-profile");

        followService.follow(fan.getMemberId(), firstSeed.getCreatorId());
        followService.follow(fan.getMemberId(), secondSeed.getCreatorId());
        activate(firstSeed, "M2", List.of(
                candidate(repeatedCandidate, "0.90000000", 1),
                candidate(secondSeed, "0.80000000", 2),
                candidate(ownCreator, "0.70000000", 3)));
        activate(secondSeed, "M4", List.of(
                candidate(singleCandidate, "1.80000000", 1),
                candidate(repeatedCandidate, "-0.50000000", 2)));

        PersonalizedCreatorRecommendationView result =
                recommendationQueryService.findForMember(fan.getMemberId(), 10);

        assertThat(result.items()).containsExactly(
                new PersonalizedCreatorRecommendationView.Item(
                        repeatedCandidate.getCreatorId(),
                        repeatedCandidate.getName(),
                        "반복 소개",
                        "repeated-profile",
                        // 후보가 남는 seed 둘의 기여도 합 0.03252247을 2로 나눈 평균
                        new BigDecimal("0.01626124"),
                        List.of(),
                        List.of(firstSeed.getCreatorId(), secondSeed.getCreatorId())),
                new PersonalizedCreatorRecommendationView.Item(
                        singleCandidate.getCreatorId(),
                        singleCandidate.getName(),
                        "단일 소개",
                        "single-profile",
                        new BigDecimal("0.00819672"),
                        List.of(),
                        List.of(secondSeed.getCreatorId())));
    }

    @Test
    void 일부_seed에_활성_결과가_없어도_다른_seed의_저장_결과를_반환한다() {
        Member fan = member("fan-partial");
        Creator missingSeed = creator("missing-seed");
        Creator activeSeed = creator("active-seed");
        Creator candidate = creator("candidate");
        createSpace(candidate, "후보 소개", "candidate-profile");
        followService.follow(fan.getMemberId(), missingSeed.getCreatorId());
        followService.follow(fan.getMemberId(), activeSeed.getCreatorId());
        activate(activeSeed, "M4", List.of(candidate(candidate, "0.50000000", 1)));

        PersonalizedCreatorRecommendationView result =
                recommendationQueryService.findForMember(fan.getMemberId(), 10);

        assertThat(result.items()).extracting(PersonalizedCreatorRecommendationView.Item::creatorId)
                .containsExactly(candidate.getCreatorId());
    }

    @Test
    void 후보가_없는_빈_세대가_활성화되어도_이전_후보_없이_빈_목록을_반환한다() {
        Member fan = member("fan-empty-generation");
        Creator seed = creator("empty-generation-seed");
        Creator previousCandidate = creator("previous-candidate");
        createSpace(previousCandidate, "이전 후보 소개", "previous-profile");
        followService.follow(fan.getMemberId(), seed.getCreatorId());
        activate(seed, "M4", List.of(candidate(previousCandidate, "0.90000000", 1)));
        activate(seed, "M2", List.of());

        PersonalizedCreatorRecommendationView result =
                recommendationQueryService.findForMember(fan.getMemberId(), 10);

        assertThat(result.policyVersion()).isEqualTo("FOLLOW_PERSONALIZED_V2");
        assertThat(result.items()).isEmpty();
    }

    @Test
    void Space가_없는_상위_후보를_제외한_뒤_카드_반환이_가능한_후보에_size를_적용한다() {
        Member fan = member("fan-missing-space");
        Creator seed = creator("missing-space-seed");
        Creator missingSpaceCandidate = creator("missing-space-candidate");
        Creator availableCandidate = creator("available-candidate");
        createSpace(availableCandidate, "정상 후보 소개", "available-profile");
        followService.follow(fan.getMemberId(), seed.getCreatorId());
        activate(seed, "M4", List.of(
                candidate(missingSpaceCandidate, "0.90000000", 1),
                candidate(availableCandidate, "0.80000000", 2)));

        PersonalizedCreatorRecommendationView result =
                recommendationQueryService.findForMember(fan.getMemberId(), 1);

        assertThat(result.items()).containsExactly(new PersonalizedCreatorRecommendationView.Item(
                availableCandidate.getCreatorId(),
                availableCandidate.getName(),
                "정상 후보 소개",
                "available-profile",
                new BigDecimal("0.01612903"),
                List.of(),
                List.of(seed.getCreatorId())));
    }

    private void activate(Creator seed, String method, List<CandidateSpec> candidateSpecs) {
        CreatorSimilarityGeneration generation = generationRepository.saveAndFlush(
                new CreatorSimilarityGeneration(
                        seed.getCreatorId(), method, "model-v1", hash(), Instant.now()));
        candidateRepository.saveAll(candidateSpecs.stream()
                .map(spec -> new CreatorSimilarityCandidate(
                        generation.getGenerationId(), spec.creator().getCreatorId(), spec.score(), spec.rank()))
                .toList());
        CreatorSimilarityState state = stateRepository.findById(seed.getCreatorId())
                .orElseGet(() -> new CreatorSimilarityState(
                        seed.getCreatorId(), generation.getGenerationId()));
        state.activate(generation.getGenerationId());
        stateRepository.saveAndFlush(state);
    }

    private CandidateSpec candidate(Creator creator, String score, int rank) {
        return new CandidateSpec(creator, new BigDecimal(score), rank);
    }

    private Member member(String prefix) {
        Member member = memberRepository.saveAndFlush(
                new Member(prefix + "-" + suffix(), null, null, MemberRole.USER));
        members.add(member);
        return member;
    }

    private Creator creator(String prefix) {
        return creator(member("owner-" + prefix), prefix);
    }

    private Creator creator(Member owner, String prefix) {
        Creator creator = creatorRepository.saveAndFlush(
                new Creator(owner.getMemberId(), prefix + "-" + suffix()));
        creators.add(creator);
        return creator;
    }

    private void createSpace(Creator creator, String introText, String profileImageUrl) {
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                creator.getMemberId(), introText, profileImageUrl, "banner", "creator-{creatorId}");
        spaces.add(spaceRepository.saveAndFlush(CreatorSpace.fromTemplate(
                creator.getCreatorId(), template, "creator-" + creator.getCreatorId())));
    }

    private String hash() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private record CandidateSpec(Creator creator, BigDecimal score, int rank) {
    }
}

package kr.co.cking.creator.application;

import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSimilarityCandidateRepository;
import kr.co.cking.interest.application.InterestRecommendationQueryService;
import kr.co.cking.interest.repository.ActiveInterestRecommendationCandidate;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.creator.repository.PopularCreatorCandidate;
import kr.co.cking.creator.repository.PopularCreatorQueryRepository;
import kr.co.cking.creator.repository.ActiveCreatorRecommendationCandidate;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

class CreatorRecommendationQueryServiceTest {

    private final CreatorFollowQueryService followQueryService = mock(CreatorFollowQueryService.class);
    private final InterestRecommendationQueryService interestQueryService =
            mock(InterestRecommendationQueryService.class);
    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorSimilarityCandidateRepository candidateRepository =
            mock(CreatorSimilarityCandidateRepository.class);
    private final CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
    private final PopularCreatorQueryRepository popularRepository = mock(PopularCreatorQueryRepository.class);
    private CreatorRecommendationQueryService service;

    @BeforeEach
    void setUp() {
        service = new CreatorRecommendationQueryService(
                followQueryService,
                interestQueryService,
                creatorRepository,
                candidateRepository,
                spaceRepository,
                popularRepository,
                new PersonalizedCreatorRecommendationPolicy());
    }

    @Test
    void 팔로우도_관심_분야도_없으면_팔로우_후보를_조회하지_않고_인기순도_비어_있으면_빈_목록을_반환한다() {
        given(followQueryService.findFollowedCreatorIds(7L)).willReturn(List.of());
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.empty());
        given(interestQueryService.findActiveCandidates(7L)).willReturn(List.of());

        PersonalizedCreatorRecommendationView result = service.findForMember(7L, 10);

        assertThat(result.policyVersion()).isEqualTo("POPULAR_FALLBACK_V1");
        assertThat(result.items()).isEmpty();
        then(candidateRepository).should(never()).findActiveCandidatesBySeedCreatorIds(any());
    }

    @Test
    void 팔로우가_한_명이지만_활성_후보가_없으면_빈_목록을_반환한다() {
        given(followQueryService.findFollowedCreatorIds(7L)).willReturn(List.of(1L));
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.empty());
        given(interestQueryService.findActiveCandidates(7L)).willReturn(List.of());
        given(candidateRepository.findActiveCandidatesBySeedCreatorIds(List.of(1L)))
                .willReturn(List.of());

        PersonalizedCreatorRecommendationView result = service.findForMember(7L, 10);

        assertThat(result.items()).isEmpty();
        then(candidateRepository).should().findActiveCandidatesBySeedCreatorIds(List.of(1L));
        then(creatorRepository).should(never()).findByCreatorIdIn(any());
    }

    @Test
    void 개인화_결과가_없으면_팔로워_수_순_인기_Creator를_본인과_기팔로우를_제외하고_size만큼_채운다() {
        given(followQueryService.findFollowedCreatorIds(7L)).willReturn(List.of(1L));
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator(9L, "own")));
        given(interestQueryService.findActiveCandidates(7L)).willReturn(List.of());
        given(candidateRepository.findActiveCandidatesBySeedCreatorIds(List.of(1L))).willReturn(List.of());
        // 제외 대상(기팔로우 1, 본인 9)을 읽은 뒤에 거르므로 size + 제외 수만큼 읽는다.
        given(popularRepository.findPopular(PageRequest.of(0, 4))).willReturn(List.of(
                new PopularCreatorCandidate(9L, "own", "소개9", "p9", 12),
                new PopularCreatorCandidate(1L, "followed", "소개1", "p1", 9),
                new PopularCreatorCandidate(2L, "first", "소개2", "p2", 5),
                new PopularCreatorCandidate(3L, "second", "소개3", "p3", 0)));

        PersonalizedCreatorRecommendationView result = service.findForMember(7L, 2);

        assertThat(result.policyVersion()).isEqualTo("POPULAR_FALLBACK_V1");
        assertThat(result.items()).extracting(PersonalizedCreatorRecommendationView.Item::creatorId)
                .containsExactly(2L, 3L);
        assertThat(result.items().getFirst().aggregateScore()).isEqualByComparingTo("5");
        assertThat(result.items().getFirst().interestCodes()).isEmpty();
        assertThat(result.items().getFirst().seedCreatorIds()).isEmpty();
    }

    @Test
    void 개인화_결과가_있으면_인기순을_조회하지_않는다() {
        given(followQueryService.findFollowedCreatorIds(7L)).willReturn(List.of(1L));
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.empty());
        given(interestQueryService.findActiveCandidates(7L)).willReturn(List.of());
        given(candidateRepository.findActiveCandidatesBySeedCreatorIds(List.of(1L)))
                .willReturn(List.of(new ActiveCreatorRecommendationCandidate(1L, 20L, "M4", new BigDecimal("0.9"), 1)));
        given(creatorRepository.findByCreatorIdIn(List.of(20L))).willReturn(List.of(creator(20L, "후보")));
        given(spaceRepository.findByCreatorIdIn(List.of(20L))).willReturn(List.of(space(20L, "소개", "p")));

        PersonalizedCreatorRecommendationView result = service.findForMember(7L, 10);

        assertThat(result.policyVersion()).isNotEqualTo("POPULAR_FALLBACK_V1");
        then(popularRepository).shouldHaveNoInteractions();
    }

    @Test
    void 팔로우가_한_명이면_해당_seed의_저장_후보를_추천한다() {
        given(followQueryService.findFollowedCreatorIds(7L)).willReturn(List.of(1L));
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.empty());
        given(interestQueryService.findActiveCandidates(7L)).willReturn(List.of());
        given(candidateRepository.findActiveCandidatesBySeedCreatorIds(List.of(1L)))
                .willReturn(List.of(row(1L, 10L, 1)));
        given(creatorRepository.findByCreatorIdIn(List.of(10L)))
                .willReturn(List.of(creator(10L, "열")));
        given(spaceRepository.findByCreatorIdIn(List.of(10L)))
                .willReturn(List.of(space(10L, "소개10", "profile10")));

        PersonalizedCreatorRecommendationView result = service.findForMember(7L, 10);

        assertThat(result.items()).usingRecursiveFieldByFieldElementComparatorIgnoringFields("sources").containsExactly(new PersonalizedCreatorRecommendationView.Item(
                10L, "열", "소개10", "profile10", new BigDecimal("0.01639344"), List.of(), List.of(1L)));
    }

    @Test
    void 여러_팔로우의_활성_후보를_한번에_조회하고_본인과_기팔로우를_제외한다() {
        List<Long> followedCreatorIds = List.of(1L, 2L, 3L);
        given(followQueryService.findFollowedCreatorIds(7L)).willReturn(followedCreatorIds);
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator(9L, "본인")));
        given(interestQueryService.findActiveCandidates(7L)).willReturn(List.of());
        given(candidateRepository.findActiveCandidatesBySeedCreatorIds(followedCreatorIds)).willReturn(List.of(
                row(1L, 10L, 1),
                row(1L, 2L, 2),
                row(2L, 10L, 1),
                row(2L, 9L, 2),
                row(2L, 11L, 3)
        ));
        given(creatorRepository.findByCreatorIdIn(List.of(10L, 11L)))
                .willReturn(List.of(creator(11L, "열하나"), creator(10L, "열")));
        given(spaceRepository.findByCreatorIdIn(List.of(10L, 11L)))
                .willReturn(List.of(space(10L, "소개10", "profile10"), space(11L, "소개11", "profile11")));

        PersonalizedCreatorRecommendationView result = service.findForMember(7L, 10);

        assertThat(result.items()).usingRecursiveFieldByFieldElementComparatorIgnoringFields("sources").containsExactly(
                // 후보가 남는 seed는 1·2 두 명뿐이라(3은 활성 후보 없음) 기여도 합을 2로 나눈다.
                new PersonalizedCreatorRecommendationView.Item(
                        10L, "열", "소개10", "profile10", new BigDecimal("0.01639344"), List.of(), List.of(1L, 2L)),
                new PersonalizedCreatorRecommendationView.Item(
                        11L, "열하나", "소개11", "profile11", new BigDecimal("0.00793651"), List.of(), List.of(2L))
        );
        then(candidateRepository).should().findActiveCandidatesBySeedCreatorIds(followedCreatorIds);
    }

    @Test
    void 선정된_후보의_Space가_없으면_전체_조회에_실패하지_않고_해당_후보를_제외한다() {
        given(followQueryService.findFollowedCreatorIds(7L)).willReturn(List.of(1L));
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.empty());
        given(interestQueryService.findActiveCandidates(7L)).willReturn(List.of());
        given(candidateRepository.findActiveCandidatesBySeedCreatorIds(List.of(1L)))
                .willReturn(List.of(row(1L, 10L, 1)));
        given(creatorRepository.findByCreatorIdIn(List.of(10L)))
                .willReturn(List.of(creator(10L, "공간없음")));
        given(spaceRepository.findByCreatorIdIn(List.of(10L))).willReturn(List.of());

        PersonalizedCreatorRecommendationView result = service.findForMember(7L, 10);

        assertThat(result.policyVersion()).isEqualTo("FOLLOW_PERSONALIZED_V2");
        assertThat(result.items()).isEmpty();
    }

    @Test
    void 관심_분야만_있으면_팔로우_후보를_조회하지_않고_INTEREST_정책으로_추천한다() {
        given(followQueryService.findFollowedCreatorIds(7L)).willReturn(List.of());
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.empty());
        given(interestQueryService.findActiveCandidates(7L)).willReturn(List.of(
                interestRow("FOOD", 10L, 1), interestRow("FITNESS", 10L, 11), interestRow("FITNESS", 12L, 2)));
        given(creatorRepository.findByCreatorIdIn(List.of(10L, 12L)))
                .willReturn(List.of(creator(10L, "열"), creator(12L, "열둘")));
        given(spaceRepository.findByCreatorIdIn(List.of(10L, 12L)))
                .willReturn(List.of(space(10L, "소개10", "profile10"), space(12L, "소개12", "profile12")));

        PersonalizedCreatorRecommendationView result = service.findForMember(7L, 10);

        assertThat(result.policyVersion()).isEqualTo("INTEREST_PERSONALIZED_V1");
        // 유효 분야 2개(FOOD·FITNESS): 10번은 두 분야에서 (0.01639344 + 0.01408451) / 2, 12번은 FITNESS 한 곳만 0.01612903 / 2
        assertThat(result.items()).extracting(PersonalizedCreatorRecommendationView.Item::creatorId)
                .containsExactly(10L, 12L);
        assertThat(result.items().getFirst().aggregateScore()).isEqualByComparingTo("0.01523898");
        assertThat(result.items().getFirst().interestCodes()).containsExactly("FITNESS", "FOOD");
        assertThat(result.items().getFirst().seedCreatorIds()).isEmpty();
        assertThat(result.items().get(1).aggregateScore()).isEqualByComparingTo("0.00806452");
        then(candidateRepository).should(never()).findActiveCandidatesBySeedCreatorIds(any());
    }

    @Test
    void 관심_분야와_팔로우가_모두_유효하면_HYBRID로_절반씩_섞고_출처를_함께_반환한다() {
        given(followQueryService.findFollowedCreatorIds(7L)).willReturn(List.of(1L));
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.empty());
        given(interestQueryService.findActiveCandidates(7L)).willReturn(List.of(interestRow("FOOD", 10L, 1)));
        given(candidateRepository.findActiveCandidatesBySeedCreatorIds(List.of(1L)))
                .willReturn(List.of(row(1L, 10L, 1), row(1L, 11L, 2)));
        given(creatorRepository.findByCreatorIdIn(List.of(10L, 11L)))
                .willReturn(List.of(creator(10L, "열"), creator(11L, "열하나")));
        given(spaceRepository.findByCreatorIdIn(List.of(10L, 11L)))
                .willReturn(List.of(space(10L, "소개10", "profile10"), space(11L, "소개11", "profile11")));

        PersonalizedCreatorRecommendationView result = service.findForMember(7L, 10);

        assertThat(result.policyVersion()).isEqualTo("HYBRID_PERSONALIZED_V1");
        // 10번: 0.5 × 0.01639344 + 0.5 × 0.01639344, 11번: 관심 평균 0과 0.5 × 0.01612903
        assertThat(result.items()).extracting(PersonalizedCreatorRecommendationView.Item::creatorId)
                .containsExactly(10L, 11L);
        assertThat(result.items().getFirst().aggregateScore()).isEqualByComparingTo("0.01639344");
        assertThat(result.items().getFirst().interestCodes()).containsExactly("FOOD");
        assertThat(result.items().getFirst().seedCreatorIds()).containsExactly(1L);
        assertThat(result.items().get(1).aggregateScore()).isEqualByComparingTo("0.00806452");
        assertThat(result.items().get(1).interestCodes()).isEmpty();
    }

    @Test
    void size는_정렬_뒤에_적용한다() {
        given(followQueryService.findFollowedCreatorIds(7L)).willReturn(List.of());
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.empty());
        given(interestQueryService.findActiveCandidates(7L)).willReturn(List.of(
                interestRow("FOOD", 12L, 3), interestRow("FOOD", 10L, 1), interestRow("FOOD", 11L, 2)));
        given(creatorRepository.findByCreatorIdIn(List.of(10L, 11L)))
                .willReturn(List.of(creator(10L, "열"), creator(11L, "열하나")));
        given(spaceRepository.findByCreatorIdIn(List.of(10L, 11L)))
                .willReturn(List.of(space(10L, "소개10", "profile10"), space(11L, "소개11", "profile11")));

        PersonalizedCreatorRecommendationView result = service.findForMember(7L, 2);

        assertThat(result.items()).extracting(PersonalizedCreatorRecommendationView.Item::creatorId)
                .containsExactly(10L, 11L);
    }

    private ActiveInterestRecommendationCandidate interestRow(String interestCode, Long creatorId, int rank) {
        return new ActiveInterestRecommendationCandidate(interestCode, creatorId, rank);
    }

    private ActiveCreatorRecommendationCandidate row(Long seedCreatorId, Long candidateCreatorId, int rank) {
        return new ActiveCreatorRecommendationCandidate(
                seedCreatorId, candidateCreatorId, "M4", new BigDecimal("1.00000000"), rank);
    }

    private Creator creator(Long creatorId, String name) {
        Creator creator = new Creator(1000L + creatorId, name);
        ReflectionTestUtils.setField(creator, "creatorId", creatorId);
        return creator;
    }

    private CreatorSpace space(Long creatorId, String introText, String profileImageUrl) {
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                1L, introText, profileImageUrl, "banner", "creator-{creatorId}");
        return CreatorSpace.fromTemplate(creatorId, template, "creator-" + creatorId);
    }
}

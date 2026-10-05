package kr.co.cking.creator.application;

import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSimilarityCandidateRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.creator.repository.ActiveCreatorRecommendationCandidate;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorSimilarityCandidateRepository candidateRepository =
            mock(CreatorSimilarityCandidateRepository.class);
    private final CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
    private CreatorRecommendationQueryService service;

    @BeforeEach
    void setUp() {
        service = new CreatorRecommendationQueryService(
                followQueryService,
                creatorRepository,
                candidateRepository,
                spaceRepository,
                new FollowBasedCreatorRecommendationPolicy());
    }

    @Test
    void 팔로우가_없으면_저장_추천을_조회하지_않고_빈_목록을_반환한다() {
        given(followQueryService.findFollowedCreatorIds(7L)).willReturn(List.of());

        PersonalizedCreatorRecommendationView result = service.findForMember(7L, 10);

        assertThat(result.policyVersion()).isEqualTo("FOLLOW_PERSONALIZED_V1");
        assertThat(result.items()).isEmpty();
        then(candidateRepository).should(never()).findActiveCandidatesBySeedCreatorIds(any());
    }

    @Test
    void 팔로우가_한_명이지만_활성_후보가_없으면_빈_목록을_반환한다() {
        given(followQueryService.findFollowedCreatorIds(7L)).willReturn(List.of(1L));
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.empty());
        given(candidateRepository.findActiveCandidatesBySeedCreatorIds(List.of(1L)))
                .willReturn(List.of());

        PersonalizedCreatorRecommendationView result = service.findForMember(7L, 10);

        assertThat(result.items()).isEmpty();
        then(candidateRepository).should().findActiveCandidatesBySeedCreatorIds(List.of(1L));
        then(creatorRepository).should(never()).findByCreatorIdIn(any());
    }

    @Test
    void 팔로우가_한_명이면_해당_seed의_저장_후보를_추천한다() {
        given(followQueryService.findFollowedCreatorIds(7L)).willReturn(List.of(1L));
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.empty());
        given(candidateRepository.findActiveCandidatesBySeedCreatorIds(List.of(1L)))
                .willReturn(List.of(row(1L, 10L, 1)));
        given(creatorRepository.findByCreatorIdIn(List.of(10L)))
                .willReturn(List.of(creator(10L, "열")));
        given(spaceRepository.findByCreatorIdIn(List.of(10L)))
                .willReturn(List.of(space(10L, "소개10", "profile10")));

        PersonalizedCreatorRecommendationView result = service.findForMember(7L, 10);

        assertThat(result.items()).containsExactly(new PersonalizedCreatorRecommendationView.Item(
                10L, "열", "소개10", "profile10", new BigDecimal("0.01639344"), List.of(1L)));
    }

    @Test
    void 여러_팔로우의_활성_후보를_한번에_조회하고_본인과_기팔로우를_제외한다() {
        List<Long> followedCreatorIds = List.of(1L, 2L, 3L);
        given(followQueryService.findFollowedCreatorIds(7L)).willReturn(followedCreatorIds);
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator(9L, "본인")));
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

        assertThat(result.items()).containsExactly(
                new PersonalizedCreatorRecommendationView.Item(
                        10L, "열", "소개10", "profile10", new BigDecimal("0.03278688"), List.of(1L, 2L)),
                new PersonalizedCreatorRecommendationView.Item(
                        11L, "열하나", "소개11", "profile11", new BigDecimal("0.01587302"), List.of(2L))
        );
        then(candidateRepository).should().findActiveCandidatesBySeedCreatorIds(followedCreatorIds);
    }

    @Test
    void 선정된_후보의_Space가_없으면_전체_조회에_실패하지_않고_해당_후보를_제외한다() {
        given(followQueryService.findFollowedCreatorIds(7L)).willReturn(List.of(1L));
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.empty());
        given(candidateRepository.findActiveCandidatesBySeedCreatorIds(List.of(1L)))
                .willReturn(List.of(row(1L, 10L, 1)));
        given(creatorRepository.findByCreatorIdIn(List.of(10L)))
                .willReturn(List.of(creator(10L, "공간없음")));
        given(spaceRepository.findByCreatorIdIn(List.of(10L))).willReturn(List.of());

        PersonalizedCreatorRecommendationView result = service.findForMember(7L, 10);

        assertThat(result.policyVersion()).isEqualTo("FOLLOW_PERSONALIZED_V1");
        assertThat(result.items()).isEmpty();
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

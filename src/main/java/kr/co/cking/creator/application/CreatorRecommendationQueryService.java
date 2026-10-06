package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSimilarityCandidateRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import kr.co.cking.interest.application.InterestRecommendationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 요청 중 모델 API를 호출하지 않고 저장된 활성 추천 결과만 합쳐 개인화 목록을 만든다. 회원이 고른 관심 분야의 후보와 팔로우
 * seed의 후보를 {@link PersonalizedCreatorRecommendationPolicy}로 합친다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreatorRecommendationQueryService {

    private final CreatorFollowQueryService followQueryService;
    private final InterestRecommendationQueryService interestQueryService;
    private final CreatorRepository creatorRepository;
    private final CreatorSimilarityCandidateRepository candidateRepository;
    private final CreatorSpaceRepository spaceRepository;
    private final PersonalizedCreatorRecommendationPolicy policy;

    public PersonalizedCreatorRecommendationView findForMember(Long memberId, int size) {
        List<Long> followedCreatorIds = followQueryService.findFollowedCreatorIds(memberId);
        var excludedCreatorIds = new HashSet<>(followedCreatorIds);
        creatorRepository.findByMemberId(memberId)
                .map(Creator::getCreatorId)
                .ifPresent(excludedCreatorIds::add);

        PersonalizedCreatorRecommendationPolicy.Recommendation recommendation = policy.recommend(
                followedCreatorIds.isEmpty()
                        ? List.of()
                        : candidateRepository.findActiveCandidatesBySeedCreatorIds(followedCreatorIds),
                interestQueryService.findActiveCandidates(memberId),
                excludedCreatorIds);

        List<PersonalizedCreatorRecommendationPolicy.Item> ranked = recommendation.items().stream()
                .limit(size)
                .toList();
        if (ranked.isEmpty()) {
            return new PersonalizedCreatorRecommendationView(recommendation.policyVersion(), List.of());
        }

        List<Long> selectedCreatorIds = ranked.stream()
                .map(PersonalizedCreatorRecommendationPolicy.Item::creatorId)
                .toList();
        Map<Long, Creator> creators = creatorRepository.findByCreatorIdIn(selectedCreatorIds).stream()
                .collect(Collectors.toMap(Creator::getCreatorId, Function.identity()));
        Map<Long, CreatorSpace> spaces = spaceRepository.findByCreatorIdIn(selectedCreatorIds).stream()
                .collect(Collectors.toMap(CreatorSpace::getCreatorId, Function.identity()));
        if (creators.size() != selectedCreatorIds.size()) {
            throw new BusinessException(CommonErrorCode.SYSTEM_ERROR);
        }

        return new PersonalizedCreatorRecommendationView(
                recommendation.policyVersion(),
                ranked.stream()
                        .filter(candidate -> spaces.containsKey(candidate.creatorId()))
                        .map(candidate -> toItem(
                                candidate,
                                creators.get(candidate.creatorId()),
                                spaces.get(candidate.creatorId())))
                        .toList());
    }

    private PersonalizedCreatorRecommendationView.Item toItem(
            PersonalizedCreatorRecommendationPolicy.Item candidate,
            Creator creator,
            CreatorSpace space
    ) {
        return new PersonalizedCreatorRecommendationView.Item(
                candidate.creatorId(),
                creator.getName(),
                space.getIntroText(),
                space.getProfileImageUrl(),
                candidate.aggregateScore(),
                candidate.interestCodes(),
                candidate.seedCreatorIds());
    }
}

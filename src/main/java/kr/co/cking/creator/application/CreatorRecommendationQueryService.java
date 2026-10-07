package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSimilarityCandidateRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.creator.repository.PopularCreatorCandidate;
import kr.co.cking.creator.repository.PopularCreatorQueryRepository;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import kr.co.cking.interest.application.InterestRecommendationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 요청 중 모델 API를 호출하지 않고 저장된 활성 추천 결과만 합쳐 개인화 목록을 만든다. 회원이 고른 관심 분야의 후보와 팔로우
 * seed의 후보를 {@link PersonalizedCreatorRecommendationPolicy}로 합친다. 합친 결과가 비어 있으면 팔로워 수 순 인기 Creator로 채운다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreatorRecommendationQueryService {

    public static final String POPULAR_FALLBACK_VERSION = "POPULAR_FALLBACK_V1";

    private final CreatorFollowQueryService followQueryService;
    private final InterestRecommendationQueryService interestQueryService;
    private final CreatorRepository creatorRepository;
    private final CreatorSimilarityCandidateRepository candidateRepository;
    private final CreatorSpaceRepository spaceRepository;
    private final PopularCreatorQueryRepository popularRepository;
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

        if (recommendation.items().isEmpty()) {
            return popularFallback(excludedCreatorIds, size);
        }

        List<PersonalizedCreatorRecommendationPolicy.Item> ranked = recommendation.items().stream()
                .limit(size)
                .toList();
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

    /**
     * 제외 대상(본인·기팔로우)을 SQL이 아니라 읽은 뒤에 거르므로, 그만큼 더 읽어 {@code size}를 채운다. 점수 자리에는 팔로워 수를
     * 넣고 추천 근거(관심 분야·seed)는 없다.
     */
    private PersonalizedCreatorRecommendationView popularFallback(Set<Long> excludedCreatorIds, int size) {
        List<PopularCreatorCandidate> popular = popularRepository.findPopular(
                PageRequest.of(0, size + excludedCreatorIds.size()));
        return new PersonalizedCreatorRecommendationView(
                POPULAR_FALLBACK_VERSION,
                popular.stream()
                        .filter(candidate -> !excludedCreatorIds.contains(candidate.creatorId()))
                        .limit(size)
                        .map(candidate -> new PersonalizedCreatorRecommendationView.Item(
                                candidate.creatorId(),
                                candidate.creatorName(),
                                candidate.introText(),
                                candidate.profileImageUrl(),
                                BigDecimal.valueOf(candidate.followerCount()),
                                List.of(),
                                List.of()))
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

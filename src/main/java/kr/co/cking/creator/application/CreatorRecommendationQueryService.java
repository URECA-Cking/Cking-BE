package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.FollowBasedCreatorRecommendationPolicy.RankedCandidate;
import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSimilarityCandidateRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 요청 중 모델 API를 호출하지 않고 저장된 활성 유사 추천 결과만 합쳐 개인화 목록을 만든다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreatorRecommendationQueryService {

    private final CreatorFollowQueryService followQueryService;
    private final CreatorRepository creatorRepository;
    private final CreatorSimilarityCandidateRepository candidateRepository;
    private final CreatorSpaceRepository spaceRepository;
    private final FollowBasedCreatorRecommendationPolicy policy;

    public PersonalizedCreatorRecommendationView findForMember(Long memberId, int size) {
        List<Long> followedCreatorIds = followQueryService.findFollowedCreatorIds(memberId);
        if (followedCreatorIds.isEmpty()) {
            return empty();
        }

        var excludedCreatorIds = new HashSet<>(followedCreatorIds);
        creatorRepository.findByMemberId(memberId)
                .map(Creator::getCreatorId)
                .ifPresent(excludedCreatorIds::add);

        List<RankedCandidate> rankedCandidates = policy.aggregate(
                candidateRepository.findActiveCandidatesBySeedCreatorIds(followedCreatorIds),
                excludedCreatorIds,
                size);
        if (rankedCandidates.isEmpty()) {
            return empty();
        }

        List<Long> selectedCreatorIds = rankedCandidates.stream()
                .map(RankedCandidate::creatorId)
                .toList();
        Map<Long, Creator> creators = creatorRepository.findByCreatorIdIn(selectedCreatorIds).stream()
                .collect(Collectors.toMap(Creator::getCreatorId, Function.identity()));
        Map<Long, CreatorSpace> spaces = spaceRepository.findByCreatorIdIn(selectedCreatorIds).stream()
                .collect(Collectors.toMap(CreatorSpace::getCreatorId, Function.identity()));
        if (creators.size() != selectedCreatorIds.size() || spaces.size() != selectedCreatorIds.size()) {
            throw new BusinessException(CommonErrorCode.SYSTEM_ERROR);
        }

        return new PersonalizedCreatorRecommendationView(
                FollowBasedCreatorRecommendationPolicy.VERSION,
                rankedCandidates.stream().map(candidate -> toItem(
                        candidate,
                        creators.get(candidate.creatorId()),
                        spaces.get(candidate.creatorId()))).toList());
    }

    private PersonalizedCreatorRecommendationView.Item toItem(
            RankedCandidate candidate,
            Creator creator,
            CreatorSpace space
    ) {
        return new PersonalizedCreatorRecommendationView.Item(
                candidate.creatorId(),
                creator.getName(),
                space.getIntroText(),
                space.getProfileImageUrl(),
                candidate.aggregateScore(),
                candidate.seedCreatorIds());
    }

    private PersonalizedCreatorRecommendationView empty() {
        return new PersonalizedCreatorRecommendationView(
                FollowBasedCreatorRecommendationPolicy.VERSION, List.of());
    }
}

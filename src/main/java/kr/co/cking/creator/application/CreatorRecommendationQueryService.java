package kr.co.cking.creator.application;

import kr.co.cking.recommendation.application.RecommendationEligibilityService;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView;
import kr.co.cking.creator.application.dto.RecommendationSource;
import kr.co.cking.creator.repository.ActiveCreatorRecommendationCandidate;
import kr.co.cking.interest.repository.ActiveInterestRecommendationCandidate;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.co.cking.creator.domain.RecommendationSourceType;

/**
 * 요청 중 모델 API를 호출하지 않고 저장된 활성 추천 결과만 합쳐 개인화 목록을 만든다. 회원이 고른 관심 분야의 후보와 팔로우
 * seed의 후보를 {@link PersonalizedCreatorRecommendationPolicy}로 합친다. 자격을 통과한 후보만 집계하며 빈 결과를 보충하지 않는다.
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
    private final RecommendationEligibilityService eligibility;
    private final kr.co.cking.interest.application.MemberInterestService memberInterests;

    /**
     * 회원의 개인화 추천을 만든다. 팔로우·관심 분야 저장 후보를 합쳐 정렬하고 {@code size}를 적용한 뒤, 선택된 Creator의
     * 프로필·Space와 팔로워 수를 각각 일괄 조회해 카드로 만든다. 합친 결과가 비면 빈 목록을 반환한다.
     */
    public PersonalizedCreatorRecommendationView findForMember(Long memberId, int size) {
        List<Long> followedCreatorIds = followQueryService.findFollowedCreatorIds(memberId);
        var excludedCreatorIds = new HashSet<>(followedCreatorIds);
        creatorRepository.findByMemberId(memberId)
                .map(Creator::getCreatorId)
                .ifPresent(excludedCreatorIds::add);

        List<ActiveCreatorRecommendationCandidate> followRows = followedCreatorIds.isEmpty() ? List.of()
                : candidateRepository.findActiveCandidatesBySeedCreatorIds(followedCreatorIds);
        List<ActiveInterestRecommendationCandidate> interestRows = interestQueryService.findActiveCandidates(memberId);
        var selectedInterests = Set.copyOf(memberInterests.findMine(memberId).interestCodes());
        var followEntries = followRows.stream().map(row -> java.util.Map.entry(row,
                new RecommendationEligibilityService.Ref(row.generationId(), row.seedCreatorId(),
                        row.candidateCreatorId(), selectedInterests))).toList();
        var interestEntries = interestRows.stream().map(row -> java.util.Map.entry(row,
                new RecommendationEligibilityService.Ref(row.generationId(), null,
                        row.creatorId(), Set.of(row.interestCode())))).toList();
        var validFollow = eligibility.valid("SIMILAR", followEntries.stream().map(java.util.Map.Entry::getValue).toList());
        var validInterest = eligibility.valid("INTEREST", interestEntries.stream().map(java.util.Map.Entry::getValue).toList());
        followRows = followEntries.stream().filter(entry -> validFollow.contains(entry.getValue()))
                .map(java.util.Map.Entry::getKey).toList();
        interestRows = interestEntries.stream().filter(entry -> validInterest.contains(entry.getValue()))
                .map(java.util.Map.Entry::getKey).toList();
        PersonalizedCreatorRecommendationPolicy.Recommendation recommendation =
                policy.recommend(followRows, interestRows, excludedCreatorIds);

        if (recommendation.items().isEmpty()) {
            return new PersonalizedCreatorRecommendationView(recommendation.policyVersion(),List.of());
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
        Map<Long, Long> followerCounts = followQueryService.countFollowersByCreatorIds(selectedCreatorIds);
        var sources = new ArrayList<SourceRow>();
        followRows.forEach(row -> sources.add(new SourceRow(row.candidateCreatorId(),
                new RecommendationSource(RecommendationSourceType.FOLLOW, row.seedCreatorId().toString(),
                        null, row.generationId(), row.method(), row.modelVersion(), row.rank()))));
        interestRows.forEach(row -> sources.add(new SourceRow(row.creatorId(),
                new RecommendationSource(RecommendationSourceType.INTEREST, row.interestCode(),
                        row.taxonomyVersion(), row.generationId(), row.method(), row.modelVersion(), row.rank()))));
        Map<Long, List<RecommendationSource>> sourcesByCreator = sources.stream().collect(Collectors.groupingBy(
                SourceRow::creatorId, Collectors.mapping(SourceRow::source, Collectors.toList())));

        return new PersonalizedCreatorRecommendationView(
                recommendation.policyVersion(),
                ranked.stream()
                        .filter(candidate -> spaces.containsKey(candidate.creatorId()))
                        .map(candidate -> toItem(
                                candidate,
                                creators.get(candidate.creatorId()),
                                spaces.get(candidate.creatorId()),
                                sourcesByCreator.getOrDefault(candidate.creatorId(), List.of()),
                                followerCounts.getOrDefault(candidate.creatorId(), 0L)))
                        .toList());
    }

    /** 정렬된 후보 한 건에 Creator·Space 프로필, 추천 근거 출처, 팔로워 수를 붙여 응답용 카드로 만든다. */
    private PersonalizedCreatorRecommendationView.Item toItem(
            PersonalizedCreatorRecommendationPolicy.Item candidate,
            Creator creator,
            CreatorSpace space,
            List<RecommendationSource> sources,
            long followerCount
    ) {
        return new PersonalizedCreatorRecommendationView.Item(
                candidate.creatorId(),
                creator.publicDisplayName(),
                space.getIntroText(),
                space.getProfileImageUrl(),
                candidate.aggregateScore(),
                candidate.interestCodes(),
                candidate.seedCreatorIds(), List.copyOf(sources), followerCount);
    }

    private record SourceRow(Long creatorId, RecommendationSource source) { }
}

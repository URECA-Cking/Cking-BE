package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.dto.CreatorSimilarityView;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSimilarityCandidate;
import kr.co.cking.creator.domain.CreatorSimilarityGeneration;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSimilarityCandidateRepository;
import kr.co.cking.creator.repository.CreatorSimilarityGenerationRepository;
import kr.co.cking.creator.repository.CreatorSimilarityStateRepository;
import kr.co.cking.recommendation.application.RecommendationEligibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 모델 API를 호출하지 않고 현재 활성화된 저장 결과만 읽는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreatorSimilarityQueryService {
    // 저장 후보 상한을 일괄 검사한 뒤 size를 적용해 앞 순위의 무효 후보가 정상 후보를 가리지 않게 한다.
    private static final int MAX_STORED_CANDIDATES = 100;

    private final CreatorRepository creatorRepository;
    private final CreatorSimilarityGenerationRepository generationRepository;
    private final CreatorSimilarityCandidateRepository candidateRepository;
    private final CreatorSimilarityStateRepository stateRepository;
    private final RecommendationEligibilityService eligibility;

    public CreatorSimilarityView findSimilar(Long creatorId, int size) {
        if (!creatorRepository.existsById(creatorId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return stateRepository.findById(creatorId)
                .map(state -> findActive(creatorId, state.getCurrentGenerationId(), size))
                .orElseGet(() -> CreatorSimilarityView.empty(creatorId));
    }

    private CreatorSimilarityView findActive(Long creatorId, Long generationId, int size) {
        CreatorSimilarityGeneration generation = generationRepository.findById(generationId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SYSTEM_ERROR));
        List<CreatorSimilarityCandidate> stored = candidateRepository.findByGenerationIdOrderByRankAsc(
                generationId, PageRequest.of(0, MAX_STORED_CANDIDATES));
        var refs = stored.stream()
                .map(candidate -> toRef(generationId, creatorId, candidate))
                .toList();
        Set<RecommendationEligibilityService.Ref> eligible = eligibility.valid("SIMILAR", refs);
        List<CreatorSimilarityCandidate> candidates = stored.stream()
                .filter(candidate -> eligible.contains(toRef(generationId, creatorId, candidate)))
                .limit(size)
                .toList();
        Map<Long, Creator> creators = creatorRepository.findByCreatorIdIn(candidates.stream()
                        .map(candidate -> candidate.getSimilarCreatorId())
                        .toList()).stream()
                .collect(Collectors.toMap(Creator::getCreatorId, Function.identity()));
        if (creators.size() != candidates.size()) {
            throw new BusinessException(CommonErrorCode.SYSTEM_ERROR);
        }
        return new CreatorSimilarityView(
                creatorId,
                generation.getMethod(),
                generation.getModelVersion(),
                generation.getInputHash(),
                generation.getCreatedAt(),
                candidates.stream()
                        .map(candidate -> new CreatorSimilarityView.Candidate(
                                candidate.getSimilarCreatorId(),
                                creators.get(candidate.getSimilarCreatorId()).publicDisplayName(),
                                candidate.getScore(),
                                candidate.getRank()))
                        .toList());
    }

    private static RecommendationEligibilityService.Ref toRef(
            Long generationId, Long creatorId, CreatorSimilarityCandidate candidate) {
        return new RecommendationEligibilityService.Ref(generationId, creatorId, candidate.getSimilarCreatorId(), Set.of());
    }
}

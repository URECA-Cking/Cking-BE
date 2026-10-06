package kr.co.cking.interest.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.interest.application.InterestRecommendationBundleValidator.Bundle;
import kr.co.cking.interest.application.dto.InterestRecommendationCommand;
import kr.co.cking.interest.domain.InterestCategory;
import kr.co.cking.interest.domain.InterestCategoryId;
import kr.co.cking.interest.domain.InterestErrorCode;
import kr.co.cking.interest.domain.InterestRecommendationCandidate;
import kr.co.cking.interest.domain.InterestRecommendationGeneration;
import kr.co.cking.interest.domain.InterestRecommendationState;
import kr.co.cking.interest.domain.InterestTaxonomy;
import kr.co.cking.interest.repository.InterestCategoryRepository;
import kr.co.cking.interest.repository.InterestRecommendationCandidateRepository;
import kr.co.cking.interest.repository.InterestRecommendationGenerationRepository;
import kr.co.cking.interest.repository.InterestRecommendationStateRepository;
import kr.co.cking.interest.repository.InterestTaxonomyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검증을 마친 관심 분야별 후보 묶음을 새 세대로 저장하고 분야의 현재 공개 포인터를 원자적으로 교체한다.
 * 구조와 멱등 규칙은 유사 추천 결과 적재({@code CreatorSimilarityResultService})와 같고 기준축만 분야다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class InterestRecommendationResultService {

    private final InterestTaxonomyRepository taxonomyRepository;
    private final InterestCategoryRepository categoryRepository;
    private final CreatorRepository creatorRepository;
    private final InterestRecommendationGenerationRepository generationRepository;
    private final InterestRecommendationCandidateRepository candidateRepository;
    private final InterestRecommendationStateRepository stateRepository;

    public StoreResult replace(String interestCode, InterestRecommendationCommand command) {
        Bundle bundle = InterestRecommendationBundleValidator.validate(interestCode, command);
        // 같은 분야의 동시 적재를 직렬화한다. MySQL REPEATABLE READ에서는 첫 일반 SELECT가 스냅샷을 만들므로,
        // 다른 읽기보다 먼저 잠가야 기다린 뒤에 앞선 적재가 커밋한 세대를 볼 수 있다.
        InterestCategory category = categoryRepository
                .findByIdForUpdate(bundle.taxonomyVersion(), bundle.interestCode())
                .orElse(null);
        requireRegisteredTaxonomy(bundle);
        if (category == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        requireExistingCreators(bundle.candidates());

        InterestCategoryId categoryId = new InterestCategoryId(bundle.taxonomyVersion(), bundle.interestCode());
        InterestRecommendationState state = stateRepository.findById(categoryId).orElse(null);
        InterestRecommendationGeneration existing = generationRepository
                .findByTaxonomyVersionAndInterestCodeAndInputHash(
                        bundle.taxonomyVersion(), bundle.interestCode(), bundle.inputHash())
                .orElse(null);
        if (existing != null) {
            if (state != null && state.getCurrentGenerationId().equals(existing.getGenerationId())) {
                if (!samePayload(existing, bundle)) {
                    throw new BusinessException(InterestErrorCode.RECOMMENDATION_INPUT_CONFLICT);
                }
                return StoreResult.of(existing, bundle.candidates().size(), false);
            }
            throw new BusinessException(InterestErrorCode.STALE_RECOMMENDATION_INPUT);
        }

        InterestRecommendationGeneration generation = generationRepository.saveAndFlush(
                new InterestRecommendationGeneration(
                        bundle.taxonomyVersion(), bundle.interestCode(), bundle.method(),
                        bundle.modelVersion(), bundle.inputHash(), Instant.now()));
        candidateRepository.saveAll(bundle.candidates().stream()
                .map(candidate -> new InterestRecommendationCandidate(
                        generation.getGenerationId(), candidate.creatorId(), candidate.score(), candidate.rank()))
                .toList());

        if (state == null) {
            stateRepository.save(new InterestRecommendationState(
                    bundle.taxonomyVersion(), bundle.interestCode(), generation.getGenerationId()));
        } else {
            state.activate(generation.getGenerationId());
        }
        return StoreResult.of(generation, bundle.candidates().size(), true);
    }

    /** 등록된 분류체계 버전이고 요청의 해시가 등록된 해시와 같아야 한다(활성 여부는 묻지 않는다). */
    private void requireRegisteredTaxonomy(Bundle bundle) {
        InterestTaxonomy taxonomy = taxonomyRepository.findById(bundle.taxonomyVersion()).orElse(null);
        if (taxonomy == null || !taxonomy.getTaxonomyHash().equals(bundle.taxonomyHash())) {
            throw new BusinessException(InterestErrorCode.INVALID_RECOMMENDATION_RESULT);
        }
    }

    private void requireExistingCreators(List<InterestRecommendationBundleValidator.Candidate> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        Set<Long> requestedIds = candidates.stream()
                .map(InterestRecommendationBundleValidator.Candidate::creatorId)
                .collect(Collectors.toSet());
        Set<Long> existingIds = creatorRepository.findByCreatorIdIn(requestedIds).stream()
                .map(Creator::getCreatorId)
                .collect(Collectors.toSet());
        if (!existingIds.equals(requestedIds)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private boolean samePayload(InterestRecommendationGeneration generation, Bundle bundle) {
        if (!generation.getMethod().equals(bundle.method())
                || !generation.getModelVersion().equals(bundle.modelVersion())) {
            return false;
        }
        List<InterestRecommendationCandidate> stored =
                candidateRepository.findByGenerationIdOrderByRankAsc(generation.getGenerationId());
        if (stored.size() != bundle.candidates().size()) {
            return false;
        }
        for (int index = 0; index < stored.size(); index++) {
            InterestRecommendationCandidate saved = stored.get(index);
            InterestRecommendationBundleValidator.Candidate requested = bundle.candidates().get(index);
            if (!saved.getCreatorId().equals(requested.creatorId())
                    || saved.getScore().compareTo(requested.score()) != 0
                    || saved.getRank() != requested.rank()) {
                return false;
            }
        }
        return true;
    }

    public record StoreResult(
            String taxonomyVersion,
            String interestCode,
            Long generationId,
            String inputHash,
            int candidateCount,
            boolean applied
    ) {
        private static StoreResult of(InterestRecommendationGeneration generation, int candidateCount, boolean applied) {
            return new StoreResult(
                    generation.getTaxonomyVersion(), generation.getInterestCode(), generation.getGenerationId(),
                    generation.getInputHash(), candidateCount, applied);
        }
    }
}

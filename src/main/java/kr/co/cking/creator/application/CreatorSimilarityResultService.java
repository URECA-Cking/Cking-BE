package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.dto.CreatorSimilarityResultCommand;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSimilarityCandidate;
import kr.co.cking.creator.domain.CreatorSimilarityGeneration;
import kr.co.cking.creator.domain.CreatorSimilarityState;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSimilarityCandidateRepository;
import kr.co.cking.creator.repository.CreatorSimilarityGenerationRepository;
import kr.co.cking.creator.repository.CreatorSimilarityStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 검증을 마친 LLM 후보 묶음을 새 세대로 저장하고 현재 공개 포인터를 원자적으로 교체한다. */
@Service
@RequiredArgsConstructor
@Transactional
public class CreatorSimilarityResultService {

    private static final BigDecimal MIN_SCORE = new BigDecimal("-1.0");
    private static final BigDecimal MAX_SCORE = new BigDecimal("2.0");

    private final CreatorRepository creatorRepository;
    private final CreatorSimilarityGenerationRepository generationRepository;
    private final CreatorSimilarityCandidateRepository candidateRepository;
    private final CreatorSimilarityStateRepository stateRepository;

    public StoreResult replace(Long creatorId, CreatorSimilarityResultCommand command) {
        creatorRepository.findByIdForUpdate(creatorId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        NormalizedBundle bundle = normalize(creatorId, command);
        requireExistingCandidates(bundle.candidates());

        CreatorSimilarityState state = stateRepository.findByCreatorIdForUpdate(creatorId).orElse(null);
        CreatorSimilarityGeneration existing = generationRepository
                .findByCreatorIdAndInputHashForUpdate(creatorId, bundle.inputHash())
                .orElse(null);
        if (existing != null) {
            if (state != null && Objects.equals(state.getCurrentGenerationId(), existing.getGenerationId())) {
                if (!samePayload(existing, bundle)) {
                    throw new BusinessException(CreatorErrorCode.RECOMMENDATION_INPUT_CONFLICT);
                }
                return StoreResult.idempotent(existing, bundle.candidates().size());
            }
            throw new BusinessException(CreatorErrorCode.STALE_RECOMMENDATION_INPUT);
        }

        CreatorSimilarityGeneration generation = generationRepository.saveAndFlush(
                new CreatorSimilarityGeneration(
                        creatorId, bundle.method(), bundle.modelVersion(), bundle.inputHash(), Instant.now()));
        candidateRepository.saveAll(bundle.candidates().stream()
                .map(candidate -> new CreatorSimilarityCandidate(
                        generation.getGenerationId(), candidate.similarCreatorId(), candidate.score(), candidate.rank()))
                .toList());

        if (state == null) {
            stateRepository.save(new CreatorSimilarityState(creatorId, generation.getGenerationId()));
        } else {
            state.activate(generation.getGenerationId());
        }
        return StoreResult.applied(generation, bundle.candidates().size());
    }

    private NormalizedBundle normalize(Long creatorId, CreatorSimilarityResultCommand command) {
        if (command == null || !Objects.equals(creatorId, command.creatorId())
                || command.candidates() == null || command.candidates().size() > 100) {
            throw invalidResult();
        }

        List<NormalizedCandidate> candidates = new ArrayList<>();
        Set<Long> similarCreatorIds = new HashSet<>();
        Set<Integer> ranks = new HashSet<>();
        for (CreatorSimilarityResultCommand.Candidate candidate : command.candidates()) {
            if (candidate == null || !Objects.equals(creatorId, candidate.creatorId())
                    || candidate.similarCreatorId() == null || candidate.similarCreatorId() <= 0
                    || Objects.equals(creatorId, candidate.similarCreatorId())
                    || candidate.score() == null
                    || candidate.score().compareTo(MIN_SCORE) < 0 || candidate.score().compareTo(MAX_SCORE) > 0
                    || candidate.score().scale() > 8
                    || candidate.rank() == null || candidate.rank() <= 0
                    || isBlank(candidate.method()) || candidate.method().length() > 20
                    || isBlank(candidate.modelVersion()) || candidate.modelVersion().length() > 255
                    || candidate.inputHash() == null || !candidate.inputHash().matches("[0-9a-f]{64}")
                    || !similarCreatorIds.add(candidate.similarCreatorId())
                    || !ranks.add(candidate.rank())) {
                throw invalidResult();
            }
            candidates.add(new NormalizedCandidate(
                    candidate.similarCreatorId(), candidate.score(), candidate.rank(),
                    candidate.method(), candidate.modelVersion(), candidate.inputHash()));
        }

        candidates.sort(Comparator.comparingInt(NormalizedCandidate::rank));
        if (candidates.isEmpty()) {
            validateMetadata(command.method(), command.modelVersion(), command.inputHash());
            return new NormalizedBundle(
                    command.method(), command.modelVersion(), command.inputHash(), List.of());
        }

        NormalizedCandidate first = candidates.getFirst();
        for (int index = 0; index < candidates.size(); index++) {
            NormalizedCandidate candidate = candidates.get(index);
            if (candidate.rank() != index + 1
                    || !first.method().equals(candidate.method())
                    || !first.modelVersion().equals(candidate.modelVersion())
                    || !first.inputHash().equals(candidate.inputHash())) {
                throw invalidResult();
            }
            if (index > 0 && isOutOfOrder(candidates.get(index - 1), candidate)) {
                throw invalidResult();
            }
        }

        String method = first.method();
        String modelVersion = first.modelVersion();
        String inputHash = first.inputHash();
        if (hasAnyMetadata(command)) {
            validateMetadata(command.method(), command.modelVersion(), command.inputHash());
            if (!method.equals(command.method())
                    || !modelVersion.equals(command.modelVersion())
                    || !inputHash.equals(command.inputHash())) {
                throw invalidResult();
            }
            method = command.method();
            modelVersion = command.modelVersion();
            inputHash = command.inputHash();
        }
        return new NormalizedBundle(
                method, modelVersion, inputHash, List.copyOf(candidates));
    }

    private boolean hasAnyMetadata(CreatorSimilarityResultCommand command) {
        return command.method() != null || command.modelVersion() != null || command.inputHash() != null;
    }

    private void validateMetadata(String method, String modelVersion, String inputHash) {
        if (isBlank(method) || method.length() > 20
                || isBlank(modelVersion) || modelVersion.length() > 255
                || inputHash == null || !inputHash.matches("[0-9a-f]{64}")) {
            throw invalidResult();
        }
    }

    private boolean isOutOfOrder(NormalizedCandidate previous, NormalizedCandidate current) {
        int scoreOrder = previous.score().compareTo(current.score());
        return scoreOrder < 0
                || (scoreOrder == 0 && previous.similarCreatorId() > current.similarCreatorId());
    }

    private void requireExistingCandidates(List<NormalizedCandidate> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        Set<Long> requestedIds = candidates.stream()
                .map(NormalizedCandidate::similarCreatorId)
                .collect(java.util.stream.Collectors.toSet());
        Set<Long> existingIds = creatorRepository.findByCreatorIdIn(requestedIds).stream()
                .map(creator -> creator.getCreatorId())
                .collect(java.util.stream.Collectors.toSet());
        if (!existingIds.equals(requestedIds)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private boolean samePayload(CreatorSimilarityGeneration generation, NormalizedBundle bundle) {
        if (!generation.getMethod().equals(bundle.method())
                || !generation.getModelVersion().equals(bundle.modelVersion())) {
            return false;
        }
        List<CreatorSimilarityCandidate> stored = candidateRepository
                .findByGenerationIdForUpdateOrderByRankAsc(generation.getGenerationId());
        if (stored.size() != bundle.candidates().size()) {
            return false;
        }
        for (int index = 0; index < stored.size(); index++) {
            CreatorSimilarityCandidate saved = stored.get(index);
            NormalizedCandidate requested = bundle.candidates().get(index);
            if (!saved.getSimilarCreatorId().equals(requested.similarCreatorId())
                    || saved.getScore().compareTo(requested.score()) != 0
                    || saved.getRank() != requested.rank()) {
                return false;
            }
        }
        return true;
    }

    private BusinessException invalidResult() {
        return new BusinessException(CreatorErrorCode.INVALID_RECOMMENDATION_RESULT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record StoreResult(
            Long creatorId,
            Long generationId,
            String inputHash,
            int candidateCount,
            boolean applied
    ) {
        private static StoreResult applied(CreatorSimilarityGeneration generation, int candidateCount) {
            return from(generation, candidateCount, true);
        }

        private static StoreResult idempotent(CreatorSimilarityGeneration generation, int candidateCount) {
            return from(generation, candidateCount, false);
        }

        private static StoreResult from(
                CreatorSimilarityGeneration generation,
                int candidateCount,
                boolean applied
        ) {
            return new StoreResult(
                    generation.getCreatorId(), generation.getGenerationId(), generation.getInputHash(),
                    candidateCount, applied);
        }
    }

    private record NormalizedBundle(
            String method,
            String modelVersion,
            String inputHash,
            List<NormalizedCandidate> candidates
    ) {
    }

    private record NormalizedCandidate(
            Long similarCreatorId,
            BigDecimal score,
            int rank,
            String method,
            String modelVersion,
            String inputHash
    ) {
    }
}

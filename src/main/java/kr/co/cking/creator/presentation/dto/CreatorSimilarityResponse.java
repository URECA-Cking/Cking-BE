package kr.co.cking.creator.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import kr.co.cking.creator.application.CreatorSimilarityResultService;
import kr.co.cking.creator.application.dto.CreatorSimilarityView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CreatorSimilarityResponse {

    private CreatorSimilarityResponse() {
    }

    public record Stored(
            Long creatorId,
            Long generationId,
            String inputHash,
            int candidateCount,
            boolean applied
    ) {
        public static Stored from(CreatorSimilarityResultService.StoreResult result) {
            return new Stored(
                    result.creatorId(), result.generationId(), result.inputHash(),
                    result.candidateCount(), result.applied());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(
            Long creatorId,
            String method,
            String modelVersion,
            String inputHash,
            Instant generatedAt,
            List<Candidate> candidates
    ) {
        public static Result from(CreatorSimilarityView view) {
            return new Result(
                    view.creatorId(), view.method(), view.modelVersion(), view.inputHash(), view.generatedAt(),
                    view.candidates().stream().map(Candidate::from).toList());
        }
    }

    public record Candidate(
            Long similarCreatorId,
            String name,
            BigDecimal score,
            int rank
    ) {
        private static Candidate from(CreatorSimilarityView.Candidate candidate) {
            return new Candidate(
                    candidate.similarCreatorId(), candidate.name(), candidate.score(), candidate.rank());
        }
    }
}

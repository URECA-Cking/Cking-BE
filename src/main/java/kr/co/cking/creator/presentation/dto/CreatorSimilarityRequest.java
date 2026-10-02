package kr.co.cking.creator.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import kr.co.cking.creator.application.dto.CreatorSimilarityResultCommand;

import java.math.BigDecimal;
import java.util.List;

public final class CreatorSimilarityRequest {

    private CreatorSimilarityRequest() {
    }

    public record Replace(
            @NotNull @Positive Long creatorId,
            @Size(max = 20) String method,
            @Size(max = 255) String modelVersion,
            @Pattern(regexp = "[0-9a-f]{64}") String inputHash,
            @NotNull @Size(max = 100) List<@NotNull @Valid Candidate> candidates
    ) {
        public CreatorSimilarityResultCommand toCommand() {
            return new CreatorSimilarityResultCommand(
                    creatorId, method, modelVersion, inputHash,
                    candidates.stream().map(Candidate::toCommand).toList());
        }
    }

    public record Candidate(
            @NotNull @Positive Long creatorId,
            @NotNull @Positive Long similarCreatorId,
            @NotNull @DecimalMin("-1.0") @DecimalMax("2.0") @Digits(integer = 4, fraction = 8)
            BigDecimal score,
            @NotNull @Positive @Max(100) Integer rank,
            @NotBlank @Size(max = 20) String method,
            @NotBlank @Size(max = 255) String modelVersion,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String inputHash
    ) {
        private CreatorSimilarityResultCommand.Candidate toCommand() {
            return new CreatorSimilarityResultCommand.Candidate(
                    creatorId, similarCreatorId, score, rank, method, modelVersion, inputHash);
        }
    }
}

package kr.co.cking.interest.presentation.dto;

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
import java.math.BigDecimal;
import java.util.List;
import kr.co.cking.interest.application.dto.InterestRecommendationCommand;

public final class InterestRecommendationRequest {

    private InterestRecommendationRequest() {
    }

    /** 한 분야의 완결된 후보 묶음이다. 의미 검증(메타데이터 일치·정렬·분류체계 해시)은 서비스가 한다. */
    public record Replace(
            @NotNull @Positive Long applicationSequence,
            @NotBlank @Size(max = 20) String taxonomyVersion,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String taxonomyHash,
            @NotBlank @Size(max = 30) String interestCode,
            @NotBlank @Size(max = 20) String method,
            @NotBlank @Size(max = 255) String modelVersion,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String inputHash,
            @NotNull @Size(max = 100) List<@NotNull @Valid Candidate> candidates
    ) {
        public InterestRecommendationCommand toCommand() {
            return new InterestRecommendationCommand(applicationSequence,
                    taxonomyVersion, taxonomyHash, interestCode, method, modelVersion, inputHash,
                    candidates.stream().map(Candidate::toCommand).toList());
        }
    }

    public record Candidate(
            @NotBlank @Size(max = 30) String interestCode,
            @NotNull @Positive Long creatorId,
            @NotNull @DecimalMin("-1.0") @DecimalMax("2.0") @Digits(integer = 4, fraction = 8) BigDecimal score,
            @NotNull @Positive @Max(100) Integer rank,
            @NotBlank @Size(max = 20) String method,
            @NotBlank @Size(max = 255) String modelVersion,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String inputHash
    ) {
        private InterestRecommendationCommand.Candidate toCommand() {
            return new InterestRecommendationCommand.Candidate(
                    interestCode, creatorId, score, rank, method, modelVersion, inputHash);
        }
    }
}

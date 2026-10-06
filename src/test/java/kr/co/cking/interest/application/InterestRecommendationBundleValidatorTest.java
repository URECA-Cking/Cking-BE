package kr.co.cking.interest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.interest.application.InterestRecommendationBundleValidator.Bundle;
import kr.co.cking.interest.application.dto.InterestRecommendationCommand;
import kr.co.cking.interest.application.dto.InterestRecommendationCommand.Candidate;
import kr.co.cking.interest.domain.InterestErrorCode;
import org.junit.jupiter.api.Test;

class InterestRecommendationBundleValidatorTest {

    private static final String TAXONOMY_HASH = "f".repeat(64);
    private static final String INPUT_HASH = "a".repeat(64);

    @Test
    void 완결된_묶음은_rank_순으로_정규화한다() {
        InterestRecommendationCommand command = command(List.of(
                candidate(21L, "0.80000000", 2), candidate(20L, "0.90000000", 1)));

        Bundle bundle = InterestRecommendationBundleValidator.validate("SPORTS", command);

        assertThat(bundle.interestCode()).isEqualTo("SPORTS");
        assertThat(bundle.candidates()).extracting(InterestRecommendationBundleValidator.Candidate::creatorId)
                .containsExactly(20L, 21L);
    }

    @Test
    void 빈_후보_묶음도_최상위_메타데이터가_있으면_정상이다() {
        Bundle bundle = InterestRecommendationBundleValidator.validate("SPORTS", command(List.of()));

        assertThat(bundle.candidates()).isEmpty();
        assertThat(bundle.inputHash()).isEqualTo(INPUT_HASH);
    }

    @Test
    void 동점은_creatorId_오름차순이어야_한다() {
        assertThat(InterestRecommendationBundleValidator.validate("SPORTS", command(List.of(
                candidate(20L, "0.50000000", 1), candidate(21L, "0.50000000", 2)))).candidates()).hasSize(2);

        assertInvalid(command(List.of(candidate(21L, "0.50000000", 1), candidate(20L, "0.50000000", 2))));
    }

    @Test
    void 후보는_최대_100개다() {
        List<Candidate> hundred = new ArrayList<>();
        for (int index = 1; index <= 100; index++) {
            hundred.add(candidate(1000L + index, String.format("%.8f", 1.0 - index * 0.001), index));
        }
        assertThat(InterestRecommendationBundleValidator.validate("SPORTS", command(hundred)).candidates())
                .hasSize(100);

        List<Candidate> tooMany = new ArrayList<>(hundred);
        tooMany.add(candidate(2000L, "-0.90000000", 101));
        assertInvalid(command(tooMany));
    }

    @Test
    void Path_최상위_후보의_분야가_다르면_거부한다() {
        assertInvalidPath("FITNESS", command(List.of(candidate(20L, "0.9", 1))));
        assertInvalid(withCandidate(candidate(20L, "0.9", 1), c -> new Candidate(
                "FITNESS", c.creatorId(), c.score(), c.rank(), c.method(), c.modelVersion(), c.inputHash())));
    }

    @Test
    void 최상위와_후보의_메타데이터가_다르면_거부한다() {
        assertInvalid(withCandidate(candidate(20L, "0.9", 1), c -> new Candidate(
                c.interestCode(), c.creatorId(), c.score(), c.rank(), "OTHER", c.modelVersion(), c.inputHash())));
        assertInvalid(withCandidate(candidate(20L, "0.9", 1), c -> new Candidate(
                c.interestCode(), c.creatorId(), c.score(), c.rank(), c.method(), "other-model", c.inputHash())));
        assertInvalid(withCandidate(candidate(20L, "0.9", 1), c -> new Candidate(
                c.interestCode(), c.creatorId(), c.score(), c.rank(), c.method(), c.modelVersion(), "b".repeat(64))));
    }

    @Test
    void rank는_1부터_빈틈없이_이어져야_한다() {
        assertInvalid(command(List.of(candidate(20L, "0.9", 2))));
        assertInvalid(command(List.of(candidate(20L, "0.9", 1), candidate(21L, "0.8", 3))));
        assertInvalid(command(List.of(candidate(20L, "0.9", 1), candidate(21L, "0.8", 1))));
    }

    @Test
    void 점수는_내림차순이고_범위와_소수_자릿수를_지켜야_한다() {
        assertInvalid(command(List.of(candidate(20L, "0.1", 1), candidate(21L, "0.9", 2))));
        assertInvalid(command(List.of(candidate(20L, "2.00000001", 1))));
        assertInvalid(command(List.of(candidate(20L, "-1.00000001", 1))));
        assertInvalid(command(List.of(candidate(20L, "0.123456789", 1))));
        assertThat(InterestRecommendationBundleValidator.validate("SPORTS",
                command(List.of(candidate(20L, "2.0", 1), candidate(21L, "-1.0", 2)))).candidates()).hasSize(2);
    }

    @Test
    void 후보_Creator가_중복이거나_양수가_아니면_거부한다() {
        assertInvalid(command(List.of(candidate(20L, "0.9", 1), candidate(20L, "0.8", 2))));
        assertInvalid(command(List.of(candidate(0L, "0.9", 1))));
        assertInvalid(command(List.of(candidate(-5L, "0.9", 1))));
    }

    @Test
    void 해시와_메타데이터_형식을_지켜야_한다() {
        assertInvalid(replace(command(List.of()), "A".repeat(64), INPUT_HASH, "INTEREST_M3_V1", "SPORTS"));
        assertInvalid(replace(command(List.of()), TAXONOMY_HASH, "short", "INTEREST_M3_V1", "SPORTS"));
        assertInvalid(replace(command(List.of()), TAXONOMY_HASH, INPUT_HASH, " ", "SPORTS"));
        assertInvalid(replace(command(List.of()), TAXONOMY_HASH, INPUT_HASH, "x".repeat(21), "SPORTS"));
        assertInvalid(null);
    }

    @Test
    void null_후보와_null_값은_거부한다() {
        assertInvalid(command(Arrays.asList(candidate(20L, "0.9", 1), null)));
        assertInvalid(withCandidate(candidate(20L, "0.9", 1), c -> new Candidate(
                c.interestCode(), null, c.score(), c.rank(), c.method(), c.modelVersion(), c.inputHash())));
        assertInvalid(withCandidate(candidate(20L, "0.9", 1), c -> new Candidate(
                c.interestCode(), c.creatorId(), null, c.rank(), c.method(), c.modelVersion(), c.inputHash())));
        assertInvalid(withCandidate(candidate(20L, "0.9", 1), c -> new Candidate(
                c.interestCode(), c.creatorId(), c.score(), null, c.method(), c.modelVersion(), c.inputHash())));
    }

    private InterestRecommendationCommand command(List<Candidate> candidates) {
        return new InterestRecommendationCommand(
                "v0.2", TAXONOMY_HASH, "SPORTS", "INTEREST_M3_V1", "BAAI/bge-m3@deepinfra-v1", INPUT_HASH, candidates);
    }

    private InterestRecommendationCommand replace(
            InterestRecommendationCommand base, String taxonomyHash, String inputHash, String method, String code) {
        return new InterestRecommendationCommand(
                base.taxonomyVersion(), taxonomyHash, code, method, base.modelVersion(), inputHash, base.candidates());
    }

    private InterestRecommendationCommand withCandidate(Candidate candidate, UnaryOperator<Candidate> change) {
        return command(List.of(change.apply(candidate)));
    }

    private Candidate candidate(Long creatorId, String score, int rank) {
        return new Candidate(
                "SPORTS", creatorId, new BigDecimal(score), rank,
                "INTEREST_M3_V1", "BAAI/bge-m3@deepinfra-v1", INPUT_HASH);
    }

    private void assertInvalid(InterestRecommendationCommand command) {
        assertInvalidPath("SPORTS", command);
    }

    private void assertInvalidPath(String path, InterestRecommendationCommand command) {
        assertThatThrownBy(() -> InterestRecommendationBundleValidator.validate(path, command))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(InterestErrorCode.INVALID_RECOMMENDATION_RESULT));
    }
}

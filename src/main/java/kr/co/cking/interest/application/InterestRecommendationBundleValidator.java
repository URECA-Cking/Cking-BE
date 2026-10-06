package kr.co.cking.interest.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.interest.application.dto.InterestRecommendationCommand;
import kr.co.cking.interest.domain.InterestErrorCode;

/**
 * 후보 묶음의 형식·정렬·메타데이터 계약을 DB 없이 검증한다. 하나라도 어긋나면 묶음 전체를 거부한다.
 *
 * <p>Path·최상위·후보별 {@code interestCode}·{@code method}·{@code modelVersion}·{@code inputHash}는 모두 같아야 하고,
 * 후보는 {@code rank} 1부터 연속이며 점수 내림차순(동점은 {@code creatorId} 오름차순)이어야 한다.
 */
final class InterestRecommendationBundleValidator {

    static final int MAX_CANDIDATES = 100;
    private static final BigDecimal MIN_SCORE = new BigDecimal("-1.0");
    private static final BigDecimal MAX_SCORE = new BigDecimal("2.0");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private InterestRecommendationBundleValidator() {
    }

    record Bundle(
            String taxonomyVersion,
            String taxonomyHash,
            String interestCode,
            String method,
            String modelVersion,
            String inputHash,
            List<Candidate> candidates
    ) {
    }

    record Candidate(Long creatorId, BigDecimal score, int rank) {
    }

    static Bundle validate(String pathInterestCode, InterestRecommendationCommand command) {
        if (command == null
                || isBlank(command.taxonomyVersion()) || command.taxonomyVersion().length() > 20
                || !isSha256(command.taxonomyHash())
                || !Objects.equals(pathInterestCode, command.interestCode())
                || isBlank(command.method()) || command.method().length() > 20
                || isBlank(command.modelVersion()) || command.modelVersion().length() > 255
                || !isSha256(command.inputHash())
                || command.candidates() == null || command.candidates().size() > MAX_CANDIDATES) {
            throw invalid();
        }

        List<Candidate> candidates = new ArrayList<>();
        Set<Long> creatorIds = new HashSet<>();
        Set<Integer> ranks = new HashSet<>();
        for (InterestRecommendationCommand.Candidate candidate : command.candidates()) {
            if (candidate == null
                    || !Objects.equals(pathInterestCode, candidate.interestCode())
                    || candidate.creatorId() == null || candidate.creatorId() <= 0
                    || candidate.score() == null
                    || candidate.score().compareTo(MIN_SCORE) < 0 || candidate.score().compareTo(MAX_SCORE) > 0
                    || candidate.score().scale() > 8
                    || candidate.rank() == null || candidate.rank() <= 0
                    || !command.method().equals(candidate.method())
                    || !command.modelVersion().equals(candidate.modelVersion())
                    || !command.inputHash().equals(candidate.inputHash())
                    || !creatorIds.add(candidate.creatorId())
                    || !ranks.add(candidate.rank())) {
                throw invalid();
            }
            candidates.add(new Candidate(candidate.creatorId(), candidate.score(), candidate.rank()));
        }

        candidates.sort(Comparator.comparingInt(Candidate::rank));
        for (int index = 0; index < candidates.size(); index++) {
            if (candidates.get(index).rank() != index + 1
                    || (index > 0 && isOutOfOrder(candidates.get(index - 1), candidates.get(index)))) {
                throw invalid();
            }
        }
        return new Bundle(
                command.taxonomyVersion(), command.taxonomyHash(), command.interestCode(), command.method(),
                command.modelVersion(), command.inputHash(), List.copyOf(candidates));
    }

    private static boolean isOutOfOrder(Candidate previous, Candidate current) {
        int scoreOrder = previous.score().compareTo(current.score());
        return scoreOrder < 0 || (scoreOrder == 0 && previous.creatorId() > current.creatorId());
    }

    private static boolean isSha256(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static BusinessException invalid() {
        return new BusinessException(InterestErrorCode.INVALID_RECOMMENDATION_RESULT);
    }
}

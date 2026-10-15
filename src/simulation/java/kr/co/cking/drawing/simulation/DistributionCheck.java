package kr.co.cking.drawing.simulation;

/** 이론 확률을 귀무가설로 사용하는 양측 Bernstein 검정. 정규/카이제곱 근사는 하지 않는다. */
public record DistributionCheck(String scenario, String metric, long memberId, long trials,
                                long observed, double expectedProbability, double absoluteError,
                                double acceptanceLower, double acceptanceUpper,
                                double tailBound, double adjustedAlpha, String status) {
    public static DistributionCheck evaluate(String scenario, String metric, long memberId,
                                              long trials, long observed, double probability,
                                              double familyAlpha, int hypothesisCount) {
        if (trials < 0 || observed < 0 || observed > trials || !Double.isFinite(probability)
                || probability < 0 || probability > 1 || !(familyAlpha > 0 && familyAlpha < 1)
                || hypothesisCount <= 0) {
            throw new IllegalArgumentException("유효한 빈도, 확률, family alpha, 검정 개수가 필요합니다.");
        }
        double alpha = familyAlpha / hypothesisCount;
        if (trials == 0) {
            return new DistributionCheck(scenario, metric, memberId, 0, 0, probability,
                    Double.NaN, 0, 1, 1, alpha, "INSUFFICIENT");
        }
        double expected = trials * probability;
        double deviation = Math.abs(observed - expected);
        double variance = expected * (1 - probability);
        double log = Math.log(2 / alpha);
        double radius = Math.sqrt(2 * variance * log) + 2 * log / 3;
        double bound = deviation == 0 ? 1 : Math.min(1,
                2 * Math.exp(-deviation * deviation / (2 * (variance + deviation / 3))));
        // 확률 0/1인 계약은 통계 오차를 허용하지 않는다.
        boolean deterministic = probability == 0 || probability == 1;
        boolean passed = deterministic ? deviation == 0 : deviation <= radius;
        return new DistributionCheck(scenario, metric, memberId, trials, observed, probability,
                deviation / trials, Math.max(0, probability - (deterministic ? 0 : radius / trials)),
                Math.min(1, probability + (deterministic ? 0 : radius / trials)),
                bound, alpha, passed ? "PASS" : "FAIL");
    }

    public boolean passed() {
        return status.equals("PASS");
    }
}

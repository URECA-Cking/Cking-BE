package kr.co.cking.abuse.application.model;

import java.time.Duration;
import java.util.Objects;

/** Feature Store가 sliding window와 최근 EARN 상태를 갱신하는 데 필요한 시간 계약이다. */
public record AbuseFeatureWindowPolicy(
        Duration missionRequestWindow,
        Duration duplicateMissionWindow,
        Duration entryRequestWindow,
        Duration insufficientBalanceWindow,
        Duration requestIdRotationWindow,
        Duration rapidEarnSpendWindow,
        Duration rapidEarnSpendMaxDelay,
        Duration failureWindow
) {

    public AbuseFeatureWindowPolicy {
        requirePositive(missionRequestWindow, "missionRequestWindow");
        requirePositive(duplicateMissionWindow, "duplicateMissionWindow");
        requirePositive(entryRequestWindow, "entryRequestWindow");
        requirePositive(insufficientBalanceWindow, "insufficientBalanceWindow");
        requirePositive(requestIdRotationWindow, "requestIdRotationWindow");
        requirePositive(rapidEarnSpendWindow, "rapidEarnSpendWindow");
        requirePositive(rapidEarnSpendMaxDelay, "rapidEarnSpendMaxDelay");
        requirePositive(failureWindow, "failureWindow");
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + "는 필수입니다.");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + "는 양수여야 합니다.");
        }
    }
}

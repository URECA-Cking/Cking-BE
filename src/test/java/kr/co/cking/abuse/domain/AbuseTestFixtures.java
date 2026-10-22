package kr.co.cking.abuse.domain;

import java.util.Map;
import java.util.Set;

/** Abuse Domain 단위 테스트가 공유하는 유효한 최소 Evidence fixture다. */
public final class AbuseTestFixtures {

    /** 테스트 fixture 유틸리티의 인스턴스 생성을 막는다. */
    private AbuseTestFixtures() {
    }

    /** USER scope의 유효한 최소 DetectionEvidence를 생성한다. */
    public static DetectionEvidence userEvidence() {
        return new DetectionEvidence(
                DetectionEvidence.POLICY_VERSION,
                new DetectionEvidence.Scope(
                        DetectionEvidence.Scope.Type.USER,
                        null, null, null, null, null),
                new DetectionEvidence.Window(10_000L, null),
                Map.of(AbuseMetric.MISSION_REQUEST_COUNT, 5L),
                Map.of(AbuseMetric.MISSION_REQUEST_COUNT, 5L),
                Set.of(),
                Set.of());
    }
}

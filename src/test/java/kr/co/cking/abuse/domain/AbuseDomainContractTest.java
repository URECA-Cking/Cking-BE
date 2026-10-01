package kr.co.cking.abuse.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AbuseDomainContractTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant OBSERVED_AT = Instant.parse("2026-10-01T00:00:01Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void BalanceScope는_COMMON과_CREATOR의_식별자_불변식을_지킨다() {
        assertThat(BalanceScope.common().canonicalValue()).isEqualTo("COMMON");
        assertThat(BalanceScope.creator(10L).canonicalValue()).isEqualTo("CREATOR:10");

        assertThatThrownBy(() -> new BalanceScope(BalanceScope.Type.COMMON, 10L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BalanceScope.creator(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void Mission_Observation은_필수_업무_범위와_시간_순서를_검증한다() {
        AbuseObservationEvent observation = creatorMissionObservation(REQUESTED_AT, OBSERVED_AT);

        assertThat(observation.actionType()).isEqualTo(AbuseActionType.MISSION_COMPLETE);
        assertThat(observation.balanceScope()).isEqualTo(BalanceScope.creator(5L));

        assertThatThrownBy(() -> creatorMissionObservation(OBSERVED_AT, REQUESTED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestedAt");
        assertThatThrownBy(() -> new AbuseObservationEvent(
                UUID.randomUUID(), 1L, AbuseActionType.MISSION_COMPLETE, UUID.randomUUID(),
                "EARN_ACCEPTED", ResultClassification.NEW_SUCCESS,
                5L, 9L, 3L, "2026-10-01", BalanceScope.creator(5L),
                "MISSION:CREATOR:DAILY:1:5:3:2026-10-01", REQUESTED_AT, OBSERVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void Event_Observation은_Event와_BalanceScope_관계를_검증한다() {
        AbuseObservationEvent observation = new AbuseObservationEvent(
                UUID.randomUUID(), 1L, AbuseActionType.EVENT_ENTRY, UUID.randomUUID(),
                "SUCCESS", ResultClassification.NEW_SUCCESS,
                5L, 9L, null, null, BalanceScope.common(), null, REQUESTED_AT, OBSERVED_AT);

        assertThat(observation.eventId()).isEqualTo(9L);

        assertThatThrownBy(() -> new AbuseObservationEvent(
                UUID.randomUUID(), 1L, AbuseActionType.EVENT_ENTRY, UUID.randomUUID(),
                "SUCCESS", ResultClassification.NEW_SUCCESS,
                5L, 9L, null, null, BalanceScope.creator(6L), null, REQUESTED_AT, OBSERVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balanceScope");
    }

    @Test
    void DetectionEvidence는_입력_컬렉션을_불변_복사한다() {
        Map<AbuseMetric, Long> features = new HashMap<>();
        features.put(AbuseMetric.MISSION_REQUEST_COUNT, 5L);
        Set<AbuseSignal> signals = new HashSet<>();
        signals.add(AbuseSignal.REQUEST_ID_ROTATION);

        DetectionEvidence evidence = evidence(features, signals);
        features.put(AbuseMetric.FAILURE_COUNT, 3L);
        signals.clear();

        assertThat(evidence.features())
                .containsOnlyKeys(AbuseMetric.MISSION_REQUEST_COUNT)
                .isUnmodifiable();
        assertThat(evidence.signals()).containsExactly(AbuseSignal.REQUEST_ID_ROTATION).isUnmodifiable();
    }

    @Test
    void 새_Detection은_DETECTED로_생성되고_같은_검토는_멱등이다() {
        DetectionResult result = new DetectionResult(
                AbuseType.MISSION_REQUEST_BURST,
                HASH,
                OBSERVED_AT,
                evidence(Map.of(AbuseMetric.MISSION_REQUEST_COUNT, 5L), Set.of())
        );
        AbuseDetection detection = AbuseDetection.detected(1L, result);
        Instant reviewedAt = OBSERVED_AT.plusSeconds(10);

        detection.review(AbuseDetectionStatus.CONFIRMED, 99L, reviewedAt);
        detection.review(AbuseDetectionStatus.CONFIRMED, 100L, reviewedAt.plusSeconds(1));

        assertThat(detection.status()).isEqualTo(AbuseDetectionStatus.CONFIRMED);
        assertThat(detection.reviewedBy()).isEqualTo(99L);
        assertThat(detection.reviewedAt()).isEqualTo(reviewedAt);
        assertThatThrownBy(() -> detection.review(
                AbuseDetectionStatus.FALSE_POSITIVE, 100L, reviewedAt.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void DETECTED_복원에는_검토_정보를_지정할_수_없다() {
        assertThatThrownBy(() -> AbuseDetection.restore(
                1L,
                2L,
                AbuseType.FAILURE_BURST,
                AbuseDetectionStatus.DETECTED,
                OBSERVED_AT,
                OBSERVED_AT.plusSeconds(1),
                3L,
                evidence(Map.of(AbuseMetric.FAILURE_COUNT, 5L), Set.of())
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private AbuseObservationEvent creatorMissionObservation(Instant requestedAt, Instant observedAt) {
        return new AbuseObservationEvent(
                UUID.randomUUID(),
                1L,
                AbuseActionType.MISSION_COMPLETE,
                UUID.randomUUID(),
                "EARN_ACCEPTED",
                ResultClassification.NEW_SUCCESS,
                5L,
                null,
                3L,
                "2026-10-01",
                BalanceScope.creator(5L),
                "MISSION:CREATOR:DAILY:1:5:3:2026-10-01",
                requestedAt,
                observedAt
        );
    }

    private DetectionEvidence evidence(
            Map<AbuseMetric, Long> features,
            Set<AbuseSignal> signals
    ) {
        return new DetectionEvidence(
                DetectionEvidence.POLICY_VERSION,
                new DetectionEvidence.Scope(
                        DetectionEvidence.Scope.Type.USER,
                        null, null, null, null, null),
                new DetectionEvidence.Window(10_000L, null),
                features,
                Map.of(AbuseMetric.MISSION_REQUEST_COUNT, 5L),
                signals,
                Set.of()
        );
    }
}

package kr.co.cking.abuse.domain;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 원 업무가 확정한 결과를 Abuse 모듈에 전달하는 기술 독립 관찰 계약이다. */
public record AbuseObservationEvent(
        UUID observationId,
        Long userId,
        AbuseActionType actionType,
        UUID requestId,
        String resultCode,
        ResultClassification resultClassification,
        Long creatorId,
        Long eventId,
        Long missionId,
        String periodKey,
        BalanceScope balanceScope,
        String businessKey,
        Instant requestedAt,
        Instant observedAt
) {

    public AbuseObservationEvent {
        Objects.requireNonNull(observationId, "observationId는 필수입니다.");
        requirePositive(userId, "userId");
        Objects.requireNonNull(actionType, "actionType은 필수입니다.");
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");
        requireText(resultCode, "resultCode");
        Objects.requireNonNull(resultClassification, "resultClassification은 필수입니다.");
        Objects.requireNonNull(requestedAt, "requestedAt은 필수입니다.");
        Objects.requireNonNull(observedAt, "observedAt은 필수입니다.");
        Objects.requireNonNull(balanceScope, "balanceScope는 필수입니다.");
        if (requestedAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("requestedAt은 observedAt보다 늦을 수 없습니다.");
        }

        if (actionType == AbuseActionType.MISSION_COMPLETE) {
            requirePositive(missionId, "missionId");
            if (eventId != null) {
                throw new IllegalArgumentException("MISSION_COMPLETE에는 eventId를 지정할 수 없습니다.");
            }
            requireOptionalText(periodKey, "periodKey");
            requireText(businessKey, "businessKey");
            if (balanceScope.type() == BalanceScope.Type.COMMON && creatorId != null) {
                throw new IllegalArgumentException("공용 Mission에는 creatorId를 지정할 수 없습니다.");
            }
            if (balanceScope.type() == BalanceScope.Type.CREATOR) {
                requirePositive(creatorId, "creatorId");
                if (!creatorId.equals(balanceScope.creatorId())) {
                    throw new IllegalArgumentException("Creator Mission의 creatorId와 balanceScope가 다릅니다.");
                }
            }
        } else {
            requirePositive(eventId, "eventId");
            requirePositive(creatorId, "creatorId");
            if (balanceScope.type() == BalanceScope.Type.CREATOR
                    && !creatorId.equals(balanceScope.creatorId())) {
                throw new IllegalArgumentException("Event의 creatorId와 balanceScope가 다릅니다.");
            }
            if (missionId != null || periodKey != null || businessKey != null) {
                throw new IllegalArgumentException(
                        "EVENT_ENTRY에는 missionId, periodKey, businessKey를 지정할 수 없습니다.");
            }
        }

        if (creatorId != null) {
            requirePositive(creatorId, "creatorId");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "는 필수입니다.");
        }
    }

    private static void requireOptionalText(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + "는 값이 있으면 비어 있을 수 없습니다.");
        }
    }
}

package kr.co.cking.creator.application.dto;

import java.util.UUID;
import kr.co.cking.creator.domain.RecommendationEventType;

public record RecommendationEventCommand(UUID eventId, UUID recommendationRequestId,
                                         Long creatorId, RecommendationEventType eventType) {
}

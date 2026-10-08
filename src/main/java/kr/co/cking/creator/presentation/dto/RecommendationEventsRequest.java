package kr.co.cking.creator.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import kr.co.cking.creator.application.dto.RecommendationEventCommand;
import kr.co.cking.creator.domain.RecommendationEventType;

public record RecommendationEventsRequest(@NotNull @Size(min = 1, max = 50) List<@NotNull @Valid Event> events) {
    public record Event(@NotNull UUID eventId, @NotNull UUID recommendationRequestId,
                        @NotNull @Positive Long creatorId, @NotNull RecommendationEventType eventType) {
        public RecommendationEventCommand toCommand() {
            return new RecommendationEventCommand(eventId, recommendationRequestId, creatorId, eventType);
        }
    }
}

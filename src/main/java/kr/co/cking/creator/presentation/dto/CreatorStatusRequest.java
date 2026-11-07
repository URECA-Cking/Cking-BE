package kr.co.cking.creator.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import kr.co.cking.creator.application.CreatorStatusService;
import kr.co.cking.creator.domain.CreatorStatusReasonCode;

public record CreatorStatusRequest(
        @NotNull UUID requestId,
        @NotNull CreatorStatusReasonCode reasonCode,
        @NotBlank @Size(max = 1000) String detailReason,
        @NotBlank @Size(max = 500) String publicNotice
) {
    public CreatorStatusService.StatusCommand toCommand() {
        return new CreatorStatusService.StatusCommand(requestId, reasonCode, detailReason, publicNotice);
    }
}

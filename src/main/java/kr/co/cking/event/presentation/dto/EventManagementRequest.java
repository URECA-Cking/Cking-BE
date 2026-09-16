package kr.co.cking.event.presentation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.cking.event.domain.DrawMethod;

import java.time.LocalDateTime;

/** Creator Event 관리 API의 요청 본문을 정의한다. */
public final class EventManagementRequest {

    private EventManagementRequest() {
    }

    /** Event 생성 요청의 입력값을 전달한다. */
    public record Create(
            @NotNull Long userId,
            @NotBlank @Size(max = 36) String requestId,
            @NotBlank @Size(max = 200) String title,
            String description,
            @NotNull LocalDateTime startAt,
            @NotNull LocalDateTime endAt,
            @Min(1) int winnerCount,
            @NotNull DrawMethod drawMethod
    ) {
    }
}

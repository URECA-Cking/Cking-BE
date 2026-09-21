package kr.co.cking.event.presentation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.PrizeConfig;

import java.time.Instant;
import java.util.List;

/** Creator Event 관리 API의 요청 본문을 정의한다. */
public final class EventManagementRequest {

    private EventManagementRequest() {
    }

    /** Event 생성 요청의 입력값을 전달한다. */
    public record Create(
            @NotNull Long userId,
            @NotBlank @Size(max = 36) @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") String requestId,
            @NotBlank @Size(max = 200) String title,
            String description,
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            @Min(1) int winnerCount,
            @NotNull DrawMethod drawMethod,
            List<@Valid Prize> prizes
    ) {
        public List<PrizeConfig> prizeConfigs() {
            return prizes == null ? List.of() : prizes.stream().map(Prize::toConfig).toList();
        }
    }

    /** Event 승인 요청의 요청자 식별자를 전달한다. */
    public record Actor(@NotNull Long userId) {
    }

    /** Event 거절 심사의 관리자 식별자와 사유를 전달한다. */
    public record Reject(@NotNull Long userId, @NotBlank @Size(max = 500) String rejectReason) {
    }

    /** Event 수정 요청의 변경 값을 전달한다. */
    public record Update(@NotNull Long userId, @NotBlank @Size(max = 200) String title, String description,
                         @NotNull Instant startAt, @NotNull Instant endAt, @Min(1) int winnerCount,
                         @NotNull DrawMethod drawMethod, List<@Valid Prize> prizes) {
        public List<PrizeConfig> prizeConfigs() {
            return prizes == null ? List.of() : prizes.stream().map(Prize::toConfig).toList();
        }
    }

    /** 상품 등급별 확률 가중치와 배정 가능 수량을 전달한다. */
    public record Prize(
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String prizeKey,
            @NotBlank @Size(max = 200) String displayName,
            @Positive int priority,
            @Positive long weight,
            @Positive int quantity
    ) {
        public PrizeConfig toConfig() {
            return new PrizeConfig(prizeKey, displayName, priority, weight, quantity);
        }
    }
}

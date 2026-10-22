package kr.co.cking.event.presentation.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.PrizeConfig;
import kr.co.cking.drawing.domain.prize.PrizeAllocationAlgorithmVersion;

import java.time.Instant;
import java.util.List;

/** Creator Event 관리 API의 요청 본문을 정의한다. */
public final class EventManagementRequest {

    private EventManagementRequest() {
    }

    /** Event 생성 요청의 입력값을 전달한다. */
    public record Create(
            @NotBlank @Size(max = 36) @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") String requestId,
            @NotBlank @Size(max = 200) String title,
            String description,
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            @Min(1) int winnerCount,
            @NotNull DrawMethod drawMethod,
            PrizeAllocationAlgorithmVersion prizeAlgorithmVersion,
            List<@Valid Prize> prizes,
            /** 배너 업로드 API가 돌려준 키(이슈 #748). 생략·null이면 배너 없이 만든다. */
            @Size(max = 200) String bannerImageKey
    ) {
        public Create {
            prizeAlgorithmVersion = prizeAlgorithmVersion == null
                    ? PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1 : prizeAlgorithmVersion;
        }

        public List<PrizeConfig> prizeConfigs() {
            return prizes == null ? List.of() : prizes.stream().map(Prize::toConfig).toList();
        }
    }

    /** Event 거절 심사의 업무 입력인 사유를 전달한다. */
    public record Reject(@NotBlank @Size(max = 500) String rejectReason) {
    }

    /**
     * Event 수정 요청의 변경 값을 전달한다. 배너 키는 생략(유지)과 null(제거)을 구분해야 해서 record 대신 setter로 받는다.
     * 배너 외 필드는 모두 교체한다.
     */
    public static final class Update {
        @NotBlank @Size(max = 200)
        private String title;
        private String description;
        @NotNull
        private Instant startAt;
        @NotNull
        private Instant endAt;
        @Min(1)
        private int winnerCount;
        @NotNull
        private DrawMethod drawMethod;
        private PrizeAllocationAlgorithmVersion prizeAlgorithmVersion;
        private List<@Valid Prize> prizes;
        /** 배너 업로드 API가 돌려준 키(이슈 #748). 생략하면 유지, 키면 교체, null이면 배너를 뗀다. */
        @Size(max = 200)
        private String bannerImageKey;
        private boolean bannerImageKeyProvided;

        public String title() { return title; }
        public String description() { return description; }
        public Instant startAt() { return startAt; }
        public Instant endAt() { return endAt; }
        public int winnerCount() { return winnerCount; }
        public DrawMethod drawMethod() { return drawMethod; }
        public List<Prize> prizes() { return prizes; }
        public String bannerImageKey() { return bannerImageKey; }
        public boolean bannerImageKeyProvided() { return bannerImageKeyProvided; }

        public PrizeAllocationAlgorithmVersion prizeAlgorithmVersion() {
            return prizeAlgorithmVersion == null ? PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1 : prizeAlgorithmVersion;
        }

        public List<PrizeConfig> prizeConfigs() {
            return prizes == null ? List.of() : prizes.stream().map(Prize::toConfig).toList();
        }

        @JsonSetter("title")
        public void setTitle(String title) { this.title = title; }

        @JsonSetter("description")
        public void setDescription(String description) { this.description = description; }

        @JsonSetter("startAt")
        public void setStartAt(Instant startAt) { this.startAt = startAt; }

        @JsonSetter("endAt")
        public void setEndAt(Instant endAt) { this.endAt = endAt; }

        @JsonSetter("winnerCount")
        public void setWinnerCount(int winnerCount) { this.winnerCount = winnerCount; }

        @JsonSetter("drawMethod")
        public void setDrawMethod(DrawMethod drawMethod) { this.drawMethod = drawMethod; }

        @JsonSetter("prizeAlgorithmVersion")
        public void setPrizeAlgorithmVersion(PrizeAllocationAlgorithmVersion prizeAlgorithmVersion) {
            this.prizeAlgorithmVersion = prizeAlgorithmVersion;
        }

        @JsonSetter("prizes")
        public void setPrizes(List<Prize> prizes) { this.prizes = prizes; }

        @JsonSetter("bannerImageKey")
        public void setBannerImageKey(String bannerImageKey) {
            this.bannerImageKey = bannerImageKey;
            this.bannerImageKeyProvided = true;
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

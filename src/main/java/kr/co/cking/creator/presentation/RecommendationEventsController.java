package kr.co.cking.creator.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.creator.application.RecommendationTrackingService;
import kr.co.cking.creator.application.RecommendationTrackingObserver;
import kr.co.cking.creator.presentation.dto.RecommendationEventsRequest;
import kr.co.cking.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "추천 행동 수집", description = "실제 노출·클릭을 서버 추천 스냅샷에 연결합니다.")
public class RecommendationEventsController {
    private final RecommendationTrackingService service;
    private final RecommendationTrackingObserver observer;

    @PostMapping("/api/me/creator-recommendation-events")
    @Operation(summary = "추천 노출·클릭 수집", description = "JWT 회원의 반환 후보만 승인합니다. 최대 50건, 배치 전체 원자 처리, eventId 멱등입니다.")
    public ApiResponse<Result> collect(@CurrentMemberId Long memberId, @Valid @RequestBody RecommendationEventsRequest request) {
        try {
            int accepted = service.collect(memberId, request.events().stream().map(RecommendationEventsRequest.Event::toCommand).toList());
            observer.result("collect", "success");
            return ApiResponse.success(new Result(accepted));
        } catch (BusinessException rejected) {
            observer.result("collect", "rejected");
            throw rejected;
        } catch (RuntimeException failure) {
            observer.failed("collect", failure);
            throw failure;
        }
    }

    public record Result(int acceptedCount) { }
}

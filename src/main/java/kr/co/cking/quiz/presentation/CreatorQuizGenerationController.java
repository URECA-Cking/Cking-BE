package kr.co.cking.quiz.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.quiz.application.QuizGenerationService;
import kr.co.cking.quiz.presentation.dto.QuizContentConfirmationRequest;
import kr.co.cking.quiz.presentation.dto.QuizGenerationCurrentResponse;
import kr.co.cking.quiz.presentation.dto.QuizGenerationRequest;
import kr.co.cking.quiz.presentation.dto.QuizGenerationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증된 Creator 본인의 YouTube 영상 Quiz 생성 요청과 진행 상태 조회 HTTP 요청을 처리한다(이슈 #525).
 * 원문 검토 작업의 진행 중 작업 조회·원문 확정·검토 취소도 처리한다(이슈 #615).
 */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Creator AI Quiz 생성", description = "YouTube 영상으로 AI Quiz를 비동기로 생성하고 진행 상태를 조회하는 API를 제공합니다.")
public class CreatorQuizGenerationController {

    private final QuizGenerationService generationService;

    @Operation(
            summary = "YouTube 영상 Quiz 생성 요청",
            description = "요청을 접수하고 202를 반환합니다. 생성은 비동기로 진행되며 상태 조회 API로 확인합니다. 같은 requestId와 "
                    + "같은 입력은 기존 작업을 반환하고, 다른 입력은 409입니다. 진행 중인 작업이 있으면 409, 기능이 꺼져 있으면 503입니다."
    )
    @PostMapping("/api/creator/quizzes/generations")
    public ResponseEntity<ApiResponse<QuizGenerationResponse>> request(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody QuizGenerationRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                QuizGenerationResponse.from(generationService.request(memberId, request.toCommand()))));
    }

    @Operation(
            summary = "진행 중인 Quiz 생성 작업 조회",
            description = "본인의 진행 중(PENDING·RUNNING) 작업 하나를 반환하고, 없으면 data를 비웁니다. 조회 전에 오래된 작업을 "
                    + "정리합니다. 원문 검토 대기(reviewStage=AWAITING_REVIEW)일 때만 검토할 AI 원문과 facts를 content로 "
                    + "반환합니다."
    )
    @GetMapping("/api/creator/quizzes/generations/current")
    public ApiResponse<QuizGenerationCurrentResponse> findCurrent(@CurrentMemberId Long memberId) {
        return ApiResponse.success(generationService.findCurrent(memberId)
                .map(QuizGenerationCurrentResponse::from)
                .orElse(null));
    }

    @Operation(
            summary = "Grounding 원문 확정",
            description = "검토한 원문을 확정하고 그 원문으로 Quiz 생성을 시작합니다. 원문은 정규화하지 않으며 확정 후 바꿀 수 "
                    + "없습니다. 같은 원문 재요청은 현재 상태를 반환하고, 다른 원문은 409입니다. 검토 대기가 아니면 409, "
                    + "생성 기능이 꺼져 있으면 새 확정은 503입니다."
    )
    @PostMapping("/api/creator/quizzes/generations/{generationId}/content-confirmation")
    public ResponseEntity<ApiResponse<QuizGenerationResponse>> confirmContent(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long generationId,
            @Valid @RequestBody QuizContentConfirmationRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(QuizGenerationResponse.from(
                generationService.confirmContent(memberId, generationId, request.contentText()))));
    }

    @Operation(
            summary = "원문 검토 취소",
            description = "원문 검토 대기 작업을 취소해 진행 중 작업 제한을 풉니다. 이미 취소한 작업은 현재 상태를 반환합니다. "
                    + "검토 대기가 아니면 409입니다. 생성 기능이 꺼져 있어도 취소할 수 있습니다."
    )
    @PostMapping("/api/creator/quizzes/generations/{generationId}/cancel")
    public ApiResponse<QuizGenerationResponse> cancelReview(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long generationId
    ) {
        return ApiResponse.success(QuizGenerationResponse.from(generationService.cancelReview(memberId, generationId)));
    }

    @Operation(
            summary = "Quiz 생성 상태 조회",
            description = "본인 작업만 조회합니다. 성공하면 quizId·revisionId를, 실패하면 실패 코드를 반환합니다."
    )
    @GetMapping("/api/creator/quizzes/generations/{generationId}")
    public ApiResponse<QuizGenerationResponse> find(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long generationId
    ) {
        return ApiResponse.success(QuizGenerationResponse.from(generationService.find(memberId, generationId)));
    }
}

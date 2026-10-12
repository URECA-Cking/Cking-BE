package kr.co.cking.quiz.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.quiz.application.QuizGenerationService;
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

/** 인증된 Creator 본인의 YouTube 영상 Quiz 생성 요청과 진행 상태 조회 HTTP 요청을 처리한다(이슈 #525). */
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

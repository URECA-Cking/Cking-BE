package kr.co.cking.quiz.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.quiz.application.QuizJudgeEvaluationService;
import kr.co.cking.quiz.presentation.dto.QuizJudgeEvaluationRequest;
import kr.co.cking.quiz.presentation.dto.QuizJudgeEvaluationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Creator Quiz Judge", description = "본인 Quiz revision 평가 요청과 결과 조회 API입니다.")
public class CreatorQuizJudgeController {
    private final QuizJudgeEvaluationService evaluationService;

    @Operation(summary = "Quiz revision 평가 요청", description = "평가를 비동기 접수합니다. 같은 requestId 재요청은 기존 작업을 반환합니다. Creator 승인은 별도입니다.")
    @PostMapping("/api/creator/quizzes/{quizId}/revisions/{revisionId}/judge-evaluations")
    public ResponseEntity<ApiResponse<QuizJudgeEvaluationResponse>> request(@CurrentMemberId Long memberId,
            @PathVariable @Positive Long quizId, @PathVariable @Positive Long revisionId,
            @Valid @RequestBody QuizJudgeEvaluationRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                QuizJudgeEvaluationResponse.from(evaluationService.request(memberId, quizId, revisionId, request.requestId()))));
    }

    @Operation(summary = "Quiz revision 평가 상태 조회", description = "evaluationId는 Job ID입니다. 본인 작업의 상태와 완료된 문항별 평가만 반환합니다.")
    @GetMapping("/api/creator/quizzes/{quizId}/revisions/{revisionId}/judge-evaluations/{evaluationId}")
    public ApiResponse<QuizJudgeEvaluationResponse> find(@CurrentMemberId Long memberId,
            @PathVariable @Positive Long quizId, @PathVariable @Positive Long revisionId,
            @PathVariable @Positive Long evaluationId) {
        return ApiResponse.success(QuizJudgeEvaluationResponse.from(evaluationService.find(memberId, quizId, revisionId, evaluationId)));
    }
}

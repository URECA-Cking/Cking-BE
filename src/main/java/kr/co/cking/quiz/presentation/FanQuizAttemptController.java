package kr.co.cking.quiz.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.quiz.application.FanQuizAttemptService;
import kr.co.cking.quiz.application.dto.QuizAttemptResult;
import kr.co.cking.quiz.presentation.dto.QuizAttemptRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

@RestController @RequiredArgsConstructor @Validated
@Tag(name = "Fan Quiz 응시", description = "답안 제출과 본인 응시 결과 조회")
public class FanQuizAttemptController {
    private final FanQuizAttemptService service;
    @Operation(summary = "Quiz 답안 제출", description = "서버 채점 결과를 저장합니다. 신규와 동일 요청 재전송은 모두 200입니다.")
    @PostMapping("/api/creators/{creatorId}/quizzes/{quizId}/attempts")
    public ApiResponse<QuizAttemptResult> submit(@CurrentMemberId Long memberId,
            @PathVariable @Positive Long creatorId, @PathVariable @Positive Long quizId,
            @RequestBody @Valid QuizAttemptRequest request) {
        return ApiResponse.success(service.submit(request.toCommand(memberId, creatorId, quizId)));
    }
    @Operation(summary = "본인 Quiz 응시 결과 조회", description = "게시 취소 후에도 제출 당시의 저장된 결과를 반환합니다.")
    @GetMapping("/api/creators/{creatorId}/quizzes/{quizId}/my-attempt")
    public ApiResponse<QuizAttemptResult> findMine(@CurrentMemberId Long memberId,
            @PathVariable @Positive Long creatorId, @PathVariable @Positive Long quizId) {
        return ApiResponse.success(service.findMine(memberId, creatorId, quizId));
    }
}

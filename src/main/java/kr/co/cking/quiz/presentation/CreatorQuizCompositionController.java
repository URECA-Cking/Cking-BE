package kr.co.cking.quiz.presentation;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.quiz.application.*;
import kr.co.cking.quiz.presentation.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @Validated
public class CreatorQuizCompositionController {
    private final CreatorQuizCompositionService compositionService;
    private final CreatorQuizCommandService commandService;
    private final CreatorQuizQueryService queryService;
    private final QuizCompositionMetadataReader metadataReader;
    public record ApprovalRequest(String revisionSha256, Boolean acknowledgeResponsibility) {}
    @Operation(summary = "Creator 최종 문항 집합 저장", description = "원본을 보존하고 선택·수정·추가한 새 Revision을 저장합니다. 신규 201, 같은 UUID replay 200입니다.")
    @PostMapping("/api/creator/quizzes/{quizId}/revisions")
    public ResponseEntity<ApiResponse<CreatorQuizResponse.Revision>> compose(@CurrentMemberId Long memberId,
            @PathVariable @Positive Long quizId, @RequestBody QuizCompositionRequest request) {
        var admission=compositionService.compose(memberId,quizId,request.toCommand());
        var view=queryService.findRevision(memberId,quizId,admission.revisionId());
        var response=CreatorQuizResponse.Revision.from(view,metadataReader.read(quizId,admission.revisionId()));
        return ResponseEntity.status(admission.created()?HttpStatus.CREATED:HttpStatus.OK).body(ApiResponse.success(response));
    }
    @PostMapping("/api/creator/quizzes/{quizId}/revisions/{revisionId}/creator-approval")
    @Operation(summary = "Creator 최종 문항 책임 승인", description = "정확한 Revision 해시와 명시적 책임 확인을 기록합니다. Judge ALL_PASS를 필수로 요구하지 않습니다.")
    public ApiResponse<CreatorQuizResponse.Decision> approve(@CurrentMemberId Long memberId,
            @PathVariable @Positive Long quizId,@PathVariable @Positive Long revisionId,@RequestBody ApprovalRequest request) {
        return ApiResponse.success(CreatorQuizResponse.Decision.from(commandService.approveByCreator(memberId,quizId,revisionId,
                request.revisionSha256(),Boolean.TRUE.equals(request.acknowledgeResponsibility()))));
    }
}

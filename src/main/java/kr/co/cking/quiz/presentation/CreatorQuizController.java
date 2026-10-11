package kr.co.cking.quiz.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.quiz.application.CreatorQuizCommandService;
import kr.co.cking.quiz.application.CreatorQuizQueryService;
import kr.co.cking.quiz.presentation.dto.CreatorQuizRequest;
import kr.co.cking.quiz.presentation.dto.CreatorQuizResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 인증된 Creator 본인의 AI Quiz 조회·검토·게시 HTTP 요청을 처리한다(이슈 #521). */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Creator AI Quiz", description = "인증된 Creator 본인의 AI Quiz 조회, revision 승인·거절, 게시·게시 취소 API를 제공합니다.")
public class CreatorQuizController {

    private final CreatorQuizQueryService queryService;
    private final CreatorQuizCommandService commandService;

    @Operation(summary = "내 Quiz 목록 조회", description = "최근 생성 순으로 페이지 조회합니다. 제목과 검토 상태는 최신 revision 기준입니다.")
    @GetMapping("/api/creator/quizzes")
    public ApiResponse<PageResponse<CreatorQuizResponse.Summary>> findMine(
            @CurrentMemberId Long memberId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(PageResponse.from(
                queryService.findMine(memberId, PageRequest.of(page, size)).map(CreatorQuizResponse.Summary::from)));
    }

    @Operation(summary = "내 Quiz 상세 조회", description = "공개 상태, 게시 revision, revision 목록을 반환합니다.")
    @GetMapping("/api/creator/quizzes/{quizId}")
    public ApiResponse<CreatorQuizResponse.Detail> findDetail(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long quizId
    ) {
        return ApiResponse.success(CreatorQuizResponse.Detail.from(queryService.findDetail(memberId, quizId)));
    }

    @Operation(summary = "Quiz revision 상세 조회", description = "검토용으로 정답, 해설, 근거, 원본 텍스트를 함께 반환합니다.")
    @GetMapping("/api/creator/quizzes/{quizId}/revisions/{revisionId}")
    public ApiResponse<CreatorQuizResponse.Revision> findRevision(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long quizId,
            @PathVariable @Positive Long revisionId
    ) {
        return ApiResponse.success(CreatorQuizResponse.Revision.from(
                queryService.findRevision(memberId, quizId, revisionId)));
    }

    @Operation(
            summary = "Quiz revision 승인",
            description = "Judge 평가 검증을 통과한 revision만 승인할 수 있습니다. Judge 평가가 연동되기 전에는 항상 "
                    + "QUIZ_JUDGE_EVALUATION_REQUIRED입니다. 이미 승인한 revision은 기존 결정을 반환합니다."
    )
    @PostMapping("/api/creator/quizzes/{quizId}/revisions/{revisionId}/approve")
    public ApiResponse<CreatorQuizResponse.Decision> approve(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long quizId,
            @PathVariable @Positive Long revisionId
    ) {
        return ApiResponse.success(CreatorQuizResponse.Decision.from(
                commandService.approve(memberId, quizId, revisionId)));
    }

    @Operation(
            summary = "Quiz revision 거절",
            description = "Judge 평가 없이 거절할 수 있습니다. 사유는 선택입니다. 이미 거절한 revision은 기존 결정을 반환합니다."
    )
    @PostMapping("/api/creator/quizzes/{quizId}/revisions/{revisionId}/reject")
    public ApiResponse<CreatorQuizResponse.Decision> reject(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long quizId,
            @PathVariable @Positive Long revisionId,
            @Valid @RequestBody(required = false) CreatorQuizRequest.Reject request
    ) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.success(CreatorQuizResponse.Decision.from(
                commandService.reject(memberId, quizId, revisionId, reason)));
    }

    @Operation(
            summary = "Quiz 게시",
            description = "승인된 최신 revision을 게시합니다. 게시 직전에 저장 내용과 Judge 승인 근거를 다시 검증하며, Judge "
                    + "평가가 연동되기 전에는 항상 QUIZ_JUDGE_EVALUATION_REQUIRED입니다. 이미 같은 revision을 게시 중이면 "
                    + "현재 상태를 반환합니다."
    )
    @PostMapping("/api/creator/quizzes/{quizId}/publish")
    public ApiResponse<CreatorQuizResponse.Publication> publish(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long quizId,
            @Valid @RequestBody CreatorQuizRequest.Publish request
    ) {
        return ApiResponse.success(CreatorQuizResponse.Publication.from(
                commandService.publish(memberId, quizId, request.revisionId())));
    }

    @Operation(
            summary = "Quiz 게시 취소",
            description = "Judge 상태와 무관하게 게시를 취소합니다. revision과 결정 기록은 유지되며, 게시 중이 아니면 현재 상태를 반환합니다."
    )
    @PostMapping("/api/creator/quizzes/{quizId}/unpublish")
    public ApiResponse<CreatorQuizResponse.Publication> unpublish(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long quizId
    ) {
        return ApiResponse.success(CreatorQuizResponse.Publication.from(commandService.unpublish(memberId, quizId)));
    }
}

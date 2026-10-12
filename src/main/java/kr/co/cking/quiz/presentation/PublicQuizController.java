package kr.co.cking.quiz.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.quiz.application.PublicQuizQueryService;
import kr.co.cking.quiz.presentation.dto.PublicQuizResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 게시된 AI Quiz 공개 조회 HTTP 요청을 처리한다(이슈 #521). 로그인하지 않아도 조회할 수 있다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "AI Quiz 공개 조회", description = "로그인 없이 Creator가 게시한 AI Quiz를 조회하는 API를 제공합니다.")
public class PublicQuizController {

    private final PublicQuizQueryService queryService;

    @Operation(summary = "크리에이터 게시 Quiz 목록 조회", description = "게시 중인 Quiz를 최근 게시 순으로 페이지 조회합니다.")
    @GetMapping("/api/creators/{creatorId}/quizzes")
    public ApiResponse<PageResponse<PublicQuizResponse.Summary>> findPublished(
            @PathVariable @Positive Long creatorId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(PageResponse.from(
                queryService.findPublished(creatorId, PageRequest.of(page, size)).map(PublicQuizResponse.Summary::from)));
    }

    @Operation(
            summary = "크리에이터 게시 Quiz 상세 조회",
            description = "게시 중인 revision의 문항과 보기를 반환합니다. 정답과 해설은 포함하지 않습니다."
    )
    @GetMapping("/api/creators/{creatorId}/quizzes/{quizId}")
    public ApiResponse<PublicQuizResponse.Detail> findPublishedDetail(
            @PathVariable @Positive Long creatorId,
            @PathVariable @Positive Long quizId
    ) {
        return ApiResponse.success(PublicQuizResponse.Detail.from(queryService.findPublishedDetail(creatorId, quizId)));
    }
}

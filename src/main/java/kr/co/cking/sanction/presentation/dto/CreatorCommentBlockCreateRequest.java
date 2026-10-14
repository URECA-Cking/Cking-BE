package kr.co.cking.sanction.presentation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import kr.co.cking.sanction.application.SpaceCommentBlockService;

/**
 * 크리에이터의 스페이스 댓글 작성 차단 요청.
 *
 * @param memberId 차단할 회원. 업무 대상 ID이며 호출자(크리에이터)는 JWT로 식별한다
 * @param days     차단 기간(일). 1~365
 */
public record CreatorCommentBlockCreateRequest(
        @NotNull Long memberId,
        @NotNull @Min(1) @Max(SpaceCommentBlockService.MAX_DAYS) Integer days
) {
}

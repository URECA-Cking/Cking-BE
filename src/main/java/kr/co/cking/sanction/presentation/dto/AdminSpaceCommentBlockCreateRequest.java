package kr.co.cking.sanction.presentation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import kr.co.cking.sanction.application.SpaceCommentBlockService;

/**
 * 관리자의 스페이스 댓글 작성 차단 요청.
 *
 * @param creatorId 차단을 거는 스페이스의 주인(Creator)
 * @param memberId  차단할 회원
 * @param days      차단 기간(일). 1~365
 */
public record AdminSpaceCommentBlockCreateRequest(
        @NotNull Long creatorId,
        @NotNull Long memberId,
        @NotNull @Min(1) @Max(SpaceCommentBlockService.MAX_DAYS) Integer days
) {
}

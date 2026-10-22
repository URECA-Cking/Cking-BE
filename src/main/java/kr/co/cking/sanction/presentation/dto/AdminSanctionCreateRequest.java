package kr.co.cking.sanction.presentation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.cking.sanction.application.SanctionService;
import kr.co.cking.sanction.domain.MemberSanction;

/**
 * 관리자 수동 댓글 작성 제한 요청.
 *
 * @param memberId 제한할 회원
 * @param days     제한 기간(일). 1~365
 * @param reason   제한 사유. 1~200자
 */
public record AdminSanctionCreateRequest(
        @NotNull Long memberId,
        @NotNull @Min(1) @Max(SanctionService.MAX_MANUAL_DAYS) Integer days,
        @NotBlank @Size(max = MemberSanction.MAX_REASON_LENGTH) String reason
) {
}

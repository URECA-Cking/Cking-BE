package kr.co.cking.interest.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.interest.application.MemberInterestService;
import kr.co.cking.interest.presentation.dto.MemberInterestRequest;
import kr.co.cking.interest.presentation.dto.MemberInterestResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "내 관심 분야", description = "회원이 관심 분야를 조회하고 0~3개로 저장합니다.")
public class MemberInterestController {

    private final MemberInterestService service;

    @GetMapping("/api/me/interests")
    @Operation(
            summary = "내 관심 분야 조회",
            description = "선택한 관심 분야를 노출 순서대로 반환합니다. 선택이 없으면 빈 배열과 현재 활성 분류체계 버전을 반환합니다."
    )
    public ApiResponse<MemberInterestResponse> findMine(@CurrentMemberId Long memberId) {
        return ApiResponse.success(MemberInterestResponse.from(service.findMine(memberId)));
    }

    @PutMapping("/api/me/interests")
    @Operation(
            summary = "내 관심 분야 저장(전체 교체)",
            description = "기존 선택 전체를 요청 목록으로 교체합니다. 같은 요청을 반복해도 결과가 같고, 빈 배열은 전체 해제입니다."
    )
    public ApiResponse<MemberInterestResponse> replace(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody MemberInterestRequest.Replace request
    ) {
        return ApiResponse.success(MemberInterestResponse.from(
                service.replace(memberId, request.taxonomyVersion(), request.interestCodes())));
    }
}

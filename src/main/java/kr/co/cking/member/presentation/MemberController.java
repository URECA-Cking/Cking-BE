package kr.co.cking.member.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.member.application.MemberQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Member", description = "인증된 현재 사용자 기본 정보를 조회합니다.")
public class MemberController {

    private final MemberQueryService memberQueryService;
    private final CreatorQueryService creatorQueryService;

    /** Access JWT로 식별된 현재 Member의 프로필과 Creator 여부를 반환한다. */
    @Operation(summary = "현재 사용자 조회", description = "Access JWT로 인증된 사용자의 기본 정보와 Creator 여부를 반환합니다.")
    @GetMapping("/api/me")
    public ApiResponse<MyProfileResponse> getMyProfile(@CurrentMemberId Long memberId) {
        var profile = memberQueryService.getProfile(memberId);
        boolean creator = creatorQueryService.isCreatorMember(memberId);
        return ApiResponse.success(MyProfileResponse.from(profile, creator));
    }
}

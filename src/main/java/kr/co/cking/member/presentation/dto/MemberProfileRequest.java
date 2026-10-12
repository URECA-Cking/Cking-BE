package kr.co.cking.member.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cking.member.application.MemberProfileUpdateService;

/** 내 프로필 전체 교체 요청이다. 사진을 유지하려면 현재 key를, 기본 이미지로 바꾸려면 null을 보낸다. */
public record MemberProfileRequest(
        @NotBlank @Size(max = 20) String nickname,
        @Size(max = 200) String profileImageKey
) {
    public MemberProfileUpdateService.UpdateFields toFields() {
        return new MemberProfileUpdateService.UpdateFields(nickname, profileImageKey);
    }
}

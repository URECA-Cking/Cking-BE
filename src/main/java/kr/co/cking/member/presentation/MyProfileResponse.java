package kr.co.cking.member.presentation;

import kr.co.cking.member.application.MemberProfile;
import kr.co.cking.member.domain.MemberRole;

/** 인증된 호출자의 기본 프로필 응답이다. */
public record MyProfileResponse(Long memberId, String name, String email, MemberRole role, boolean creator) {

    public static MyProfileResponse from(MemberProfile profile, boolean creator) {
        return new MyProfileResponse(
                profile.memberId(), profile.name(), profile.email(), profile.role(), creator);
    }
}

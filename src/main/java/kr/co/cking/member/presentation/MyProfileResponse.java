package kr.co.cking.member.presentation;

import kr.co.cking.member.application.MemberProfile;
import kr.co.cking.member.domain.MemberRole;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 인증된 호출자의 기본 프로필 응답이다. */
public record MyProfileResponse(
        Long memberId,
        String name,
        String nickname,
        String email,
        MemberRole role,
        boolean creator,
        boolean onboardingCompleted,
        boolean profileSetupRequired,
        OffsetDateTime nicknameChangeableAt,
        String restorableNickname
) {

    public static MyProfileResponse from(MemberProfile profile, boolean creator) {
        return new MyProfileResponse(
                profile.memberId(), profile.name(), profile.nickname(), profile.email(), profile.role(), creator,
                profile.onboardingCompleted(), profile.nickname() == null,
                profile.nicknameChangeableAt() == null ? null : profile.nicknameChangeableAt().atOffset(ZoneOffset.UTC),
                profile.restorableNickname());
    }
}

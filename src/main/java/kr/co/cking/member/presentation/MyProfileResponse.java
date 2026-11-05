package kr.co.cking.member.presentation;

import kr.co.cking.member.application.MemberProfile;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.creator.domain.CreatorStatus;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 인증된 호출자의 기본 프로필 응답이다. */
public record MyProfileResponse(
        Long memberId,
        String name,
        String nickname,
        String profileImageUrl,
        String email,
        MemberRole role,
        boolean creator,
        CreatorStatus creatorStatus,
        boolean creatorCanOperate,
        boolean onboardingCompleted,
        boolean profileConfigured,
        OffsetDateTime nicknameChangeableAt,
        String restorableNickname
) {

    public static MyProfileResponse from(MemberProfile profile, boolean creator, CreatorStatus creatorStatus) {
        return new MyProfileResponse(
                profile.memberId(), profile.name(), profile.nickname(), profile.profileImageUrl(), profile.email(), profile.role(), creator,
                creatorStatus, creatorStatus == CreatorStatus.ACTIVE,
                profile.onboardingCompleted(), profile.nickname() != null,
                profile.nicknameChangeableAt() == null ? null : profile.nicknameChangeableAt().atOffset(ZoneOffset.UTC),
                profile.restorableNickname());
    }
}

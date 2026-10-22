package kr.co.cking.member.application;

import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;

import java.time.LocalDateTime;

/** 인증된 Member의 프로필 조회에 필요한 개인정보 범위를 표현한다. */
public record MemberProfile(
        Long memberId,
        String name,
        String nickname,
        String profileImageUrl,
        String email,
        MemberRole role,
        boolean onboardingCompleted,
        LocalDateTime nicknameChangeableAt,
        String restorableNickname
) {

    public static MemberProfile from(Member member, String profileImageUrl, String restorableNickname) {
        return new MemberProfile(
                member.getMemberId(), member.getName(), member.getNickname(), profileImageUrl, member.getEmail(), member.getRole(),
                member.isOnboardingCompleted(), member.nicknameChangeableAt(), restorableNickname);
    }
}

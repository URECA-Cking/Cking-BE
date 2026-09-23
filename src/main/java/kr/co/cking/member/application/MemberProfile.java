package kr.co.cking.member.application;

import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;

/** 인증된 Member의 프로필 조회에 필요한 개인정보 범위를 표현한다. */
public record MemberProfile(Long memberId, String name, String email, MemberRole role) {

    public static MemberProfile from(Member member) {
        return new MemberProfile(member.getMemberId(), member.getName(), member.getEmail(), member.getRole());
    }
}

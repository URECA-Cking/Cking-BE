package kr.co.cking.member.application;

import kr.co.cking.member.domain.Member;

public record MemberInfo(Long memberId, String name) {

    public static MemberInfo from(Member member) {
        return new MemberInfo(member.getMemberId(), member.getName());
    }
}

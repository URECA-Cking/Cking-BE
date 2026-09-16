package kr.co.cking.member.presentation;

import kr.co.cking.member.domain.Member;

public record UserSummary(Long userId, String name) {

    public static UserSummary from(Member member) {
        return new UserSummary(member.getMemberId(), member.getName());
    }
}

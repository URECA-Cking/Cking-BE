package kr.co.cking.member.presentation;

import kr.co.cking.member.domain.Member;

public record UserSelectionResponse(Long userId, String name, boolean selected) {

    public static UserSelectionResponse from(Member member) {
        return new UserSelectionResponse(member.getMemberId(), member.getName(), true);
    }
}

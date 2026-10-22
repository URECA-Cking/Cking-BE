package kr.co.cking.member.application;

import kr.co.cking.member.domain.MemberNickname;

/** 금칙어·예약어 정책을 추가할 확장 지점이다. 현재는 서비스 정책상 모두 허용한다. */
@FunctionalInterface
public interface MemberNicknameContentPolicy {

    void validate(MemberNickname nickname);
}

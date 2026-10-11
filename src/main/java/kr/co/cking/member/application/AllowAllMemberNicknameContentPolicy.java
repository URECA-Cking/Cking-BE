package kr.co.cking.member.application;

import kr.co.cking.member.domain.MemberNickname;
import org.springframework.stereotype.Component;

/** MVP에서는 금칙어·예약어를 실제 차단하지 않는다. */
@Component
class AllowAllMemberNicknameContentPolicy implements MemberNicknameContentPolicy {

    @Override
    public void validate(MemberNickname nickname) {
        // 후속 금칙어·예약어 정책 구현 시 이 Bean을 교체한다.
    }
}

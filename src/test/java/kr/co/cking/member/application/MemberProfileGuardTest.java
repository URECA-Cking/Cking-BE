package kr.co.cking.member.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberErrorCode;
import kr.co.cking.member.domain.MemberNickname;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;

class MemberProfileGuardTest {

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final MemberProfileGuard guard = new MemberProfileGuard(memberRepository);

    @Test
    void 닉네임이_없는_회원의_공개_활동을_막는다() {
        given(memberRepository.findById(1L)).willReturn(Optional.of(new Member("OAuth", null, null, MemberRole.USER)));

        assertThatThrownBy(() -> guard.requireConfigured(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(MemberErrorCode.PROFILE_SETUP_REQUIRED);
    }

    @Test
    void 닉네임이_있는_회원은_공개_활동을_할_수_있다() {
        Member member = new Member("OAuth", null, null, MemberRole.USER);
        member.setInitialNickname(MemberNickname.of("fan"));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        guard.requireConfigured(1L);
    }
}

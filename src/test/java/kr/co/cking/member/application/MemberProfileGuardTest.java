package kr.co.cking.member.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberErrorCode;
import kr.co.cking.member.domain.MemberNickname;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.creator.application.CreatorQueryService;
import org.junit.jupiter.api.Test;

class MemberProfileGuardTest {

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorQueryService creatorQueryService = mock(CreatorQueryService.class);
    private final MemberProfileGuard guard = new MemberProfileGuard(memberRepository, creatorQueryService);

    @Test
    void 닉네임이_없는_회원의_공개_활동을_막는다() {
        given(memberRepository.findById(1L)).willReturn(Optional.of(new Member("OAuth", null, null, MemberRole.USER)));
        given(creatorQueryService.requireActivityNameConfiguredForCreator(1L)).willReturn(false);

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
        given(creatorQueryService.requireActivityNameConfiguredForCreator(1L)).willReturn(false);

        guard.requireConfigured(1L);
    }

    @Test
    void 활동명이_설정된_Creator는_Member_닉네임_없이도_공개_활동을_할_수_있다() {
        given(memberRepository.findById(1L)).willReturn(Optional.of(new Member("OAuth", null, null, MemberRole.USER)));
        given(creatorQueryService.requireActivityNameConfiguredForCreator(1L)).willReturn(true);

        guard.requireConfigured(1L);
    }

    @Test
    void 한_번_통과한_회원은_DB를_다시_조회하지_않는다() {
        Member member = new Member("OAuth", null, null, MemberRole.USER);
        member.setInitialNickname(MemberNickname.of("fan"));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(creatorQueryService.requireActivityNameConfiguredForCreator(1L)).willReturn(false);

        guard.requireConfigured(1L);
        guard.requireConfigured(1L);

        verify(memberRepository, times(1)).findById(1L);
        verify(creatorQueryService, times(1)).requireActivityNameConfiguredForCreator(1L);
    }

    @Test
    void 거절된_회원은_다음_요청에서도_DB로_다시_확인한다() {
        given(memberRepository.findById(1L)).willReturn(Optional.of(new Member("OAuth", null, null, MemberRole.USER)));
        given(creatorQueryService.requireActivityNameConfiguredForCreator(1L)).willReturn(false);

        assertThatThrownBy(() -> guard.requireConfigured(1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> guard.requireConfigured(1L)).isInstanceOf(BusinessException.class);

        verify(memberRepository, times(2)).findById(1L);
    }

    @Test
    void 닉네임을_설정한_뒤에는_바로_통과한다() {
        Member member = new Member("OAuth", null, null, MemberRole.USER);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(creatorQueryService.requireActivityNameConfiguredForCreator(1L)).willReturn(false);
        assertThatThrownBy(() -> guard.requireConfigured(1L)).isInstanceOf(BusinessException.class);

        member.setInitialNickname(MemberNickname.of("fan"));

        guard.requireConfigured(1L);
    }
}

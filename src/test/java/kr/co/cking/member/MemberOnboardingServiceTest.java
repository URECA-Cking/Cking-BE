package kr.co.cking.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberOnboardingService;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;

class MemberOnboardingServiceTest {

    private final MemberRepository repository = mock(MemberRepository.class);
    private final MemberOnboardingService service = new MemberOnboardingService(repository);

    @Test
    void 온보딩_완료는_여러_번_호출해도_완료_상태다() {
        Member member = new Member("홍길동", null, null, MemberRole.USER);
        when(repository.findById(1L)).thenReturn(Optional.of(member));

        assertThat(member.isOnboardingCompleted()).isFalse();
        service.complete(1L);
        service.complete(1L);

        assertThat(member.isOnboardingCompleted()).isTrue();
    }

    @Test
    void 존재하지_않는_사용자의_온보딩_완료는_RESOURCE_NOT_FOUND다() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }
}

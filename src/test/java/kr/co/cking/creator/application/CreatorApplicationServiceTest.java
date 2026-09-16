package kr.co.cking.creator.application;

import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.domain.CreatorApplicationStatus;
import kr.co.cking.creator.repository.CreatorApplicationRepository;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class CreatorApplicationServiceTest {

    @Test
    void pendingApplicationIsReturnedInsteadOfCreatingAnotherOne() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        CreatorRepository creatorRepository = mock(CreatorRepository.class);
        CreatorApplicationRepository applicationRepository = mock(CreatorApplicationRepository.class);
        CreatorApplication existing = new CreatorApplication(10L);
        given(memberRepository.existsById(10L)).willReturn(true);
        given(creatorRepository.existsByMemberId(10L)).willReturn(false);
        given(applicationRepository.findFirstByMemberIdAndStatusOrderByIdDesc(10L, CreatorApplicationStatus.PENDING))
                .willReturn(Optional.of(existing));
        CreatorApplicationService service = new CreatorApplicationService(
                memberRepository, creatorRepository, applicationRepository
        );

        CreatorApplication result = service.apply(10L);

        assertThat(result).isSameAs(existing);
    }
}

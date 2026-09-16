package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.domain.CreatorApplicationStatus;
import kr.co.cking.creator.repository.CreatorApplicationRepository;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

class CreatorApplicationServiceTest {

    @Test
    void pendingApplicationIsReturnedInsteadOfCreatingAnotherOne() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        CreatorRepository creatorRepository = mock(CreatorRepository.class);
        CreatorApplicationRepository applicationRepository = mock(CreatorApplicationRepository.class);
        CreatorApplicationLockManager lockManager = mock(CreatorApplicationLockManager.class);
        runCommands(lockManager);
        CreatorApplication existing = new CreatorApplication(10L);
        given(memberRepository.existsById(10L)).willReturn(true);
        given(creatorRepository.existsByMemberId(10L)).willReturn(false);
        given(applicationRepository.findFirstByMemberIdAndStatusOrderByIdDesc(10L, CreatorApplicationStatus.PENDING))
                .willReturn(Optional.of(existing));
        CreatorApplicationService service = new CreatorApplicationService(
                memberRepository, creatorRepository, applicationRepository, lockManager
        );

        CreatorApplicationService.ApplyResult result = service.apply(10L);

        assertThat(result.application()).isSameAs(existing);
        assertThat(result.created()).isFalse();
    }

    @Test
    void adminApprovalMarksApplicationApprovedAndCreatesCreatorForApplicant() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        CreatorRepository creatorRepository = mock(CreatorRepository.class);
        CreatorApplicationRepository applicationRepository = mock(CreatorApplicationRepository.class);
        CreatorApplicationLockManager lockManager = mock(CreatorApplicationLockManager.class);
        runCommands(lockManager);
        Member admin = new Member("관리자", null, null, MemberRole.ADMIN);
        Member applicant = new Member("신청자", null, null, MemberRole.USER);
        CreatorApplication application = new CreatorApplication(10L);
        given(memberRepository.findById(1L)).willReturn(Optional.of(admin));
        given(memberRepository.findById(10L)).willReturn(Optional.of(applicant));
        given(applicationRepository.findById(100L)).willReturn(Optional.of(application));
        given(creatorRepository.existsByMemberId(10L)).willReturn(false);
        CreatorApplicationService service = new CreatorApplicationService(
                memberRepository, creatorRepository, applicationRepository, lockManager
        );

        service.approve(1L, 100L);

        assertThat(application.getStatus()).isEqualTo(CreatorApplicationStatus.APPROVED);
        assertThat(application.getReviewedBy()).isEqualTo(1L);
        ArgumentCaptor<Creator> creatorCaptor = ArgumentCaptor.forClass(Creator.class);
        verify(creatorRepository).save(creatorCaptor.capture());
        assertThat(creatorCaptor.getValue().getMemberId()).isEqualTo(10L);
        assertThat(creatorCaptor.getValue().getName()).isEqualTo("신청자");
    }

    @Test
    void nonAdminCannotRejectApplication() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        CreatorRepository creatorRepository = mock(CreatorRepository.class);
        CreatorApplicationRepository applicationRepository = mock(CreatorApplicationRepository.class);
        CreatorApplicationLockManager lockManager = mock(CreatorApplicationLockManager.class);
        runCommands(lockManager);
        Member user = new Member("일반사용자", null, null, MemberRole.USER);
        given(memberRepository.findById(1L)).willReturn(Optional.of(user));
        CreatorApplicationService service = new CreatorApplicationService(
                memberRepository, creatorRepository, applicationRepository, lockManager
        );

        assertThatThrownBy(() -> service.reject(1L, 100L, "사유"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    private void runCommands(CreatorApplicationLockManager lockManager) {
        given(lockManager.execute(any(), any())).willAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
    }
}

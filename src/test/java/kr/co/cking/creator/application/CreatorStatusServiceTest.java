package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorStatus;
import kr.co.cking.creator.domain.CreatorStatusHistory;
import kr.co.cking.creator.domain.CreatorStatusReasonCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorStatusHistoryRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CreatorStatusServiceTest {
    private final CreatorRepository creators = mock(CreatorRepository.class);
    private final CreatorStatusHistoryRepository histories = mock(CreatorStatusHistoryRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final CreatorApplicationLockManager locks = mock(CreatorApplicationLockManager.class);
    private final CreatorStatusService service = new CreatorStatusService(creators, histories, members, locks);
    private final UUID requestId = UUID.randomUUID();

    @Test
    void 배포_스위치가_꺼져_있으면_자격_변경을_접수하지_않는다() {
        var command = new CreatorStatusService.StatusCommand(requestId,
                CreatorStatusReasonCode.POLICY_VIOLATION, "내부 사유", "공개 안내");

        assertThatThrownBy(() -> service.change(7L, 11L, CreatorStatus.SUSPENDED, command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CreatorErrorCode.CREATOR_STATUS_MANAGEMENT_DISABLED);
        verifyNoInteractions(locks, creators, histories);
    }

    @Test
    void 상태와_이력을_함께_변경하고_같은_요청은_기존_결과를_반환한다() {
        ReflectionTestUtils.setField(service, "statusManagementEnabled", true);
        Member admin = mock(Member.class);
        when(admin.getRole()).thenReturn(MemberRole.ADMIN);
        when(members.findById(7L)).thenReturn(Optional.of(admin));
        when(locks.executeWaiting(anyString(), anyInt(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(2)).get());
        Creator creator = new Creator(9L, "creator");
        ReflectionTestUtils.setField(creator, "creatorId", 11L);
        when(creators.findByIdForUpdate(11L)).thenReturn(Optional.of(creator));
        when(histories.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var command = new CreatorStatusService.StatusCommand(requestId,
                CreatorStatusReasonCode.POLICY_VIOLATION, "내부 사유", "공개 안내");

        CreatorStatusHistory first = service.change(7L, 11L, CreatorStatus.SUSPENDED, command);
        assertThat(creator.getStatus()).isEqualTo(CreatorStatus.SUSPENDED);
        assertThat(first.getPreviousStatus()).isEqualTo(CreatorStatus.ACTIVE);
        assertThat(first.getDetailReason()).isEqualTo("내부 사유");

        when(histories.findByRequestId(requestId.toString())).thenReturn(Optional.of(first));
        assertThat(service.change(7L, 11L, CreatorStatus.SUSPENDED, command)).isSameAs(first);
        assertThatThrownBy(() -> service.change(7L, 11L, CreatorStatus.REVOKED, command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CreatorErrorCode.CREATOR_STATUS_REQUEST_CONFLICT);
    }
}

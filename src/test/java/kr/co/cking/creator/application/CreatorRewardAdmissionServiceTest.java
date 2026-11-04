package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.ticket.application.TicketEarnService;
import kr.co.cking.ticket.application.TicketOnceEarnService;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import kr.co.cking.ticket.application.dto.EarnResult;
import org.junit.jupiter.api.Test;

class CreatorRewardAdmissionServiceTest {
    private final CreatorRepository creators = mock(CreatorRepository.class);
    private final TicketEarnService daily = mock(TicketEarnService.class);
    private final TicketOnceEarnService once = mock(TicketOnceEarnService.class);
    private final CreatorRewardAdmissionService service = new CreatorRewardAdmissionService(creators, daily, once);

    @Test
    void 정지_이후_새_보상은_거부하고_이미_수락된_요청만_재현한다() {
        EarnCommand command = new EarnCommand(UUID.randomUUID(), 1L, 2L, "LIKE", 3L,
                "2026-11-04", "like:2", 1L);
        Creator creator = mock(Creator.class);
        org.mockito.Mockito.doThrow(new BusinessException(CreatorErrorCode.CREATOR_NOT_ACTIVE))
                .when(creator).requireActive();
        when(creators.findById(2L)).thenReturn(Optional.of(creator));
        when(daily.findExisting(command)).thenReturn(new EarnLookupResult(EarnLookupStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.earnDaily(command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CreatorErrorCode.CREATOR_NOT_ACTIVE);
        verifyNoInteractions(once);

        when(daily.findExisting(command)).thenReturn(new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED));
        assertThat(service.earnDaily(command).code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        org.mockito.Mockito.verify(daily, org.mockito.Mockito.never()).earn(any());
    }
}

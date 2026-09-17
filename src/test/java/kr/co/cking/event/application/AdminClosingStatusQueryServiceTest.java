package kr.co.cking.event.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.application.service.EventClosingService;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.member.application.MemberQueryService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AdminClosingStatusQueryServiceTest {

    /** 관리자는 시스템2가 조회한 마감 진행 상태를 그대로 받는다. */
    @Test
    void adminGetsClosingStatus() {
        MemberQueryService memberQueryService = mock(MemberQueryService.class);
        EventClosingService eventClosingService = mock(EventClosingService.class);
        given(eventClosingService.getClosingStatus(10L)).willReturn(EventStatus.CLOSING);

        EventStatus status = service(memberQueryService, eventClosingService).getClosingStatus(1L, 10L);

        assertThat(status).isEqualTo(EventStatus.CLOSING);
        verify(memberQueryService).validateAdmin(1L);
        verify(eventClosingService).getClosingStatus(10L);
    }

    /** 완료된 Event도 시스템2가 조회한 CLOSED 상태를 그대로 받는다. */
    @Test
    void adminGetsClosedStatus() {
        MemberQueryService memberQueryService = mock(MemberQueryService.class);
        EventClosingService eventClosingService = mock(EventClosingService.class);
        given(eventClosingService.getClosingStatus(10L)).willReturn(EventStatus.CLOSED);

        EventStatus status = service(memberQueryService, eventClosingService).getClosingStatus(1L, 10L);

        assertThat(status).isEqualTo(EventStatus.CLOSED);
    }

    /** 존재하지 않거나 관리자가 아닌 요청자는 시스템2 조회 전에 거절한다. */
    @Test
    void invalidAdminDoesNotCallSystem2() {
        MemberQueryService memberQueryService = mock(MemberQueryService.class);
        EventClosingService eventClosingService = mock(EventClosingService.class);
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN)).when(memberQueryService).validateAdmin(1L);

        assertThatThrownBy(() -> service(memberQueryService, eventClosingService).getClosingStatus(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
        verify(eventClosingService, never()).getClosingStatus(10L);
    }

    /** 조회 서비스에 필요한 의존성을 조립한다. */
    private AdminClosingStatusQueryService service(
            MemberQueryService memberQueryService,
            EventClosingService eventClosingService
    ) {
        return new AdminClosingStatusQueryService(memberQueryService, eventClosingService);
    }
}

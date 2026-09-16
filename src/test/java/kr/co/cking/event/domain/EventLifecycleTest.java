package kr.co.cking.event.domain;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.repository.EventRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class EventLifecycleTest {

    @Test
    void draftEventCanRequestApprovalOnlyOnce() {
        Event event = new Event(
                1L,
                "팬미팅",
                "설명",
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1),
                LocalDateTime.now(ZoneOffset.UTC).plusDays(2),
                3,
                DrawMethod.WEIGHTED,
                1L,
                "550e8400-e29b-41d4-a716-446655440000"
        );

        event.requestApproval();

        assertThat(event.getStatus()).isEqualTo(EventStatus.PENDING_APPROVAL);
        assertThatThrownBy(event::requestApproval)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(EventErrorCode.INVALID_STATE);
    }

    @Test
    void commandServiceTransitionsPendingEventToScheduled() {
        Event event = new Event(
                1L,
                "팬미팅",
                "설명",
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1),
                LocalDateTime.now(ZoneOffset.UTC).plusDays(2),
                3,
                DrawMethod.WEIGHTED,
                1L,
                "550e8400-e29b-41d4-a716-446655440000"
        );
        event.requestApproval();
        EventRepository eventRepository = mock(EventRepository.class);
        given(eventRepository.findById(1L)).willReturn(java.util.Optional.of(event));
        EventCommandService eventCommandService = new EventCommandService(eventRepository);

        eventCommandService.approve(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.SCHEDULED);
    }

    @Test
    void commandServiceRejectsPendingEventAndAllowsReturnToDraft() {
        Event event = new Event(
                1L,
                "팬미팅",
                "설명",
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1),
                LocalDateTime.now(ZoneOffset.UTC).plusDays(2),
                3,
                DrawMethod.WEIGHTED,
                1L,
                "550e8400-e29b-41d4-a716-446655440000"
        );
        event.requestApproval();
        EventRepository eventRepository = mock(EventRepository.class);
        given(eventRepository.findById(1L)).willReturn(java.util.Optional.of(event));
        EventCommandService eventCommandService = new EventCommandService(eventRepository);

        eventCommandService.reject(1L, "일정 확인 필요");
        eventCommandService.changeToDraft(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
    }
}

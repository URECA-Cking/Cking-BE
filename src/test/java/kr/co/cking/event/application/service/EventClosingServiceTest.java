package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;

@ExtendWith(MockitoExtension.class)
class EventClosingServiceTest {

    @Mock
    private EventCutoffBarrier eventCutoffBarrier;

    @Mock
    private EventCommandService eventCommandService;

    @Mock
    private EventRepository eventRepository;

    @InjectMocks
    private EventClosingService eventClosingService;

    @Test
    void Gate와_cutoff를_확정한_뒤_CLOSING_전이를_요청한다() {
        when(eventRepository.findById(1L)).thenReturn(java.util.Optional.of(eventOf(EventStatus.OPEN)));
        when(eventCutoffBarrier.close(1L)).thenReturn("123-0");
        when(eventCommandService.startClosing(1L, "123-0")).thenReturn(EventStatus.CLOSING);

        EventClosingService.ClosingResult result = eventClosingService.startClosing(1L);

        InOrder inOrder = inOrder(eventCutoffBarrier, eventCommandService);
        inOrder.verify(eventCutoffBarrier).close(1L);
        inOrder.verify(eventCommandService).startClosing(1L, "123-0");
        assertThat(result).isEqualTo(new EventClosingService.ClosingResult(1L, EventStatus.CLOSING));
    }

    @Test
    void 이미_CLOSING이면_Redis를_건드리지_않고_현재_상태를_반환한다() {
        when(eventRepository.findById(1L)).thenReturn(java.util.Optional.of(eventOf(EventStatus.CLOSING)));

        EventClosingService.ClosingResult result = eventClosingService.startClosing(1L);

        assertThat(result).isEqualTo(new EventClosingService.ClosingResult(1L, EventStatus.CLOSING));
        verifyNoInteractions(eventCutoffBarrier, eventCommandService);
    }

    @Test
    void 이미_CLOSED면_Redis를_건드리지_않고_현재_상태를_반환한다() {
        when(eventRepository.findById(1L)).thenReturn(java.util.Optional.of(eventOf(EventStatus.CLOSED)));

        EventClosingService.ClosingResult result = eventClosingService.startClosing(1L);

        assertThat(result).isEqualTo(new EventClosingService.ClosingResult(1L, EventStatus.CLOSED));
        verifyNoInteractions(eventCutoffBarrier, eventCommandService);
    }

    @Test
    void OPEN이_아닌_잘못된_상태면_Redis를_건드리기_전에_실패한다() {
        when(eventRepository.findById(1L)).thenReturn(java.util.Optional.of(eventOf(EventStatus.DRAFT)));

        assertThatThrownBy(() -> eventClosingService.startClosing(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_STATE);
        verifyNoInteractions(eventCutoffBarrier, eventCommandService);
    }

    /** 마감 진행 중이거나 완료된 Event의 상태만 조회한다. */
    @Test
    void CLOSING과_CLOSED_상태만_조회한다() {
        when(eventRepository.findById(1L)).thenReturn(java.util.Optional.of(eventOf(EventStatus.CLOSING)));

        assertThat(eventClosingService.getClosingStatus(1L)).isEqualTo(EventStatus.CLOSING);

        when(eventRepository.findById(1L)).thenReturn(java.util.Optional.of(eventOf(EventStatus.CLOSED)));

        assertThat(eventClosingService.getClosingStatus(1L)).isEqualTo(EventStatus.CLOSED);
        verifyNoInteractions(eventCutoffBarrier, eventCommandService);
    }

    /** 존재하지 않거나 아직 마감되지 않은 Event의 상태 조회를 거절한다. */
    @Test
    void 조회할_수_없는_Event는_오류를_반환한다() {
        when(eventRepository.findById(1L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> eventClosingService.getClosingStatus(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.RESOURCE_NOT_FOUND);

        when(eventRepository.findById(1L)).thenReturn(java.util.Optional.of(eventOf(EventStatus.OPEN)));

        assertThatThrownBy(() -> eventClosingService.getClosingStatus(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_STATE);
    }

    /** 테스트에 필요한 최소 Event 상태 객체를 만든다. */
    private Event eventOf(EventStatus status) {
        return Event.builder()
                .status(status)
                .startAt(Instant.parse("2026-09-10T00:00:00Z"))
                .endAt(Instant.parse("2026-09-20T00:00:00Z"))
                .build();
    }
}

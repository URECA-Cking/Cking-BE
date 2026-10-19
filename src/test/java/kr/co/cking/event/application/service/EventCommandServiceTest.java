package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;

@ExtendWith(MockitoExtension.class)
class EventCommandServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-20T00:05:00Z");

    @Mock
    private EventRepository eventRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private EventCommandService eventCommandService;

    @BeforeEach
    void setUp() {
        eventCommandService = new EventCommandService(
                eventRepository, eventPublisher, Clock.fixed(FIXED_NOW, ZoneOffset.UTC), Duration.ofHours(48));
    }

    @Test
    void startClosing은_OPEN_이벤트를_CLOSING으로_전이하고_커밋후_캐시무효화_이벤트를_발행한다() {
        Event event = eventOf(EventStatus.OPEN);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        EventStatus result = eventCommandService.startClosing(1L, "123-0");

        assertThat(result).isEqualTo(EventStatus.CLOSING);
        assertThat(event.getStatus()).isEqualTo(EventStatus.CLOSING);
        assertThat(event.getCutoffStreamId()).isEqualTo("123-0");
        verify(eventPublisher).publishEvent(new EventClosingStateChangedEvent(1L));
    }

    @Test
    void startClosing은_동일_cutoff로_이미_CLOSING이면_멱등하게_아무것도_하지_않는다() {
        Event event = eventOf(EventStatus.OPEN);
        event.startClosing("123-0");
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        EventStatus result = eventCommandService.startClosing(1L, "123-0");

        assertThat(result).isEqualTo(EventStatus.CLOSING);
        assertThat(event.getStatus()).isEqualTo(EventStatus.CLOSING);
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(Object.class));
    }

    @Test
    void startClosing은_OPEN이_아닌_잘못된_상태면_INVALID_STATE를_던진다() {
        Event event = eventOf(EventStatus.DRAFT);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        assertThatThrownBy(() -> eventCommandService.startClosing(1L, "123-0"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_STATE);
    }

    @Test
    void startClosing은_이미_CLOSED면_현재_상태를_멱등하게_반환한다() {
        Event event = eventOf(EventStatus.CLOSED);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        EventStatus result = eventCommandService.startClosing(1L, "123-0");

        assertThat(result).isEqualTo(EventStatus.CLOSED);
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(Object.class));
    }

    @Test
    void completeClosing은_CLOSING_이벤트를_CLOSED로_전이하고_커밋후_캐시무효화_이벤트를_발행한다() {
        Event event = eventOf(EventStatus.CLOSING);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        eventCommandService.completeClosing(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.CLOSED);
        assertThat(event.getClosedAt()).isEqualTo(FIXED_NOW);
        verify(eventPublisher).publishEvent(new EventClosingStateChangedEvent(1L));
    }

    @Test
    void completeClosing은_이미_CLOSED면_멱등하게_아무것도_하지_않는다() {
        Event event = eventOf(EventStatus.CLOSED);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        eventCommandService.completeClosing(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.CLOSED);
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(Object.class));
    }

    @Test
    void completeClosing은_CLOSING이_아닌_잘못된_상태면_INVALID_STATE를_던진다() {
        Event event = eventOf(EventStatus.OPEN);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        assertThatThrownBy(() -> eventCommandService.completeClosing(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_STATE);
    }

    @Test
    void completeDrawing은_CLOSED_이벤트를_DRAW_COMPLETED로_전이하고_커밋후_캐시무효화_이벤트를_발행한다() {
        Event event = eventOf(EventStatus.CLOSED);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        eventCommandService.completeDrawing(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAW_COMPLETED);
        verify(eventPublisher).publishEvent(new EventClosingStateChangedEvent(1L));
    }

    @Test
    void publish는_DRAW_COMPLETED_이벤트를_PUBLISHED로_전이하고_커밋후_캐시무효화_이벤트를_발행한다() {
        Event event = eventOf(EventStatus.CLOSED);
        event.completeDrawing();
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        eventCommandService.publish(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        verify(eventPublisher).publishEvent(new EventClosingStateChangedEvent(1L));
    }

    @Test
    void archive와_unarchive는_PUBLISHED_이벤트의_보관_메타데이터만_변경하고_캐시무효화_이벤트를_발행한다() {
        Event event = eventOf(EventStatus.PUBLISHED);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        eventCommandService.archive(1L, 9L);

        assertThat(event.isArchived()).isTrue();
        assertThat(event.getArchivedAt()).isEqualTo(FIXED_NOW);
        assertThat(event.getArchivedBy()).isEqualTo(9L);
        assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);

        eventCommandService.unarchive(1L);

        assertThat(event.isArchived()).isFalse();
        assertThat(event.getArchivedAt()).isNull();
        assertThat(event.getArchivedBy()).isNull();
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(new EventVisibilityChangedEvent(1L));
    }

    @Test
    void archive는_Event잠금으로_조회한_뒤_보관_조건을_확인한다() {
        Event event = eventOf(EventStatus.PUBLISHED);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        assertThatThrownBy(() -> eventCommandService.archive(1L, 9L, lockedEvent -> {
            assertThat(lockedEvent).isSameAs(event);
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        })).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_STATE);

        assertThat(event.isArchived()).isFalse();
        verify(eventPublisher, never()).publishEvent(new EventVisibilityChangedEvent(1L));
    }

    @Test
    void archive는_PUBLISHED가_아닌_이벤트를_거부한다() {
        Event event = eventOf(EventStatus.CLOSED);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        assertThatThrownBy(() -> eventCommandService.archive(1L, 9L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_STATE);
    }

    @Test
    void 승인요청은_시작_49시간_전에_허용한다() {
        Event event = draftEventAt(FIXED_NOW.plus(java.time.Duration.ofHours(49)));
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        eventCommandService.requestApproval(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.PENDING_APPROVAL);
    }

    @Test
    void 승인요청은_시작_정확히_48시간_전에_허용한다() {
        Event event = draftEventAt(FIXED_NOW.plus(java.time.Duration.ofHours(48)));
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        eventCommandService.requestApproval(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.PENDING_APPROVAL);
    }

    @Test
    void 승인요청은_시작_47시간_59분_전이면_이력_저장_전_거부한다() {
        Event event = draftEventAt(FIXED_NOW.plus(java.time.Duration.ofHours(47).plusMinutes(59)));
        AtomicBoolean historyCreated = new AtomicBoolean();
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        assertThatThrownBy(() -> eventCommandService.requestApproval(1L, lockedEvent -> {
            historyCreated.set(true);
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.APPROVAL_REQUEST_TOO_LATE);

        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(historyCreated.get()).isFalse();
    }

    @Test
    void 승인요청은_시작_24시간_전이면_거부한다() {
        Event event = draftEventAt(FIXED_NOW.plus(java.time.Duration.ofHours(24)));
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        assertThatThrownBy(() -> eventCommandService.requestApproval(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.APPROVAL_REQUEST_TOO_LATE);
        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
    }

    @Test
    void 승인요청은_이미_시작된_이벤트를_거부한다() {
        Event event = draftEventAt(FIXED_NOW.minus(java.time.Duration.ofMinutes(1)));
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        assertThatThrownBy(() -> eventCommandService.requestApproval(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.APPROVAL_REQUEST_TOO_LATE);
        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
    }

    @Test
    void 승인요청은_DRAFT가_아니면_시간보다_기존_상태_검증을_우선한다() {
        Event event = draftEventAt(FIXED_NOW.plus(java.time.Duration.ofHours(24)));
        event.requestApproval();
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        assertThatThrownBy(() -> eventCommandService.requestApproval(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_STATE);
    }

    @Test
    void 개발_환경처럼_최소_사전_시간이_0이면_즉시_시작하는_이벤트도_승인_요청할_수_있다() {
        Event event = draftEventAt(FIXED_NOW);
        EventCommandService developmentCommandService = new EventCommandService(
                eventRepository, eventPublisher, Clock.fixed(FIXED_NOW, ZoneOffset.UTC), Duration.ZERO);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        developmentCommandService.requestApproval(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.PENDING_APPROVAL);
    }

    @Test
    void 승인요청_최소_사전_시간이_음수면_서비스_생성을_거부한다() {
        assertThatThrownBy(() -> new EventCommandService(
                eventRepository, eventPublisher, Clock.fixed(FIXED_NOW, ZoneOffset.UTC), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("승인 요청 최소 사전 시간은 음수일 수 없습니다.");
    }

    private static Event eventOf(EventStatus status) {
        return Event.builder()
                .status(status)
                .startAt(FIXED_NOW.plus(java.time.Duration.ofDays(3)))
                .endAt(FIXED_NOW.plus(java.time.Duration.ofDays(4)))
                .build();
    }

    private static Event draftEventAt(Instant startAt) {
        return Event.builder()
                .status(EventStatus.DRAFT)
                .startAt(startAt)
                .endAt(startAt.plus(java.time.Duration.ofDays(1)))
                .build();
    }
}

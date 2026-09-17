package kr.co.cking.event.domain;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.application.service.EventOpenedEvent;
import kr.co.cking.event.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EventLifecycleTest {

    /** 운영 승인으로 예약된 Event는 공개 조회에서 UPCOMING 상태로 계산된다. */
    @Test
    void approvedEventIsPubliclyVisibleAsUpcoming() {
        Event event = new Event(
                1L, "팬미팅", "설명",
                Instant.parse("2026-09-20T09:00:00Z"),
                Instant.parse("2026-09-21T09:00:00Z"),
                3, DrawMethod.WEIGHTED, 1L, "550e8400-e29b-41d4-a716-446655440000"
        );

        event.requestApproval();
        event.approve();

        assertThat(event.getStatus().isPubliclyVisible()).isTrue();
        assertThat(event.displayStatus(java.time.Instant.parse("2026-09-01T00:00:00Z")))
                .isEqualTo(DisplayStatus.UPCOMING);
    }

    /** 거절된 Event를 수정하면 초안으로 복귀하고 변경 값이 반영되는지 검증한다. */
    @Test
    void rejectedEventUpdateReturnsItToDraft() {
        Event event = new Event(1L, "기존", "기존 설명",
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED, 1L, "550e8400-e29b-41d4-a716-446655440000");
        event.requestApproval();
        event.reject();
        event.changeToDraft();

        event.update("변경", "변경 설명", Instant.now().plus(java.time.Duration.ofDays(3)),
                Instant.now().plus(java.time.Duration.ofDays(4)), 2, DrawMethod.WEIGHTED);

        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.getTitle()).isEqualTo("변경");
        assertThat(event.getWinnerCount()).isEqualTo(2);
    }

    /** 삭제 가능한 Event는 물리 삭제 대신 삭제 시각을 기록하는지 검증한다. */
    @Test
    void draftEventIsSoftDeleted() {
        Event event = new Event(1L, "이벤트", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED, 1L, "550e8400-e29b-41d4-a716-446655440000");

        event.delete();

        assertThat(event.getDeletedAt()).isNotNull();
    }

    /** 거절된 Event도 물리 삭제 대신 삭제 시각을 기록할 수 있는지 검증한다. */
    @Test
    void rejectedEventIsSoftDeleted() {
        Event event = new Event(1L, "이벤트", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED, 1L, "550e8400-e29b-41d4-a716-446655440000");
        event.requestApproval();
        event.reject();

        event.delete();

        assertThat(event.getDeletedAt()).isNotNull();
    }

    /** 승인 대기처럼 삭제 대상이 아닌 상태에서는 삭제를 거부하는지 검증한다. */
    @Test
    void pendingApprovalEventCannotBeDeleted() {
        Event event = new Event(1L, "이벤트", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED, 1L, "550e8400-e29b-41d4-a716-446655440000");
        event.requestApproval();

        assertThatThrownBy(event::delete)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(EventErrorCode.INVALID_STATE);
    }

    /** 승인 요청 서비스가 DRAFT 이벤트를 승인 대기 상태로 전이시키는지 검증한다. */
    @Test
    void commandServiceRequestsApprovalForDraftEvent() {
        Event event = new Event(
                1L, "팬미팅", "설명",
                Instant.now().plus(java.time.Duration.ofDays(1)),
                Instant.now().plus(java.time.Duration.ofDays(2)),
                3, DrawMethod.WEIGHTED, 1L, "550e8400-e29b-41d4-a716-446655440000"
        );
        EventRepository eventRepository = mock(EventRepository.class);
        given(eventRepository.findByEventId(1L)).willReturn(java.util.Optional.of(event));
        EventCommandService eventCommandService = newEventCommandService(eventRepository);

        eventCommandService.requestApproval(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.PENDING_APPROVAL);
        verify(eventRepository).findByEventId(1L);
    }

    @Test
    void draftEventCanRequestApprovalOnlyOnce() {
        Event event = new Event(
                1L,
                "팬미팅",
                "설명",
                Instant.now().plus(java.time.Duration.ofDays(1)),
                Instant.now().plus(java.time.Duration.ofDays(2)),
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
                Instant.now().plus(java.time.Duration.ofDays(1)),
                Instant.now().plus(java.time.Duration.ofDays(2)),
                3,
                DrawMethod.WEIGHTED,
                1L,
                "550e8400-e29b-41d4-a716-446655440000"
        );
        event.requestApproval();
        EventRepository eventRepository = mock(EventRepository.class);
        given(eventRepository.findByEventId(1L)).willReturn(java.util.Optional.of(event));
        EventCommandService eventCommandService = newEventCommandService(eventRepository);

        eventCommandService.approve(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.SCHEDULED);
    }

    @Test
    void commandServiceOpensScheduledEvent() {
        Event event = scheduledEvent();
        EventRepository eventRepository = mock(EventRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        given(eventRepository.findByEventId(1L)).willReturn(java.util.Optional.of(event));
        EventCommandService eventCommandService = newEventCommandService(eventRepository, eventPublisher);

        eventCommandService.open(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.OPEN);
        verify(eventRepository).findByEventId(1L);
        verify(eventPublisher).publishEvent(new EventOpenedEvent(1L));
    }

    @Test
    void alreadyOpenEventDoesNotPublishCacheInvalidationEventAgain() {
        Event event = scheduledEvent();
        EventRepository eventRepository = mock(EventRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        given(eventRepository.findByEventId(1L)).willReturn(java.util.Optional.of(event));
        EventCommandService eventCommandService = newEventCommandService(eventRepository, eventPublisher);

        eventCommandService.open(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.OPEN);
        assertThatThrownBy(() -> eventCommandService.open(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(EventErrorCode.INVALID_STATE);
        verify(eventPublisher).publishEvent(new EventOpenedEvent(1L));
    }

    /** CLOSED Event가 초기 추첨 완료 후 DRAW_COMPLETED로 전이하는지 검증한다. */
    @Test
    void commandServiceCompletesDrawingForClosedEvent() {
        Event event = closedEvent();
        EventRepository eventRepository = mock(EventRepository.class);
        given(eventRepository.findByEventId(1L)).willReturn(java.util.Optional.of(event));
        EventCommandService eventCommandService = newEventCommandService(eventRepository);

        eventCommandService.completeDrawing(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAW_COMPLETED);
        verify(eventRepository).findByEventId(1L);
    }

    /** CLOSED가 아닌 Event의 초기 추첨 완료 전이는 거부하는지 검증한다. */
    @Test
    void commandServiceRejectsDrawingCompletionForNonClosedEvent() {
        Event event = scheduledEvent();
        EventRepository eventRepository = mock(EventRepository.class);
        given(eventRepository.findByEventId(1L)).willReturn(java.util.Optional.of(event));
        EventCommandService eventCommandService = newEventCommandService(eventRepository);

        assertThatThrownBy(() -> eventCommandService.completeDrawing(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(EventErrorCode.INVALID_STATE);
    }

    /** DRAW_COMPLETED Event가 결과 공개 후 PUBLISHED로 전이하는지 검증한다. */
    @Test
    void commandServicePublishesDrawingCompletedEvent() {
        Event event = drawingCompletedEvent();
        EventRepository eventRepository = mock(EventRepository.class);
        given(eventRepository.findByEventId(1L)).willReturn(java.util.Optional.of(event));
        EventCommandService eventCommandService = newEventCommandService(eventRepository);

        eventCommandService.publish(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
        verify(eventRepository).findByEventId(1L);
    }

    /** DRAW_COMPLETED가 아닌 Event의 결과 공개 전이는 거부하는지 검증한다. */
    @Test
    void commandServiceRejectsPublishingForNonDrawingCompletedEvent() {
        Event event = closedEvent();
        EventRepository eventRepository = mock(EventRepository.class);
        given(eventRepository.findByEventId(1L)).willReturn(java.util.Optional.of(event));
        EventCommandService eventCommandService = newEventCommandService(eventRepository);

        assertThatThrownBy(() -> eventCommandService.publish(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(EventErrorCode.INVALID_STATE);
    }

    @Test
    void commandServiceRejectsPendingEventAndAllowsReturnToDraft() {
        Event event = new Event(
                1L,
                "팬미팅",
                "설명",
                Instant.now().plus(java.time.Duration.ofDays(1)),
                Instant.now().plus(java.time.Duration.ofDays(2)),
                3,
                DrawMethod.WEIGHTED,
                1L,
                "550e8400-e29b-41d4-a716-446655440000"
        );
        event.requestApproval();
        EventRepository eventRepository = mock(EventRepository.class);
        given(eventRepository.findByEventId(1L)).willReturn(java.util.Optional.of(event));
        EventCommandService eventCommandService = newEventCommandService(eventRepository);

        eventCommandService.reject(1L, "일정 확인 필요");
        eventCommandService.changeToDraft(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
    }

    /** EventCommandService가 지원하는 생명주기 전이를 정해진 순서로 수행하는지 검증한다. */
    @Test
    void commandServiceExecutesSupportedLifecycleTransitions() {
        Event scheduledPathEvent = new Event(
                1L, "전체 전이", "설명",
                Instant.now().plus(java.time.Duration.ofDays(1)),
                Instant.now().plus(java.time.Duration.ofDays(2)),
                3, DrawMethod.WEIGHTED, 1L, "550e8400-e29b-41d4-a716-446655440000"
        );
        Event drawingPathEvent = closedEvent();
        EventRepository eventRepository = mock(EventRepository.class);
        given(eventRepository.findByEventId(1L)).willReturn(java.util.Optional.of(scheduledPathEvent));
        given(eventRepository.findByEventId(2L)).willReturn(java.util.Optional.of(drawingPathEvent));
        EventCommandService eventCommandService = newEventCommandService(eventRepository);

        eventCommandService.requestApproval(1L);
        eventCommandService.approve(1L);
        eventCommandService.open(1L);
        eventCommandService.completeDrawing(2L);
        eventCommandService.publish(2L);

        assertThat(scheduledPathEvent.getStatus()).isEqualTo(EventStatus.OPEN);
        assertThat(drawingPathEvent.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(drawingPathEvent.getPublishedAt()).isNotNull();
    }

    private EventCommandService newEventCommandService(EventRepository eventRepository) {
        return newEventCommandService(eventRepository, mock(ApplicationEventPublisher.class));
    }

    private EventCommandService newEventCommandService(EventRepository eventRepository, ApplicationEventPublisher eventPublisher) {
        return new EventCommandService(
                eventRepository,
                eventPublisher,
                java.time.Clock.systemUTC()
        );
    }

    private Event scheduledEvent() {
        Event event = new Event(
                1L,
                "팬미팅",
                "설명",
                Instant.now().plus(java.time.Duration.ofDays(1)),
                Instant.now().plus(java.time.Duration.ofDays(2)),
                3,
                DrawMethod.WEIGHTED,
                1L,
                "550e8400-e29b-41d4-a716-446655440000"
        );
        event.requestApproval();
        event.approve();
        return event;
    }

    /** 추첨 완료 전이 테스트에 사용할 CLOSED Event를 생성한다. */
    private Event closedEvent() {
        return Event.builder()
                .creatorId(1L)
                .requestId("550e8400-e29b-41d4-a716-446655440000")
                .title("마감 이벤트")
                .startAt(Instant.now().minus(java.time.Duration.ofDays(2)))
                .endAt(Instant.now().minus(java.time.Duration.ofDays(1)))
                .winnerCount(3)
                .drawMethod(DrawMethod.WEIGHTED.name())
                .status(EventStatus.CLOSED)
                .createdBy(1L)
                .createdAt(Instant.now())
                .build();
    }

    /** 결과 공개 전이 테스트에 사용할 DRAW_COMPLETED Event를 생성한다. */
    private Event drawingCompletedEvent() {
        Event event = closedEvent();
        event.completeDrawing();
        return event;
    }
}

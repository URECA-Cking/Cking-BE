package kr.co.cking.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.notification.application.dto.NotificationSummary;
import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.domain.NotificationType;
import kr.co.cking.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

class NotificationQueryServiceTest {

    private static final Sort NOTIFICATION_LIST_SORT = Sort.by(
            Sort.Direction.DESC, "createdAt", "notificationId"
    );

    @Test
    void 내_알림만_최신순으로_조회하고_연관_정보를_반환한다() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        EventRepository eventRepository = mock(EventRepository.class);
        DrawingRepository drawingRepository = mock(DrawingRepository.class);
        Notification notification = notification(10L, 100L, 200L, 300L, NotificationType.REDRAW_WINNER);
        Event event = mock(Event.class);
        Drawing drawing = mock(Drawing.class);
        when(memberRepository.existsById(10L)).thenReturn(true);
        when(notificationRepository.findByMemberId(eq(10L), eq(PageRequest.of(0, 20, NOTIFICATION_LIST_SORT))))
                .thenReturn(new PageImpl<>(List.of(notification), PageRequest.of(0, 20), 1));
        when(eventRepository.findByEventIdIn(List.of(100L))).thenReturn(List.of(event));
        when(drawingRepository.findByIdIn(List.of(200L))).thenReturn(List.of(drawing));
        when(event.getEventId()).thenReturn(100L);
        when(event.getTitle()).thenReturn("팬미팅 이벤트");
        when(drawing.getId()).thenReturn(200L);
        when(drawing.getDrawNo()).thenReturn(1);
        when(drawing.getDrawType()).thenReturn(DrawingType.REDRAW);
        NotificationQueryService service = new NotificationQueryService(
                memberRepository, notificationRepository, eventRepository, drawingRepository
        );

        NotificationSummary result = service.findMine(10L, 0, 20).getContent().getFirst();

        assertThat(result.notificationId()).isEqualTo(1L);
        assertThat(result.event()).isEqualTo(new NotificationSummary.EventInfo(100L, "팬미팅 이벤트"));
        assertThat(result.drawing()).isEqualTo(new NotificationSummary.DrawingInfo(200L, 1, DrawingType.REDRAW));
        assertThat(result.type()).isEqualTo(NotificationType.REDRAW_WINNER);
        assertThat(result.readAt()).isNull();
        verify(notificationRepository).findByMemberId(10L, PageRequest.of(0, 20, NOTIFICATION_LIST_SORT));
    }

    @Test
    void 존재하지_않는_사용자는_알림을_조회할_수_없다() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        NotificationQueryService service = new NotificationQueryService(
                memberRepository, notificationRepository, mock(EventRepository.class), mock(DrawingRepository.class)
        );

        assertThatThrownBy(() -> service.findMine(999L, 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 요청한_사용자_ID로만_알림을_조회한다() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        EventRepository eventRepository = mock(EventRepository.class);
        DrawingRepository drawingRepository = mock(DrawingRepository.class);
        when(memberRepository.existsById(10L)).thenReturn(true);
        when(notificationRepository.findByMemberId(eq(10L), eq(PageRequest.of(2, 50, NOTIFICATION_LIST_SORT))))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 50), 0));
        when(eventRepository.findByEventIdIn(List.of())).thenReturn(List.of());
        when(drawingRepository.findByIdIn(List.of())).thenReturn(List.of());
        NotificationQueryService service = new NotificationQueryService(
                memberRepository, notificationRepository, eventRepository, drawingRepository
        );

        service.findMine(10L, 2, 50);

        verify(notificationRepository).findByMemberId(10L, PageRequest.of(2, 50, NOTIFICATION_LIST_SORT));
    }

    private Notification notification(
            Long memberId,
            Long eventId,
            Long drawingId,
            Long winnerId,
            NotificationType type
    ) {
        Notification notification = new Notification(memberId, eventId, drawingId, winnerId, type, "당첨 안내", "축하합니다.");
        ReflectionTestUtils.setField(notification, "notificationId", 1L);
        ReflectionTestUtils.setField(notification, "createdAt", Instant.parse("2026-09-17T00:00:00Z"));
        return notification;
    }
}

package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.snapshot.application.VerifiedSnapshotTestFactory;

@ExtendWith(MockitoExtension.class)
class DrawingPublicationServiceTest {

    @Mock
    private DrawingRepository drawingRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventCommandService eventCommandService;

    @Mock
    private MemberQueryService memberQueryService;

    @InjectMocks
    private DrawingPublicationService drawingPublicationService;

    @Test
    void DRAW_COMPLETED_Event의_완료된_Drawing을_공개한다() {
        Drawing drawing = completedDrawing(1L, 10L, DrawingVisibility.PRIVATE);
        when(drawingRepository.findById(1L)).thenReturn(Optional.of(drawing));
        when(eventRepository.findById(10L)).thenReturn(Optional.of(eventOf(EventStatus.DRAW_COMPLETED)));
        ReflectionTestUtils.setField(drawingPublicationService, "clock",
                Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC));

        Drawing result = drawingPublicationService.publish(1L, 99L);

        assertThat(result.getVisibility()).isEqualTo(DrawingVisibility.PUBLIC);
        verify(memberQueryService).validateAdmin(99L);
        verify(eventCommandService).publish(10L);
    }

    @Test
    void 이미_공개된_Drawing은_Event를_건드리지_않고_그대로_반환한다() {
        Drawing drawing = completedDrawing(1L, 10L, DrawingVisibility.PUBLIC);
        when(drawingRepository.findById(1L)).thenReturn(Optional.of(drawing));

        Drawing result = drawingPublicationService.publish(1L, 99L);

        assertThat(result.getVisibility()).isEqualTo(DrawingVisibility.PUBLIC);
        verify(memberQueryService).validateAdmin(99L);
        verifyNoInteractions(eventRepository, eventCommandService);
    }

    @Test
    void Event가_DRAW_COMPLETED가_아니면_INVALID_STATE이고_Drawing을_바꾸지_않는다() {
        Drawing drawing = completedDrawing(1L, 10L, DrawingVisibility.PRIVATE);
        when(drawingRepository.findById(1L)).thenReturn(Optional.of(drawing));
        when(eventRepository.findById(10L)).thenReturn(Optional.of(eventOf(EventStatus.CLOSED)));

        assertThatThrownBy(() -> drawingPublicationService.publish(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_STATE);
        assertThat(drawing.getVisibility()).isEqualTo(DrawingVisibility.PRIVATE);
        verify(eventCommandService, never()).publish(10L);
    }

    @Test
    void 완료되지_않은_Drawing은_공개할_수_없다() {
        Drawing drawing = Drawing.createInitial(snapshotContract(), 3L, 4L);
        ReflectionTestUtils.setField(drawing, "eventId", 1L);
        when(drawingRepository.findById(1L)).thenReturn(Optional.of(drawing));
        when(eventRepository.findById(1L)).thenReturn(Optional.of(eventOf(EventStatus.DRAW_COMPLETED)));
        ReflectionTestUtils.setField(drawingPublicationService, "clock",
                Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> drawingPublicationService.publish(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", DrawingErrorCode.DRAWING_NOT_COMPLETED);
    }

    @Test
    void 존재하지_않는_Drawing이면_DRAWING_NOT_FOUND이다() {
        when(drawingRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> drawingPublicationService.publish(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", DrawingErrorCode.DRAWING_NOT_FOUND);
    }

    @Test
    void ADMIN이_아니면_Drawing을_조회하지_않는다() {
        org.mockito.Mockito.doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(memberQueryService).validateAdmin(2L);

        assertThatThrownBy(() -> drawingPublicationService.publish(1L, 2L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);
        verifyNoInteractions(drawingRepository);
    }

    private Drawing completedDrawing(Long drawingId, Long eventId, DrawingVisibility visibility) {
        Drawing drawing = Drawing.createInitial(snapshotContract(), 3L, 4L);
        ReflectionTestUtils.setField(drawing, "id", drawingId);
        ReflectionTestUtils.setField(drawing, "eventId", eventId);
        ReflectionTestUtils.setField(drawing, "status", DrawingStatus.COMPLETED);
        ReflectionTestUtils.setField(drawing, "visibility", visibility);
        return drawing;
    }

    private kr.co.cking.drawing.domain.DrawingSnapshotContract snapshotContract() {
        VerifiedSnapshot snapshot = VerifiedSnapshotTestFactory.create(2L, 1L, 2, "WEIGHTED", "WEIGHTED_V1");
        return kr.co.cking.drawing.domain.DrawingSnapshotContract.from(snapshot);
    }

    private Event eventOf(EventStatus status) {
        return Event.builder()
                .status(status)
                .startAt(Instant.parse("2026-09-10T00:00:00Z"))
                .endAt(Instant.parse("2026-09-20T00:00:00Z"))
                .build();
    }
}

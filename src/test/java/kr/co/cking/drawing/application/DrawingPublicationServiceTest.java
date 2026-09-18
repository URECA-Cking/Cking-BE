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
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.application.EventDrawingQueryService;
import kr.co.cking.event.application.dto.EventDrawingSource;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.snapshot.application.VerifiedSnapshotTestFactory;

@ExtendWith(MockitoExtension.class)
class DrawingPublicationServiceTest {

    @Mock
    private DrawingRepository drawingRepository;

    @Mock
    private EventDrawingQueryService eventDrawingQueryService;

    @Mock
    private EventCommandService eventCommandService;

    @Mock
    private MemberQueryService memberQueryService;

    @InjectMocks
    private DrawingPublicationService drawingPublicationService;

    @Test
    void DRAW_COMPLETED_Event의_완료된_Drawing을_공개한다() {
        Drawing drawing = completedDrawing(1L, 10L, DrawingVisibility.PRIVATE);
        when(drawingRepository.findByIdForPublish(1L)).thenReturn(Optional.of(drawing));
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L)).thenReturn(sourceOf(10L, EventStatus.DRAW_COMPLETED));
        ReflectionTestUtils.setField(drawingPublicationService, "clock",
                Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC));

        DrawingPublicationResult result = drawingPublicationService.publish(1L, 99L);

        assertThat(result.visibility()).isEqualTo(DrawingVisibility.PUBLIC);
        assertThat(result.outcome()).isEqualTo(DrawingPublicationResult.PublicationOutcome.PUBLISHED);
        verify(memberQueryService).validateAdmin(99L);
        verify(eventCommandService).publish(10L);
    }

    @Test
    void 이미_공개된_Drawing은_Event가_PUBLISHED면_그대로_반환한다() {
        Drawing drawing = completedDrawing(1L, 10L, DrawingVisibility.PUBLIC);
        when(drawingRepository.findByIdForPublish(1L)).thenReturn(Optional.of(drawing));
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L)).thenReturn(sourceOf(10L, EventStatus.PUBLISHED));

        DrawingPublicationResult result = drawingPublicationService.publish(1L, 99L);

        assertThat(result.visibility()).isEqualTo(DrawingVisibility.PUBLIC);
        assertThat(result.outcome()).isEqualTo(DrawingPublicationResult.PublicationOutcome.ALREADY_PUBLISHED);
        verify(memberQueryService).validateAdmin(99L);
        verifyNoInteractions(eventCommandService);
    }

    @Test
    void 이미_공개된_Drawing인데_Event가_PUBLISHED가_아니면_INVALID_STATE이다() {
        Drawing drawing = completedDrawing(1L, 10L, DrawingVisibility.PUBLIC);
        when(drawingRepository.findByIdForPublish(1L)).thenReturn(Optional.of(drawing));
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L)).thenReturn(sourceOf(10L, EventStatus.DRAW_COMPLETED));

        assertThatThrownBy(() -> drawingPublicationService.publish(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_STATE);
        verifyNoInteractions(eventCommandService);
    }

    @Test
    void Event가_DRAW_COMPLETED가_아니면_INVALID_STATE이고_Drawing을_바꾸지_않는다() {
        Drawing drawing = completedDrawing(1L, 10L, DrawingVisibility.PRIVATE);
        when(drawingRepository.findByIdForPublish(1L)).thenReturn(Optional.of(drawing));
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L)).thenReturn(sourceOf(10L, EventStatus.CLOSED));

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
        when(drawingRepository.findByIdForPublish(1L)).thenReturn(Optional.of(drawing));

        assertThatThrownBy(() -> drawingPublicationService.publish(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", DrawingErrorCode.DRAWING_NOT_COMPLETED);
        verifyNoInteractions(eventDrawingQueryService, eventCommandService);
    }

    @Test
    void 이미_공개된_Drawing이라도_status가_COMPLETED가_아니면_DRAWING_NOT_COMPLETED이다() {
        // 정상 흐름에서는 나오지 않아야 하는 데이터 불일치(예: 수동 DB 조작)를 가정한다.
        Drawing drawing = Drawing.createInitial(snapshotContract(), 3L, 4L);
        ReflectionTestUtils.setField(drawing, "eventId", 1L);
        ReflectionTestUtils.setField(drawing, "visibility", DrawingVisibility.PUBLIC);
        when(drawingRepository.findByIdForPublish(1L)).thenReturn(Optional.of(drawing));

        assertThatThrownBy(() -> drawingPublicationService.publish(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", DrawingErrorCode.DRAWING_NOT_COMPLETED);
        verifyNoInteractions(eventDrawingQueryService, eventCommandService);
    }

    @Test
    void PUBLISHED_Event의_REDRAW_Drawing을_공개하고_Event를_다시_전이하지_않는다() {
        Drawing drawing = completedDrawing(1L, 10L, DrawingVisibility.PRIVATE);
        ReflectionTestUtils.setField(drawing, "drawType", DrawingType.REDRAW);
        when(drawingRepository.findByIdForPublish(1L)).thenReturn(Optional.of(drawing));
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L)).thenReturn(sourceOf(10L, EventStatus.PUBLISHED));
        ReflectionTestUtils.setField(drawingPublicationService, "clock",
                Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC));

        DrawingPublicationResult result = drawingPublicationService.publish(1L, 99L);

        assertThat(result.drawingType()).isEqualTo(DrawingType.REDRAW);
        assertThat(result.visibility()).isEqualTo(DrawingVisibility.PUBLIC);
        assertThat(result.outcome()).isEqualTo(DrawingPublicationResult.PublicationOutcome.PUBLISHED);
        verify(eventCommandService, never()).publish(10L);
    }

    @Test
    void 이미_공개된_REDRAW_Drawing은_PUBLISHED_Event에서_멱등하게_성공한다() {
        Drawing drawing = completedDrawing(1L, 10L, DrawingVisibility.PUBLIC);
        ReflectionTestUtils.setField(drawing, "drawType", DrawingType.REDRAW);
        when(drawingRepository.findByIdForPublish(1L)).thenReturn(Optional.of(drawing));
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L)).thenReturn(sourceOf(10L, EventStatus.PUBLISHED));

        DrawingPublicationResult result = drawingPublicationService.publish(1L, 99L);

        assertThat(result.outcome()).isEqualTo(DrawingPublicationResult.PublicationOutcome.ALREADY_PUBLISHED);
        verifyNoInteractions(eventCommandService);
    }

    @Test
    void REDRAW_Drawing은_Event가_PUBLISHED가_아니면_공개할_수_없다() {
        Drawing drawing = completedDrawing(1L, 10L, DrawingVisibility.PRIVATE);
        ReflectionTestUtils.setField(drawing, "drawType", DrawingType.REDRAW);
        when(drawingRepository.findByIdForPublish(1L)).thenReturn(Optional.of(drawing));
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L)).thenReturn(sourceOf(10L, EventStatus.DRAW_COMPLETED));

        assertThatThrownBy(() -> drawingPublicationService.publish(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_STATE);
        assertThat(drawing.getVisibility()).isEqualTo(DrawingVisibility.PRIVATE);
        verifyNoInteractions(eventCommandService);
    }

    @Test
    void 존재하지_않는_Drawing이면_DRAWING_NOT_FOUND이다() {
        when(drawingRepository.findByIdForPublish(1L)).thenReturn(Optional.empty());

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

    private EventDrawingSource sourceOf(Long eventId, EventStatus status) {
        return new EventDrawingSource(eventId, status, null);
    }
}

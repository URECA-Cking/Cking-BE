package kr.co.cking.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorActivityName;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.snapshot.domain.DrawSnapshot;
import kr.co.cking.snapshot.domain.SnapshotVerificationStatus;
import kr.co.cking.snapshot.repository.DrawSnapshotRepository;
import kr.co.cking.winner.repository.WinnerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class AdminEventOperationQueryServiceTest {

    @Test
    void 관리자에게_Event기본정보와_운영요약을_한번에_반환한다() {
        MemberQueryService memberQueryService = mock(MemberQueryService.class);
        EventRepository eventRepository = mock(EventRepository.class);
        CreatorRepository creatorRepository = mock(CreatorRepository.class);
        DrawSnapshotRepository snapshotRepository = mock(DrawSnapshotRepository.class);
        DrawingRepository drawingRepository = mock(DrawingRepository.class);
        WinnerRepository winnerRepository = mock(WinnerRepository.class);
        Event event = event(7L, 3L);
        Creator creator = creator(3L);
        DrawSnapshot snapshot = mock(DrawSnapshot.class);
        Drawing drawing = mock(Drawing.class);

        given(eventRepository.findByEventIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(event));
        given(creatorRepository.findById(3L)).willReturn(Optional.of(creator));
        given(snapshotRepository.findByEventId(7L)).willReturn(Optional.of(snapshot));
        given(drawingRepository.findByEventIdAndDrawNo(7L, 0)).willReturn(Optional.of(drawing));
        given(snapshot.getId()).willReturn(11L);
        given(snapshot.getVerificationStatus()).willReturn(SnapshotVerificationStatus.VERIFIED);
        given(snapshot.getCandidateCount()).willReturn(10);
        given(snapshot.getTotalTicketCount()).willReturn(50L);
        given(snapshot.getWinnerCount()).willReturn(2);
        given(snapshot.getCreatedAt()).willReturn(Instant.parse("2026-10-20T00:10:00Z"));
        given(drawing.getDrawType()).willReturn(DrawingType.INITIAL);
        given(drawing.getId()).willReturn(21L);
        given(drawing.getDrawNo()).willReturn(0);
        given(drawing.getStatus()).willReturn(DrawingStatus.COMPLETED);
        given(drawing.getVisibility()).willReturn(DrawingVisibility.PUBLIC);
        given(drawing.getWinnerCount()).willReturn(2);
        given(drawing.getCreatedAt()).willReturn(Instant.parse("2026-10-20T00:11:00Z"));
        given(drawing.getCompletedAt()).willReturn(Instant.parse("2026-10-20T00:12:00Z"));
        given(drawing.getPublishedAt()).willReturn(Instant.parse("2026-10-20T00:13:00Z"));
        given(winnerRepository.countByDrawingId(21L)).willReturn(2L);

        AdminEventOperationResult result = service(memberQueryService, eventRepository, creatorRepository,
                snapshotRepository, drawingRepository, winnerRepository).getOperation(1L, 7L);

        assertThat(result.event().title()).isEqualTo("운영 이벤트");
        assertThat(result.event().creatorName()).isEqualTo("활동명");
        assertThat(result.snapshot().snapshotId()).isEqualTo(11L);
        assertThat(result.initialDrawing().drawingId()).isEqualTo(21L);
        assertThat(result.initialDrawing().storedWinnerCount()).isEqualTo(2L);
        verify(memberQueryService).validateAdmin(1L);
    }

    @Test
    void Snapshot과_INITIAL_Drawing이_없어도_null_요약으로_반환한다() {
        MemberQueryService memberQueryService = mock(MemberQueryService.class);
        EventRepository eventRepository = mock(EventRepository.class);
        CreatorRepository creatorRepository = mock(CreatorRepository.class);
        DrawSnapshotRepository snapshotRepository = mock(DrawSnapshotRepository.class);
        DrawingRepository drawingRepository = mock(DrawingRepository.class);
        WinnerRepository winnerRepository = mock(WinnerRepository.class);
        Event event = event(7L, 3L);
        given(eventRepository.findByEventIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(event));
        given(creatorRepository.findById(3L)).willReturn(Optional.of(creator(3L)));
        given(snapshotRepository.findByEventId(7L)).willReturn(Optional.empty());
        given(drawingRepository.findByEventIdAndDrawNo(7L, 0)).willReturn(Optional.empty());

        AdminEventOperationResult result = service(memberQueryService, eventRepository, creatorRepository,
                snapshotRepository, drawingRepository, winnerRepository).getOperation(1L, 7L);

        assertThat(result.snapshot()).isNull();
        assertThat(result.initialDrawing()).isNull();
        verify(winnerRepository, never()).countByDrawingId(org.mockito.ArgumentMatchers.anyLong());
    }

    @ParameterizedTest
    @EnumSource(value = EventStatus.class, names = {
            "DRAFT", "OPEN", "CLOSING", "DRAW_COMPLETED", "PUBLISHED"
    })
    void 관리자_운영_상세는_Event상태를_그대로_반환한다(EventStatus status) {
        MemberQueryService memberQueryService = mock(MemberQueryService.class);
        EventRepository eventRepository = mock(EventRepository.class);
        CreatorRepository creatorRepository = mock(CreatorRepository.class);
        DrawSnapshotRepository snapshotRepository = mock(DrawSnapshotRepository.class);
        DrawingRepository drawingRepository = mock(DrawingRepository.class);
        WinnerRepository winnerRepository = mock(WinnerRepository.class);
        Event event = event(7L, 3L, status);
        given(eventRepository.findByEventIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(event));
        given(creatorRepository.findById(3L)).willReturn(Optional.of(creator(3L)));
        given(snapshotRepository.findByEventId(7L)).willReturn(Optional.empty());
        given(drawingRepository.findByEventIdAndDrawNo(7L, 0)).willReturn(Optional.empty());

        AdminEventOperationResult result = service(memberQueryService, eventRepository, creatorRepository,
                snapshotRepository, drawingRepository, winnerRepository).getOperation(1L, 7L);

        assertThat(result.event().status()).isEqualTo(status);
    }

    @Test
    void 삭제된_Event는_관리자_운영_상세에서_찾을_수_없다() {
        MemberQueryService memberQueryService = mock(MemberQueryService.class);
        EventRepository eventRepository = mock(EventRepository.class);
        given(eventRepository.findByEventIdAndDeletedAtIsNull(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service(memberQueryService, eventRepository, mock(CreatorRepository.class),
                mock(DrawSnapshotRepository.class), mock(DrawingRepository.class), mock(WinnerRepository.class))
                .getOperation(1L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 권한검증에_실패하면_Event를_조회하지_않는다() {
        MemberQueryService memberQueryService = mock(MemberQueryService.class);
        EventRepository eventRepository = mock(EventRepository.class);
        org.mockito.Mockito.doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(memberQueryService).validateAdmin(1L);

        assertThatThrownBy(() -> service(memberQueryService, eventRepository, mock(CreatorRepository.class),
                mock(DrawSnapshotRepository.class), mock(DrawingRepository.class), mock(WinnerRepository.class))
                .getOperation(1L, 7L))
                .isInstanceOf(BusinessException.class);
        verify(eventRepository, never()).findByEventIdAndDeletedAtIsNull(7L);
    }

    private AdminEventOperationQueryService service(MemberQueryService memberQueryService, EventRepository eventRepository,
            CreatorRepository creatorRepository, DrawSnapshotRepository snapshotRepository,
            DrawingRepository drawingRepository, WinnerRepository winnerRepository) {
        return new AdminEventOperationQueryService(memberQueryService, eventRepository, creatorRepository,
                snapshotRepository, drawingRepository, winnerRepository);
    }

    private Event event(Long eventId, Long creatorId) {
        return event(eventId, creatorId, EventStatus.CLOSED);
    }

    private Event event(Long eventId, Long creatorId, EventStatus status) {
        Event event = new Event(creatorId, "운영 이벤트", "설명", Instant.parse("2026-10-21T00:00:00Z"),
                Instant.parse("2026-10-22T00:00:00Z"), 2, DrawMethod.WEIGHTED, 1L,
                "550e8400-e29b-41d4-a716-446655440000");
        ReflectionTestUtils.setField(event, "eventId", eventId);
        ReflectionTestUtils.setField(event, "status", status);
        return event;
    }

    private Creator creator(Long creatorId) {
        Creator creator = new Creator(3L, "OAuth 이름", CreatorActivityName.of("활동명"));
        ReflectionTestUtils.setField(creator, "creatorId", creatorId);
        return creator;
    }
}

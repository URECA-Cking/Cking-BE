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

    /** 거절된 Event를 수정하면 초안으로 복귀하고 변경 값이 반영되는지 검증한다. */
    @Test
    void rejectedEventUpdateReturnsItToDraft() {
        Event event = new Event(1L, "기존", "기존 설명",
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1), LocalDateTime.now(ZoneOffset.UTC).plusDays(2),
                1, DrawMethod.WEIGHTED, 1L, "550e8400-e29b-41d4-a716-446655440000");
        event.requestApproval();
        event.reject();
        event.changeToDraft();

        event.update("변경", "변경 설명", LocalDateTime.now(ZoneOffset.UTC).plusDays(3),
                LocalDateTime.now(ZoneOffset.UTC).plusDays(4), 2, DrawMethod.WEIGHTED);

        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.getTitle()).isEqualTo("변경");
        assertThat(event.getWinnerCount()).isEqualTo(2);
    }

    /** 삭제 가능한 Event는 물리 삭제 대신 삭제 시각을 기록하는지 검증한다. */
    @Test
    void draftEventIsSoftDeleted() {
        Event event = new Event(1L, "이벤트", null,
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1), LocalDateTime.now(ZoneOffset.UTC).plusDays(2),
                1, DrawMethod.WEIGHTED, 1L, "550e8400-e29b-41d4-a716-446655440000");

        event.delete();

        assertThat(event.getDeletedAt()).isNotNull();
    }

    /** 승인 요청 서비스가 DRAFT 이벤트를 승인 대기 상태로 전이시키는지 검증한다. */
    @Test
    void commandServiceRequestsApprovalForDraftEvent() {
        Event event = new Event(
                1L, "팬미팅", "설명",
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1),
                LocalDateTime.now(ZoneOffset.UTC).plusDays(2),
                3, DrawMethod.WEIGHTED, 1L, "550e8400-e29b-41d4-a716-446655440000"
        );
        EventRepository eventRepository = mock(EventRepository.class);
        given(eventRepository.findById(1L)).willReturn(java.util.Optional.of(event));
        EventCommandService eventCommandService = new EventCommandService(eventRepository);

        eventCommandService.requestApproval(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.PENDING_APPROVAL);
    }

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

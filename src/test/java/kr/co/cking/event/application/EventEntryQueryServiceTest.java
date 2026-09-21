package kr.co.cking.event.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.application.dto.EntryHistoryItemResponse;
import kr.co.cking.event.application.dto.EntryHistoryPage;
import kr.co.cking.event.repository.EventEntryRepository;
import kr.co.cking.event.repository.EventEntryView;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventEntryQueryServiceTest {

    private final EventEntryRepository entryRepository = mock(EventEntryRepository.class);
    private final EventRepository eventRepository = mock(EventRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final EventEntryQueryService service =
            new EventEntryQueryService(entryRepository, eventRepository, memberRepository);

    @Test
    void 내_응모내역을_최신순으로_조회하고_다음_cursor를_반환한다() {
        EventEntryView first = view(12L, 3L, "2026-09-18T02:00:00Z");
        EventEntryView second = view(11L, 2L, "2026-09-18T01:00:00Z");
        EventEntryView third = view(10L, 1L, "2026-09-18T00:00:00Z");
        when(memberRepository.existsById(1L)).thenReturn(true);
        when(eventRepository.existsByEventIdAndDeletedAtIsNull(2L)).thenReturn(true);
        when(entryRepository.findFirstPageView(1L, 2L, PageRequest.of(0, 3)))
                .thenReturn(List.of(first, second, third));

        EntryHistoryPage result = service.getMyEntries(2L, 1L, 2, null);

        assertThat(result.items()).extracting(EntryHistoryItemResponse::entryId)
                .containsExactly(12L, 11L);
        assertThat(result.items()).extracting(EntryHistoryItemResponse::usedTicketCount)
                .containsExactly(3L, 2L);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isNotBlank();

        when(entryRepository.findAfterCursorView(
                1L, 2L, second.getAppliedAt(), second.getEntryId(), PageRequest.of(0, 3)))
                .thenReturn(List.of(third));

        EntryHistoryPage next = service.getMyEntries(2L, 1L, 2, result.nextCursor());

        assertThat(next.items()).extracting(EntryHistoryItemResponse::entryId)
                .containsExactly(10L);
        assertThat(next.hasNext()).isFalse();
        assertThat(next.nextCursor()).isNull();
    }

    @Test
    void 잘못된_cursor는_validation오류로_실패한다() {
        when(memberRepository.existsById(1L)).thenReturn(true);
        when(eventRepository.existsByEventIdAndDeletedAtIsNull(2L)).thenReturn(true);

        assertThatThrownBy(() -> service.getMyEntries(2L, 1L, 20, "invalid"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }

    @Test
    void 회원이_없으면_resource_not_found로_실패하고_내역을_조회하지_않는다() {
        when(memberRepository.existsById(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.getMyEntries(2L, 1L, 20, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        verify(entryRepository, never()).findFirstPageView(1L, 2L, PageRequest.of(0, 21));
    }

    @Test
    void 삭제됐거나_없는_event면_resource_not_found로_실패한다() {
        when(memberRepository.existsById(1L)).thenReturn(true);
        when(eventRepository.existsByEventIdAndDeletedAtIsNull(2L)).thenReturn(false);

        assertThatThrownBy(() -> service.getMyEntries(2L, 1L, 20, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    private EventEntryView view(Long entryId, Long usedTicketCount, String appliedAt) {
        EventEntryView view = mock(EventEntryView.class);
        when(view.getEntryId()).thenReturn(entryId);
        when(view.getUsedTicketCount()).thenReturn(usedTicketCount);
        when(view.getAppliedAt()).thenReturn(Instant.parse(appliedAt));
        return view;
    }
}

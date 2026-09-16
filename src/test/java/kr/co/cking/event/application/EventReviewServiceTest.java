package kr.co.cking.event.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventApprovalRequest;
import kr.co.cking.event.domain.EventApprovalRequestStatus;
import kr.co.cking.event.repository.EventApprovalRequestRepository;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class EventReviewServiceTest {

    /** 승인 대기 목록은 페이지의 Event·Creator를 각각 한 번씩 일괄 조회하는지 검증한다. */
    @Test
    void pendingListBatchLoadsEventsAndCreators() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        EventRepository eventRepository = mock(EventRepository.class);
        EventApprovalRequestRepository approvalRequestRepository = mock(EventApprovalRequestRepository.class);
        CreatorRepository creatorRepository = mock(CreatorRepository.class);
        EventCommandService eventCommandService = mock(EventCommandService.class);
        Member admin = new Member("관리자", null, null, MemberRole.ADMIN);
        EventApprovalRequest firstRequest = new EventApprovalRequest(10L, 1, 1L);
        EventApprovalRequest secondRequest = new EventApprovalRequest(20L, 1, 1L);
        Event firstEvent = event(10L, 100L, "첫 이벤트");
        Event secondEvent = event(20L, 200L, "둘째 이벤트");
        Creator firstCreator = creator(100L, "첫 크리에이터");
        Creator secondCreator = creator(200L, "둘째 크리에이터");
        given(memberRepository.findById(1L)).willReturn(Optional.of(admin));
        given(approvalRequestRepository.findByStatusOrderByRequestedAtAscIdAsc(
                EventApprovalRequestStatus.PENDING, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(firstRequest, secondRequest)));
        given(eventRepository.findByEventIdIn(List.of(10L, 20L))).willReturn(List.of(firstEvent, secondEvent));
        given(creatorRepository.findByCreatorIdIn(org.mockito.ArgumentMatchers.anyCollection()))
                .willReturn(List.of(firstCreator, secondCreator));
        EventReviewService service = new EventReviewService(memberRepository, eventRepository, approvalRequestRepository,
                eventCommandService, creatorRepository);

        var result = service.findPending(1L, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(EventReviewService.PendingEvent::creatorName)
                .containsExactly("첫 크리에이터", "둘째 크리에이터");
        verify(eventRepository).findByEventIdIn(List.of(10L, 20L));
        verify(creatorRepository).findByCreatorIdIn(org.mockito.ArgumentMatchers.argThat(
                ids -> ids.size() == 2 && ids.containsAll(List.of(100L, 200L))));
        verify(eventRepository, never()).findById(org.mockito.ArgumentMatchers.anyLong());
        verify(creatorRepository, never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    private Event event(Long eventId, Long creatorId, String title) {
        Event event = new Event(creatorId, title, null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED, 1L, "550e8400-e29b-41d4-a716-446655440000");
        ReflectionTestUtils.setField(event, "eventId", eventId);
        return event;
    }

    private Creator creator(Long creatorId, String name) {
        Creator creator = new Creator(creatorId, name);
        ReflectionTestUtils.setField(creator, "creatorId", creatorId);
        return creator;
    }
}

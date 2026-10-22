package kr.co.cking.event.application;

import java.time.Instant;
import java.util.Optional;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.event.application.service.EventClosingService;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ManualEventCloseServiceTest {

    @Test
    void adminCanCloseAnyOpenEvent() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        CreatorRepository creatorRepository = mock(CreatorRepository.class);
        EventRepository eventRepository = mock(EventRepository.class);
        EventClosingService eventClosingService = mock(EventClosingService.class);
        Member admin = member(1L, MemberRole.ADMIN);
        Event event = event(10L, 200L, EventStatus.OPEN);
        EventClosingService.ClosingResult result = new EventClosingService.ClosingResult(10L, EventStatus.CLOSING);
        given(memberRepository.findById(1L)).willReturn(Optional.of(admin));
        given(eventRepository.findById(10L)).willReturn(Optional.of(event));
        given(eventClosingService.startClosing(10L)).willReturn(result);

        EventClosingService.ClosingResult actual = service(memberRepository, creatorRepository, eventRepository,
                eventClosingService).close(1L, 10L);

        assertThat(actual).isEqualTo(result);
        verify(eventClosingService).startClosing(10L);
        verify(creatorRepository, never()).findByMemberId(1L);
    }

    @Test
    void creatorCanCloseOwnOpenEvent() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        CreatorRepository creatorRepository = mock(CreatorRepository.class);
        EventRepository eventRepository = mock(EventRepository.class);
        EventClosingService eventClosingService = mock(EventClosingService.class);
        Member creatorMember = member(1L, MemberRole.USER);
        Creator creator = creator(20L, 1L);
        Event event = event(10L, 20L, EventStatus.OPEN);
        given(memberRepository.findById(1L)).willReturn(Optional.of(creatorMember));
        given(creatorRepository.findByMemberId(1L)).willReturn(Optional.of(creator));
        given(eventRepository.findById(10L)).willReturn(Optional.of(event));

        service(memberRepository, creatorRepository, eventRepository, eventClosingService).close(1L, 10L);

        verify(eventClosingService).startClosing(10L);
    }

    @Test
    void missingMemberIsRejected() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        EventClosingService eventClosingService = mock(EventClosingService.class);
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        assertError(() -> service(memberRepository, mock(CreatorRepository.class), mock(EventRepository.class),
                eventClosingService).close(1L, 10L), CommonErrorCode.RESOURCE_NOT_FOUND);
        verify(eventClosingService, never()).startClosing(10L);
    }

    @Test
    void missingOrDeletedEventIsRejected() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        CreatorRepository creatorRepository = mock(CreatorRepository.class);
        EventRepository eventRepository = mock(EventRepository.class);
        EventClosingService eventClosingService = mock(EventClosingService.class);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member(1L, MemberRole.ADMIN)));
        given(eventRepository.findById(10L)).willReturn(Optional.empty());

        assertError(() -> service(memberRepository, creatorRepository, eventRepository, eventClosingService)
                .close(1L, 10L), CommonErrorCode.RESOURCE_NOT_FOUND);

        Event deletedEvent = event(10L, 20L, EventStatus.OPEN);
        ReflectionTestUtils.setField(deletedEvent, "deletedAt", Instant.now());
        given(eventRepository.findById(10L)).willReturn(Optional.of(deletedEvent));

        assertError(() -> service(memberRepository, creatorRepository, eventRepository, eventClosingService)
                .close(1L, 10L), CommonErrorCode.RESOURCE_NOT_FOUND);
        verify(eventClosingService, never()).startClosing(10L);
    }

    @Test
    void creatorCannotCloseAnotherCreatorsEvent() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        CreatorRepository creatorRepository = mock(CreatorRepository.class);
        EventRepository eventRepository = mock(EventRepository.class);
        EventClosingService eventClosingService = mock(EventClosingService.class);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member(1L, MemberRole.USER)));
        given(creatorRepository.findByMemberId(1L)).willReturn(Optional.of(creator(20L, 1L)));
        given(eventRepository.findById(10L)).willReturn(Optional.of(event(10L, 30L, EventStatus.OPEN)));

        assertError(() -> service(memberRepository, creatorRepository, eventRepository, eventClosingService)
                .close(1L, 10L), CommonErrorCode.FORBIDDEN);
        verify(eventClosingService, never()).startClosing(10L);
    }

    @Test
    void onlyOpenEventCanBeClosed() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        CreatorRepository creatorRepository = mock(CreatorRepository.class);
        EventRepository eventRepository = mock(EventRepository.class);
        EventClosingService eventClosingService = mock(EventClosingService.class);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member(1L, MemberRole.ADMIN)));
        given(eventRepository.findById(10L)).willReturn(Optional.of(event(10L, 20L, EventStatus.SCHEDULED)));

        assertError(() -> service(memberRepository, creatorRepository, eventRepository, eventClosingService)
                .close(1L, 10L), EventErrorCode.INVALID_STATE);
        verify(eventClosingService, never()).startClosing(10L);
    }

    @Test
    void closingOrClosedEventCanBeClosedIdempotently() {
        for (EventStatus status : new EventStatus[]{EventStatus.CLOSING, EventStatus.CLOSED}) {
            MemberRepository memberRepository = mock(MemberRepository.class);
            EventRepository eventRepository = mock(EventRepository.class);
            EventClosingService eventClosingService = mock(EventClosingService.class);
            given(memberRepository.findById(1L)).willReturn(Optional.of(member(1L, MemberRole.ADMIN)));
            given(eventRepository.findById(10L)).willReturn(Optional.of(event(10L, 20L, status)));
            given(eventClosingService.startClosing(10L))
                    .willReturn(new EventClosingService.ClosingResult(10L, status));

            EventClosingService.ClosingResult result = service(memberRepository, mock(CreatorRepository.class),
                    eventRepository, eventClosingService).close(1L, 10L);

            assertThat(result.status()).isEqualTo(status);
            verify(eventClosingService).startClosing(10L);
        }
    }

    private ManualEventCloseService service(MemberRepository memberRepository, CreatorRepository creatorRepository,
            EventRepository eventRepository, EventClosingService eventClosingService) {
        return new ManualEventCloseService(memberRepository, creatorRepository, eventRepository, eventClosingService);
    }

    private Member member(Long memberId, MemberRole role) {
        Member member = new Member("사용자", null, null, role);
        ReflectionTestUtils.setField(member, "memberId", memberId);
        return member;
    }

    private Creator creator(Long creatorId, Long memberId) {
        Creator creator = new Creator(memberId, "크리에이터");
        ReflectionTestUtils.setField(creator, "creatorId", creatorId);
        return creator;
    }

    private Event event(Long eventId, Long creatorId, EventStatus status) {
        Event event = Event.builder()
                .creatorId(creatorId)
                .requestId("550e8400-e29b-41d4-a716-446655440000")
                .title("이벤트")
                .startAt(Instant.now().minusSeconds(60))
                .endAt(Instant.now().plusSeconds(60))
                .winnerCount(1)
                .drawMethod(DrawMethod.WEIGHTED.name())
                .status(status)
                .createdBy(1L)
                .createdAt(Instant.now())
                .build();
        ReflectionTestUtils.setField(event, "eventId", eventId);
        return event;
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            kr.co.cking.common.exception.ErrorCode errorCode) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(errorCode);
    }
}

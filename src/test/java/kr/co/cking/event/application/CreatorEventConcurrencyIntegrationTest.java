package kr.co.cking.event.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.event.application.dto.CreateEventCommand;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventApprovalRequestStatus;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventApprovalRequestRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.event.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Instant;

import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CreatorEventConcurrencyIntegrationTest {
    @Autowired private CreatorEventService service;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventApprovalRequestRepository approvalRequestRepository;
    @Autowired private EventReviewService eventReviewService;
    @Autowired private EventCommandService eventCommandService;
    @Autowired private EventQueryService eventQueryService;
    @Autowired private EventCache eventCache;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 동시성 테스트의 고정 멱등 키가 이전 실행과 충돌하지 않도록 Event를 정리한다. */
    @BeforeEach
    void cleanTestEvent() {
        jdbcTemplate.update("DELETE FROM event_approval_request WHERE event_id IN (SELECT event_id FROM event WHERE request_id LIKE ?)", "550e8400-e29b-41d4-a716-4466554400%");
        jdbcTemplate.update("DELETE FROM event WHERE request_id LIKE ?", "550e8400-e29b-41d4-a716-4466554400%");
    }

    /** 동일 멱등 키의 병렬 생성이 하나의 Event 식별자로 수렴하는지 검증한다. */
    @Test
    void concurrentSameRequestReturnsSameEvent() throws Exception {
        Member member = memberRepository.saveAndFlush(new Member("동시생성", null, null, MemberRole.USER));
        creatorRepository.saveAndFlush(new Creator(member.getMemberId(), member.getName()));
        CreateEventCommand command = new CreateEventCommand(member.getMemberId(), "550e8400-e29b-41d4-a716-446655440008",
                "동시 이벤트", null, Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)), 1, DrawMethod.WEIGHTED);
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> runCreate(start, command));
            Future<String> second = executor.submit(() -> runCreate(start, command));
            start.countDown();
            java.util.List<String> results = java.util.List.of(first.get(), second.get());
            assertThat(results).withFailMessage("병렬 생성 결과: %s", results)
                    .allMatch(result -> result.equals("SUCCESS") || result.equals("CONCURRENT_COMMAND"));
            assertThat(results).contains("SUCCESS");
            assertThat(eventRepository.findByRequestId(command.requestId())).isPresent();
        } finally { executor.shutdownNow(); }
    }

    /** 동시 승인·거절 명령에서 하나의 심사 결과만 기록되는지 검증한다. */
    @Test
    void concurrentApprovalAndRejectionLeaveExactlyOneReviewedRequest() throws Exception {
        Member creator = memberRepository.saveAndFlush(new Member("동시 심사 크리에이터", null, null, MemberRole.USER));
        creatorRepository.saveAndFlush(new Creator(creator.getMemberId(), creator.getName()));
        Member admin = memberRepository.saveAndFlush(new Member("동시 심사 관리자", null, null, MemberRole.ADMIN));
        Event event = service.create(new CreateEventCommand(creator.getMemberId(),
                "550e8400-e29b-41d4-a716-446655440014", "심사 경합", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED));
        service.requestApproval(creator.getMemberId(), event.getEventId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> approval = executor.submit(() -> runReview(start,
                    () -> eventReviewService.approve(admin.getMemberId(), event.getEventId()), "SCHEDULED"));
            Future<String> rejection = executor.submit(() -> runReview(start,
                    () -> eventReviewService.reject(admin.getMemberId(), event.getEventId(), "일정 재검토"), "REJECTED"));
            start.countDown();
            java.util.List<String> results = java.util.List.of(approval.get(), rejection.get());

            assertThat(results).contains("INVALID_STATE").containsAnyOf("SCHEDULED", "REJECTED");
            EventStatus finalStatus = eventRepository.findById(event.getEventId()).orElseThrow().getStatus();
            boolean approved = approvalRequestRepository
                    .findByEventIdAndStatus(event.getEventId(), EventApprovalRequestStatus.APPROVED).isPresent();
            boolean rejected = approvalRequestRepository
                    .findByEventIdAndStatus(event.getEventId(), EventApprovalRequestStatus.REJECTED).isPresent();
            assertThat(finalStatus).isIn(EventStatus.SCHEDULED, EventStatus.REJECTED);
            assertThat(approved).isNotEqualTo(rejected);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 병렬 Scheduler 호출에서도 Event 행 잠금으로 OPEN 전이는 한 번만 성공한다. */
    @Test
    void concurrentOpenTransitionsScheduledEventExactlyOnce() throws Exception {
        Member creator = memberRepository.saveAndFlush(new Member("동시 시작 크리에이터", null, null, MemberRole.USER));
        Creator savedCreator = creatorRepository.saveAndFlush(new Creator(creator.getMemberId(), creator.getName()));
        Event event = eventRepository.saveAndFlush(Event.builder()
                .creatorId(savedCreator.getCreatorId())
                .requestId("550e8400-e29b-41d4-a716-446655440015")
                .title("동시 시작 이벤트")
                .startAt(Instant.now().minusSeconds(1))
                .endAt(Instant.now().plus(java.time.Duration.ofDays(1)))
                .winnerCount(1)
                .drawMethod(DrawMethod.WEIGHTED.name())
                .status(EventStatus.SCHEDULED)
                .createdBy(creator.getMemberId())
                .createdAt(Instant.now())
                .build());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> runOpen(start, event.getEventId()));
            Future<String> second = executor.submit(() -> runOpen(start, event.getEventId()));
            start.countDown();

            java.util.List<String> results = java.util.List.of(first.get(), second.get());

            assertThat(results).containsExactlyInAnyOrder("OPEN", "INVALID_STATE");
            assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getStatus())
                    .isEqualTo(EventStatus.OPEN);
        } finally {
            executor.shutdownNow();
        }
    }

    /** SCHEDULED 캐시가 있어도 OPEN 전이 성공 뒤에는 stale cache:event 항목을 제거한다. */
    @Test
    void openingScheduledEventEvictsCachedEvent() {
        Event event = persistScheduledEvent("550e8400-e29b-41d4-a716-446655440016");
        eventQueryService.getCachedEvent(event.getEventId());

        assertThat(eventCache.find(event.getEventId())).isPresent();

        eventCommandService.open(event.getEventId());

        assertThat(eventCache.find(event.getEventId())).isEmpty();
        assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getStatus())
                .isEqualTo(EventStatus.OPEN);
    }

    /** 병렬 생성 결과를 성공 또는 도메인 오류 코드로 변환한다. */
    private String runCreate(CountDownLatch start, CreateEventCommand command) throws InterruptedException {
        start.await();
        try { service.create(command); return "SUCCESS"; }
        catch (kr.co.cking.common.exception.BusinessException exception) { return exception.getErrorCode().code(); }
    }

    /** 병렬 심사 결과를 최종 상태 또는 도메인 오류 코드로 변환한다. */
    private String runReview(CountDownLatch start, Runnable command, String success) throws InterruptedException {
        start.await();
        try {
            command.run();
            return success;
        } catch (kr.co.cking.common.exception.BusinessException exception) {
            return exception.getErrorCode().code();
        }
    }

    private String runOpen(CountDownLatch start, Long eventId) throws InterruptedException {
        start.await();
        try {
            eventCommandService.open(eventId);
            return "OPEN";
        } catch (kr.co.cking.common.exception.BusinessException exception) {
            return exception.getErrorCode().code();
        }
    }

    private Event persistScheduledEvent(String requestId) {
        Member creator = memberRepository.saveAndFlush(new Member("캐시 시작 크리에이터", null, null, MemberRole.USER));
        Creator savedCreator = creatorRepository.saveAndFlush(new Creator(creator.getMemberId(), creator.getName()));
        return eventRepository.saveAndFlush(Event.builder()
                .creatorId(savedCreator.getCreatorId())
                .requestId(requestId)
                .title("캐시 시작 이벤트")
                .startAt(Instant.now().minusSeconds(1))
                .endAt(Instant.now().plus(java.time.Duration.ofDays(1)))
                .winnerCount(1)
                .drawMethod(DrawMethod.WEIGHTED.name())
                .status(EventStatus.SCHEDULED)
                .createdBy(creator.getMemberId())
                .createdAt(Instant.now())
                .build());
    }
}

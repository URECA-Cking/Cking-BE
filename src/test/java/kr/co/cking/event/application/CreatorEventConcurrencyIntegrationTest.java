package kr.co.cking.event.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.event.application.dto.CreateEventCommand;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.application.service.EventClosingService;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventApprovalRequestStatus;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventApprovalRequestRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.event.repository.EventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Instant;

import java.util.ArrayList;
import java.util.List;
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
    @Autowired private EventClosingService eventClosingService;
    @Autowired private EventQueryService eventQueryService;
    @Autowired private EventCache eventCache;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> creatorIds = new ArrayList<>();

    /** 동시성 테스트의 고정 멱등 키가 이전 실행과 충돌하지 않도록 Event를 정리한다. */
    @BeforeEach
    void cleanTestEvent() {
        jdbcTemplate.update("DELETE FROM event_approval_request WHERE event_id IN (SELECT event_id FROM event WHERE request_id LIKE ?)", "550e8400-e29b-41d4-a716-4466554400%");
        jdbcTemplate.update("DELETE FROM event WHERE request_id LIKE ?", "550e8400-e29b-41d4-a716-4466554400%");
    }

    /** 테스트가 생성한 Event와 연관 fixture를 외래 키 의존성의 역순으로 정리한다. */
    @AfterEach
    void cleanUp() {
        List<Long> eventIds = jdbcTemplate.queryForList(
                "SELECT event_id FROM event WHERE request_id LIKE ?",
                Long.class,
                "550e8400-e29b-41d4-a716-4466554400%"
        );
        eventIds.forEach(eventCache::evict);
        cleanTestEvent();
        creatorRepository.deleteAllById(creatorIds);
        memberRepository.deleteAllById(memberIds);
    }

    /** 동일 멱등 키의 병렬 생성이 하나의 Event 식별자로 수렴하는지 검증한다. */
    @Test
    void concurrentSameRequestReturnsSameEvent() throws Exception {
        Member member = saveMember("동시생성", MemberRole.USER);
        saveCreator(member);
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
        Member creator = saveMember("동시 심사 크리에이터", MemberRole.USER);
        saveCreator(creator);
        Member admin = saveMember("동시 심사 관리자", MemberRole.ADMIN);
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
        Member creator = saveMember("동시 시작 크리에이터", MemberRole.USER);
        Creator savedCreator = saveCreator(creator);
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

    /** 자동·수동 마감이 동시에 시작돼도 OPEN→CLOSING 전이는 한 번으로 수렴한다. */
    @Test
    void concurrentAutomaticAndManualClosingReturnCurrentClosingStatus() throws Exception {
        Event event = persistOpenEvent("550e8400-e29b-41d4-a716-446655440019");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<EventStatus> automatic = executor.submit(() -> runStartClosing(start, event.getEventId()));
            Future<EventStatus> manual = executor.submit(() -> runStartClosing(start, event.getEventId()));
            start.countDown();

            assertThat(java.util.List.of(automatic.get(), manual.get()))
                    .containsOnly(EventStatus.CLOSING);
            Event closedEvent = eventRepository.findById(event.getEventId()).orElseThrow();
            assertThat(closedEvent.getStatus()).isEqualTo(EventStatus.CLOSING);
            assertThat(closedEvent.getCutoffStreamId()).isNotBlank();
        } finally {
            executor.shutdownNow();
        }
    }

    /** 다른 마감 흐름이 완료한 뒤의 요청도 CLOSING으로 고정하지 않고 실제 상태를 반환한다. */
    @Test
    void closingAlreadyClosedEventReturnsClosedStatus() {
        Event event = persistClosedEvent("550e8400-e29b-41d4-a716-446655440020");

        EventStatus status = eventClosingService.startClosing(event.getEventId()).status();

        assertThat(status).isEqualTo(EventStatus.CLOSED);
        assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getStatus())
                .isEqualTo(EventStatus.CLOSED);
    }

    /** 병렬 INITIAL Drawing 완료 호출에서도 Event 행 잠금으로 DRAW_COMPLETED 전이는 한 번만 성공한다. */
    @Test
    void concurrentDrawingCompletionTransitionsClosedEventExactlyOnce() throws Exception {
        Event event = persistClosedEvent("550e8400-e29b-41d4-a716-446655440017");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> runCompleteDrawing(start, event.getEventId()));
            Future<String> second = executor.submit(() -> runCompleteDrawing(start, event.getEventId()));
            start.countDown();

            java.util.List<String> results = java.util.List.of(first.get(), second.get());

            assertThat(results).containsExactlyInAnyOrder("DRAW_COMPLETED", "INVALID_STATE");
            assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getStatus())
                    .isEqualTo(EventStatus.DRAW_COMPLETED);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 병렬 결과 공개 호출에서도 Event 행 잠금으로 PUBLISHED 전이는 한 번만 성공한다. */
    @Test
    void concurrentPublishingTransitionsDrawingCompletedEventExactlyOnce() throws Exception {
        Event event = persistDrawingCompletedEvent("550e8400-e29b-41d4-a716-446655440018");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> runPublish(start, event.getEventId()));
            Future<String> second = executor.submit(() -> runPublish(start, event.getEventId()));
            start.countDown();

            java.util.List<String> results = java.util.List.of(first.get(), second.get());

            assertThat(results).containsExactlyInAnyOrder("PUBLISHED", "INVALID_STATE");
            assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getStatus())
                    .isEqualTo(EventStatus.PUBLISHED);
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

    private EventStatus runStartClosing(CountDownLatch start, Long eventId) throws InterruptedException {
        start.await();
        return eventClosingService.startClosing(eventId).status();
    }

    /** 병렬 INITIAL Drawing 완료 호출의 결과를 상태 또는 오류 코드로 변환한다. */
    private String runCompleteDrawing(CountDownLatch start, Long eventId) throws InterruptedException {
        start.await();
        try {
            eventCommandService.completeDrawing(eventId);
            return "DRAW_COMPLETED";
        } catch (kr.co.cking.common.exception.BusinessException exception) {
            return exception.getErrorCode().code();
        }
    }

    /** 병렬 결과 공개 호출의 결과를 상태 또는 오류 코드로 변환한다. */
    private String runPublish(CountDownLatch start, Long eventId) throws InterruptedException {
        start.await();
        try {
            eventCommandService.publish(eventId);
            return "PUBLISHED";
        } catch (kr.co.cking.common.exception.BusinessException exception) {
            return exception.getErrorCode().code();
        }
    }

    private Event persistScheduledEvent(String requestId) {
        Member creator = saveMember("캐시 시작 크리에이터", MemberRole.USER);
        Creator savedCreator = saveCreator(creator);
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

    private Event persistOpenEvent(String requestId) {
        Member creator = saveMember("동시 마감 크리에이터", MemberRole.USER);
        Creator savedCreator = saveCreator(creator);
        return eventRepository.saveAndFlush(Event.builder()
                .creatorId(savedCreator.getCreatorId())
                .requestId(requestId)
                .title("동시 마감 이벤트")
                .startAt(Instant.now().minusSeconds(1))
                .endAt(Instant.now().plus(java.time.Duration.ofDays(1)))
                .winnerCount(1)
                .drawMethod(DrawMethod.WEIGHTED.name())
                .status(EventStatus.OPEN)
                .createdBy(creator.getMemberId())
                .createdAt(Instant.now())
                .build());
    }

    /** 병렬 추첨 완료 전이 테스트에 사용할 CLOSED Event를 저장한다. */
    private Event persistClosedEvent(String requestId) {
        Member creator = saveMember("동시 추첨 크리에이터", MemberRole.USER);
        Creator savedCreator = saveCreator(creator);
        return eventRepository.saveAndFlush(Event.builder()
                .creatorId(savedCreator.getCreatorId())
                .requestId(requestId)
                .title("동시 추첨 완료 이벤트")
                .startAt(Instant.now().minus(java.time.Duration.ofDays(2)))
                .endAt(Instant.now().minus(java.time.Duration.ofDays(1)))
                .winnerCount(1)
                .drawMethod(DrawMethod.WEIGHTED.name())
                .status(EventStatus.CLOSED)
                .createdBy(creator.getMemberId())
                .createdAt(Instant.now())
                .build());
    }

    /** 병렬 결과 공개 전이 테스트에 사용할 DRAW_COMPLETED Event를 저장한다. */
    private Event persistDrawingCompletedEvent(String requestId) {
        Member creator = saveMember("동시 공개 크리에이터", MemberRole.USER);
        Creator savedCreator = saveCreator(creator);
        return eventRepository.saveAndFlush(Event.builder()
                .creatorId(savedCreator.getCreatorId())
                .requestId(requestId)
                .title("동시 결과 공개 이벤트")
                .startAt(Instant.now().minus(java.time.Duration.ofDays(2)))
                .endAt(Instant.now().minus(java.time.Duration.ofDays(1)))
                .winnerCount(1)
                .drawMethod(DrawMethod.WEIGHTED.name())
                .status(EventStatus.DRAW_COMPLETED)
                .createdBy(creator.getMemberId())
                .createdAt(Instant.now())
                .build());
    }

    /** Member를 저장하고 사후 정리 대상에 등록한다. */
    private Member saveMember(String name, MemberRole role) {
        Member member = memberRepository.saveAndFlush(new Member(name, null, null, role));
        memberIds.add(member.getMemberId());
        return member;
    }

    /** Creator를 저장하고 사후 정리 대상에 등록한다. */
    private Creator saveCreator(Member member) {
        Creator creator = creatorRepository.saveAndFlush(new Creator(member.getMemberId(), member.getName()));
        creatorIds.add(creator.getCreatorId());
        return creator;
    }
}

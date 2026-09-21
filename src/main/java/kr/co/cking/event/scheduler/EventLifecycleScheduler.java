package kr.co.cking.event.scheduler;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.application.service.EventClosingService;
import kr.co.cking.event.application.service.EventDrainChecker;
import kr.co.cking.event.application.service.EventGateLoader;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.snapshot.application.OfficialSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 자동 마감 처리 오케스트레이션(취합v1.5.4 §6.2, FR-P2-023~026). 틱마다 두 단계를 수행한다.
 *
 * <ol>
 *   <li>{@code endAt}이 지난 OPEN 이벤트: Gate 차단·cutoff 확정(barrier) 후 짧은 Tx1로 CLOSING 전이.</li>
 *   <li>CLOSING 이벤트: Drain(cutoff까지 응모 Stream 소비) 완료 여부를 확인해 끝났으면 짧은 Tx2로 CLOSED 전이.</li>
 * </ol>
 *
 * <p>awaitDrain을 스케줄러 안에서 블로킹으로 기다리지 않고, "Drain 됐는지 확인 → 아니면 다음 틱에
 * 다시 확인"으로 풀어낸다. 10초 주기 자체가 재시도 간격이 되므로 별도 대기 루프·타임아웃 로직이
 * 필요 없다 - Drain이 실패하거나 오래 걸려도 CLOSING 상태로 안전하게 남고, 서버가 재기동돼도
 * {@link EventRepository#findByStatus} 조회가 그대로 CLOSING 이벤트를 다시 찾아 재개한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventLifecycleScheduler {

    private final EventRepository eventRepository;
    private final EventCommandService eventCommandService;
    private final EventClosingService eventClosingService;
    private final EventDrainChecker eventDrainChecker;
    private final EventGateLoader eventGateLoader;
    private final OfficialSnapshotService officialSnapshotService;
    private final Clock clock;

    /** 예약 시작, 마감 시작, Drain 완료 처리를 순서대로 한 번 실행한다. */
    @Scheduled(fixedDelayString = "${cking.event.lifecycle-interval-ms:10000}")
    public void run() {
        openScheduledEvents();
        restoreOpenGates();
        startClosingOverdueEvents();
        completeDrainedEvents();
    }

    /** 지정 Event만 생명주기 조건에 따라 한 번 처리한다. */
    public void run(Long eventId) {
        eventRepository.findById(eventId).ifPresent(this::processEvent);
    }

    /** 시작 시각에 도달한 예약 Event를 OPEN 상태로 전이한다. */
    private void openScheduledEvents() {
        Instant now = clock.instant();
        for (Event event : eventRepository.findByStatusAndStartAtLessThanEqualAndEndAtGreaterThan(
                EventStatus.SCHEDULED,
                now,
                now
        )) {
            Long eventId = event.getEventId();
            try {
                eventCommandService.open(eventId);
            } catch (RuntimeException e) {
                log.error("이벤트 자동 시작(SCHEDULED→OPEN)에 실패했습니다. eventId={}", eventId, e);
            }
        }
    }

    /**
     * 기동 직후, Redis 유실·eviction, OPEN 직후 적재 실패로 Gate 키가 없는 진행 중 Event를 DB 기준으로 복원한다
     * (취합v1.5.4 §2.4). 종료 시각이 지난 Event는 곧 마감되므로 제외하고, 이미 있는 키는 덮어쓰지 않는다.
     * OPEN 적재 뒤 CLOSING 이벤트를 새로 조회해 Gate를 닫으므로, cutoff까지 유실된 상태에서 stale한 OPEN 조회로
     * 다시 열린 Gate도 같은 틱에 닫힌다. cutoff는 DB의 cutoffStreamId로 Drain하므로 복구하지 않는다.
     *
     * <p>ponytail: 매 틱 OPEN 전체 조회는 소수 이벤트 전제다. 복원 주기·조회 범위·페이징은 2차 MVP에서 팀 합의로 확정한다(#149).
     */
    private void restoreOpenGates() {
        for (Event event : eventRepository.findByStatus(EventStatus.OPEN)) {
            try {
                if (event.getEndAt().isAfter(clock.instant())) {
                    eventGateLoader.load(event);
                }
            } catch (RuntimeException e) {
                log.error("응모 Gate 복원에 실패했습니다. eventId={}", event.getEventId(), e);
            }
        }
        for (Event event : eventRepository.findByStatus(EventStatus.CLOSING)) {
            try {
                eventGateLoader.close(event.getEventId());
            } catch (RuntimeException e) {
                log.error("마감 중 응모 Gate 차단에 실패했습니다. eventId={}", event.getEventId(), e);
            }
        }
    }

    /** 종료 시각이 지난 OPEN Event의 마감 시작을 시스템2 서비스에 요청한다. */
    private void startClosingOverdueEvents() {
        Instant now = clock.instant();
        for (Event event : eventRepository.findByStatusAndEndAtLessThanEqual(EventStatus.OPEN, now)) {
            Long eventId = event.getEventId();
            try {
                eventClosingService.startClosing(eventId);
            } catch (RuntimeException e) {
                log.error("이벤트 마감 시작(OPEN→CLOSING)에 실패했습니다. eventId={}", eventId, e);
            }
        }
    }

    /** cutoff까지 Drain된 CLOSING Event를 CLOSED로 확정하고 공식 Snapshot 생성을 요청한다. */
    private void completeDrainedEvents() {
        for (Event event : eventRepository.findByStatus(EventStatus.CLOSING)) {
            completeDrainedEvent(event);
        }
    }

    private void processEvent(Event event) {
        Instant now = clock.instant();
        if (event.getStatus() == EventStatus.SCHEDULED
                && !event.getStartAt().isAfter(now)
                && event.getEndAt().isAfter(now)) {
            eventCommandService.open(event.getEventId());
            return;
        }
        if (event.getStatus() == EventStatus.OPEN && !event.getEndAt().isAfter(now)) {
            eventClosingService.startClosing(event.getEventId());
            return;
        }
        if (event.getStatus() == EventStatus.CLOSING) {
            completeDrainedEvent(event);
        }
    }

    private void completeDrainedEvent(Event event) {
        Long eventId = event.getEventId();
        String cutoffStreamId = event.getCutoffStreamId();
        if (cutoffStreamId == null) {
            log.error("CLOSING 이벤트에 cutoffStreamId가 없습니다. eventId={}", eventId);
            return;
        }
        try {
            if (eventDrainChecker.isDrained(eventId, cutoffStreamId)) {
                eventCommandService.completeClosing(eventId);
                officialSnapshotService.createIfAbsent(eventId);
            }
        } catch (RuntimeException e) {
            log.error("이벤트 마감 완료(CLOSING→CLOSED) 확인에 실패했습니다. eventId={}", eventId, e);
        }
    }
}

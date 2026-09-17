package kr.co.cking.event.scheduler;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.application.service.EventClosingService;
import kr.co.cking.event.application.service.EventDrainChecker;
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
    private final OfficialSnapshotService officialSnapshotService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${cking.event.lifecycle-interval-ms:10000}")
    public void run() {
        openScheduledEvents();
        startClosingOverdueEvents();
        completeDrainedEvents();
    }

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

    private void completeDrainedEvents() {
        for (Event event : eventRepository.findByStatus(EventStatus.CLOSING)) {
            Long eventId = event.getEventId();
            String cutoffStreamId = event.getCutoffStreamId();
            if (cutoffStreamId == null) {
                log.error("CLOSING 이벤트에 cutoffStreamId가 없습니다. eventId={}", eventId);
                continue;
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
}

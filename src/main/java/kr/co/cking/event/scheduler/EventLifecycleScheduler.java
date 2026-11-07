package kr.co.cking.event.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import kr.co.cking.creator.repository.CreatorRepository;
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
    private final CreatorRepository creatorRepository;
    private final EventCommandService eventCommandService;
    private final EventClosingService eventClosingService;
    private final EventDrainChecker eventDrainChecker;
    private final EventGateLoader eventGateLoader;
    private final OfficialSnapshotService officialSnapshotService;
    private final Clock clock;

    /**
     * Drain이 끝나지 않은 CLOSING 이벤트는 마감 시작 후 30틱(10초 틱 기준 약 5분)마다 WARN을 남기고,
     * 180틱(약 30분)부터는 같은 주기로 ERROR로 올려 "정상 지연"과 "사실상 멈춘 상태"를 로그 레벨로
     * 구분한다. Drain을 강제로 끝내거나 CLOSED로 전환하지는 않는다(FR-11b, 취합v1.5.4 §6.8 원칙
     * 유지) — 그건 여전히 수동 개입(Dead Stream 확인·replay 등) 영역이다.
     *
     * <p>FR-11b는 이 구체값을 시스템2가 자율 결정하도록 위임했다. MVP concurrency=1 Consumer
     * 기준 정상 지연은 5분 WARN 안에서 대부분 해소된다고 보고, 30분을 넘기면 실측 부하 테스트
     * (NFR-06) 이전에도 운영자가 봐야 할 이상 신호로 잡았다. 실측 이후 재조정할 수 있는 잠정값이라
     * tick 간격과 마찬가지로 프로퍼티로 뺐고, "틱 수 × tick 간격"을 시간 임계값으로 쓴다.
     *
     * <p>경과 시간은 인스턴스 메모리가 아니라 DB의 {@code cutoffStreamId}에서 계산한다(#797). cutoff는 마감 barrier를
     * {@code XADD *}로 넣고 받은 Stream ID({@code 밀리초-시퀀스})라 앞부분이 곧 마감 시작 시각이다. 그래서 재배포·
     * 인스턴스 교체 뒤에도 경과 시간이 이어진다.
     */
    @Value("${cking.event.lifecycle-interval-ms:10000}")
    private long lifecycleIntervalMs = 10_000L;

    @Value("${cking.event.drain-warn-every-ticks:30}")
    private int drainWarnEveryTicks = 30;

    @Value("${cking.event.drain-error-after-ticks:180}")
    private int drainErrorAfterTicks = 180;

    /** Redis Stream ID 형식({@code 밀리초-시퀀스}, 둘 다 음이 아닌 정수). */
    private static final Pattern STREAM_ID = Pattern.compile("(\\d{1,18})-(\\d{1,19})");

    /** eventId별로 마지막으로 경고한 구간 번호. 같은 구간 중복 경고만 막는다(경과 시간의 기준은 아니다). */
    private final Map<Long, Long> lastWarnedPeriod = new ConcurrentHashMap<>();

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
            if (!creatorRepository.findById(event.getCreatorId()).map(creator -> creator.canOperate()).orElse(false)) {
                log.info("비활성 Creator의 예약 이벤트 자동 시작을 건너뜁니다. eventId={}", eventId);
                continue;
            }
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
     * <p>ponytail: 매 틱 OPEN 전체 조회는 OPEN 이벤트 100개 이하 전제다(#149). 100개를 넘거나 틱 실행 시간이 주기의 절반을
     * 넘으면 Slice 페이징을 도입한다. 10초는 fixedDelay라서 Redis가 정상일 때 Gate 유실 복원을 재시도하는 기본 간격일 뿐,
     * 실제 GATE_NOT_LOADED 지속 시간은 틱 실행 시간과 Redis 장애 기간만큼 10초를 넘을 수 있다.
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
        List<Event> closing = eventRepository.findByStatus(EventStatus.CLOSING);
        for (Event event : closing) {
            completeDrainedEvent(event);
        }
        // 이 인스턴스나 다른 인스턴스가 CLOSED로 넘긴 이벤트의 경고 기록을 지운다.
        lastWarnedPeriod.keySet().retainAll(closing.stream().map(Event::getEventId).toList());
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
                createSnapshot(eventId);
                return;
            }
            warnIfDrainDelayed(eventId, cutoffStreamId);
        } catch (RuntimeException e) {
            log.error("이벤트 마감 완료(CLOSING→CLOSED) 확인에 실패했습니다. eventId={}", eventId, e);
        }
    }

    /**
     * 마감 시작 후 경고 주기(기본 5분)의 새 구간에 들어섰을 때 한 번 로그를 남긴다. 구간 번호는 경과 시간으로 계산하고,
     * {@link #lastWarnedPeriod}는 같은 구간에서 틱마다 반복하지 않게 막는 용도일 뿐이다. 재기동으로 비면 현재 구간을 바로
     * 한 번 남기므로(예: 31분 경과면 즉시 ERROR) 경과 시간은 이어진다. 인스턴스가 여러 대면 각자 남긴다.
     */
    private void warnIfDrainDelayed(Long eventId, String cutoffStreamId) {
        Matcher streamId = STREAM_ID.matcher(cutoffStreamId);
        if (!streamId.matches()) {
            // Drain 판정은 EventDrainChecker가 따로 하므로 경고만 건너뛴다.
            log.debug("cutoffStreamId에서 마감 시작 시각을 읽지 못해 지연 경고를 건너뜁니다. eventId={}, cutoffStreamId={}",
                    eventId, cutoffStreamId);
            return;
        }
        long cutoffMillis = Long.parseLong(streamId.group(1));
        long warnEveryMs = drainWarnEveryTicks * lifecycleIntervalMs;
        if (warnEveryMs <= 0) {
            return;
        }
        // cutoff 밀리초는 Redis 시계, 현재 시각은 앱 서버 시계다. 둘 다 NTP(AWS Time Sync)로 맞춰져 차이가 밀리초 수준이라는
        // 전제로 그대로 뺀다. 조금 어긋나 음수가 되면 막 시작한 것으로 본다.
        // ponytail: 시계 차이가 분 단위로 커질 수 있는 환경이면 현재 시각도 Redis TIME으로 읽는다.
        long elapsedMs = Math.max(0L, clock.instant().toEpochMilli() - cutoffMillis);
        long period = elapsedMs / warnEveryMs;
        if (period == 0 || period <= lastWarnedPeriod.getOrDefault(eventId, 0L)) {
            return;
        }
        lastWarnedPeriod.put(eventId, period);
        long elapsedMinutes = elapsedMs / 60_000L;
        if (elapsedMs >= drainErrorAfterTicks * lifecycleIntervalMs) {
            log.error("이벤트가 CLOSING에서 Drain을 {}분 넘게 끝내지 못하고 있습니다(운영자 확인 필요). eventId={}, cutoffStreamId={}",
                    elapsedMinutes, eventId, cutoffStreamId);
        } else {
            log.warn("이벤트가 CLOSING에서 Drain을 끝내지 못하고 있습니다(마감 시작 후 {}분 경과). eventId={}, cutoffStreamId={}",
                    elapsedMinutes, eventId, cutoffStreamId);
        }
    }

    /**
     * CLOSED 확정 뒤 실패해도 CLOSED를 되돌리지 않는다. 이 스케줄러는 CLOSING만 조회하므로 여기서는
     * 재시도되지 않는다. CLOSED인데 Snapshot이 없는 Event는 시스템3 누락 복구 스케줄러(FR-P3-034,
     * {@code SnapshotRecoveryScheduler})가 주기적으로 복구한다.
     */
    private void createSnapshot(Long eventId) {
        try {
            officialSnapshotService.createIfAbsent(eventId);
        } catch (RuntimeException e) {
            log.error("이벤트는 CLOSED로 확정됐지만 공식 Snapshot 생성에 실패했습니다. eventId={}", eventId, e);
        }
    }
}

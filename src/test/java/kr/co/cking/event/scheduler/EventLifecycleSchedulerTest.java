package kr.co.cking.event.scheduler;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.application.service.EventClosingService;
import kr.co.cking.event.application.service.EventDrainChecker;
import kr.co.cking.event.application.service.EventGateLoader;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.snapshot.application.OfficialSnapshotService;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class EventLifecycleSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-20T00:05:00Z");

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventCommandService eventCommandService;

    @Mock
    private EventClosingService eventClosingService;

    @Mock
    private EventDrainChecker eventDrainChecker;

    @Mock
    private EventGateLoader eventGateLoader;

    @Mock
    private OfficialSnapshotService officialSnapshotService;

    @Mock
    private Clock clock;

    @InjectMocks
    private EventLifecycleScheduler scheduler;

    @Test
    void 시작시각이_지난_SCHEDULED_이벤트를_OPEN으로_전이한다() {
        Event event = org.mockito.Mockito.mock(Event.class);
        when(event.getEventId()).thenReturn(1L);
        when(clock.instant()).thenReturn(NOW);
        when(eventRepository.findByStatusAndStartAtLessThanEqualAndEndAtGreaterThan(
                EventStatus.SCHEDULED,
                NOW,
                NOW
        )).thenReturn(List.of(event));

        scheduler.run();

        verify(eventCommandService).open(1L);
    }

    @Test
    void 진행중_OPEN_이벤트의_Gate를_복원하고_종료시각이_지난_이벤트는_건너뛴다() {
        Event live = org.mockito.Mockito.mock(Event.class);
        Event overdue = org.mockito.Mockito.mock(Event.class);
        when(live.getEndAt()).thenReturn(NOW.plusSeconds(60));
        when(overdue.getEndAt()).thenReturn(NOW.minusSeconds(1));
        when(clock.instant()).thenReturn(NOW);
        when(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(List.of(live, overdue));

        scheduler.run();

        verify(eventGateLoader).load(live);
        org.mockito.Mockito.verifyNoMoreInteractions(eventGateLoader);
    }

    @Test
    void CLOSING_이벤트의_Gate를_OPEN_적재_뒤에_닫는다() {
        Event live = org.mockito.Mockito.mock(Event.class);
        Event closing = org.mockito.Mockito.mock(Event.class);
        when(live.getEndAt()).thenReturn(NOW.plusSeconds(60));
        when(closing.getEventId()).thenReturn(7L);
        when(clock.instant()).thenReturn(NOW);
        when(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(List.of(live));
        when(eventRepository.findByStatus(EventStatus.CLOSING)).thenReturn(List.of(closing));

        scheduler.run();

        InOrder inOrder = inOrder(eventGateLoader);
        inOrder.verify(eventGateLoader).load(live);
        inOrder.verify(eventGateLoader).close(7L);
    }

    @Test
    void 종료시각이_지난_OPEN_이벤트는_공통_마감_서비스로_요청한다() {
        Event event = org.mockito.Mockito.mock(Event.class);
        when(event.getEventId()).thenReturn(1L);
        when(clock.instant()).thenReturn(NOW);
        when(eventRepository.findByStatusAndEndAtLessThanEqual(EventStatus.OPEN, NOW))
                .thenReturn(List.of(event));

        scheduler.run();

        verify(eventClosingService).startClosing(1L);
    }

    @Test
    void Drain_완료시_CLOSED_커밋후_공식_Snapshot을_생성한다() {
        Event event = org.mockito.Mockito.mock(Event.class);
        when(event.getEventId()).thenReturn(1L);
        when(event.getCutoffStreamId()).thenReturn("123-0");
        when(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.CLOSING)).thenReturn(List.of(event));
        when(eventDrainChecker.isDrained(1L, "123-0")).thenReturn(true);

        scheduler.run();

        InOrder inOrder = inOrder(eventCommandService, officialSnapshotService);
        inOrder.verify(eventCommandService).completeClosing(1L);
        inOrder.verify(officialSnapshotService).createIfAbsent(1L);
    }

    // #797: 지연 경고의 경과 시간은 메모리 틱 카운터가 아니라 cutoffStreamId(마감 barrier의 Stream ID)의 밀리초로 계산한다.
    @Test
    void 마감_시작_후_5분이_되기_전에는_경고하지_않고_5분이_되면_한번_경고한다(CapturedOutput output) {
        givenClosingEventStartedBefore(Duration.ofMinutes(4).plusSeconds(59));
        scheduler.run();
        assertThat(countWarnings(output)).isZero();

        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        scheduler.run();
        assertThat(countWarnings(output)).isEqualTo(1);
        assertThat(output.getAll()).contains("마감 시작 후 5분 경과");
    }

    @Test
    void 같은_5분_구간에서는_틱이_여러번_돌아도_한번만_경고한다(CapturedOutput output) {
        givenClosingEventStartedBefore(Duration.ofMinutes(5));

        runTicksAdvancing(29, Duration.ofSeconds(10));

        assertThat(countWarnings(output)).isEqualTo(1);
    }

    @Test
    void 경고는_5분마다_반복되고_틱_간격이_밀려도_구간을_놓치지_않는다(CapturedOutput output) {
        givenClosingEventStartedBefore(Duration.ofMinutes(4).plusSeconds(55));

        // fixedDelay라 실제 틱 간격은 10초보다 길어질 수 있다. 12초씩 밀려도 5분·10분 구간을 하나씩 남긴다.
        runTicksAdvancing(27, Duration.ofSeconds(12));

        assertThat(countWarnings(output)).isEqualTo(2);
        assertThat(output.getAll()).contains("마감 시작 후 10분 경과");
    }

    /** FR-11b: 30분(180틱)부터는 같은 5분 주기로 WARN 대신 ERROR를 남긴다. */
    @Test
    void 마감_시작_후_30분부터는_ERROR로_올리고_5분마다_반복한다(CapturedOutput output) {
        givenClosingEventStartedBefore(Duration.ofMinutes(29).plusSeconds(59));
        scheduler.run();
        assertThat(output.getAll()).doesNotContain("분 넘게");

        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        scheduler.run();
        assertThat(output.getAll()).contains("30분 넘게");

        when(clock.instant()).thenReturn(NOW.plus(Duration.ofMinutes(5)).plusSeconds(1));
        scheduler.run();
        assertThat(output.getAll()).contains("35분 넘게");
    }

    @Test
    void 재기동해도_경과시간이_이어져_30분이_지났으면_첫_틱에_바로_ERROR를_남긴다(CapturedOutput output) {
        givenClosingEventStartedBefore(Duration.ofMinutes(31));
        EventLifecycleScheduler restarted = new EventLifecycleScheduler(eventRepository, eventCommandService,
                eventClosingService, eventDrainChecker, eventGateLoader, officialSnapshotService, clock);

        restarted.run();

        assertThat(output.getAll()).contains("31분 넘게");
    }

    /** tick 간격이 기본값(10초)이 아니면 임계값도 "틱 수 × 간격"으로 달라진다. */
    @Test
    void ERROR_기준은_틱_수와_실제_tick_간격으로_계산한다(CapturedOutput output) {
        ReflectionTestUtils.setField(scheduler, "lifecycleIntervalMs", 5_000L);
        givenClosingEventStartedBefore(Duration.ofMinutes(15));

        scheduler.run();

        assertThat(output.getAll()).contains("15분 넘게");
    }

    @Test
    void cutoff_형식이_이상하면_경고만_건너뛰고_Drain_판정은_그대로_한다(CapturedOutput output) {
        Event event = org.mockito.Mockito.mock(Event.class);
        when(event.getEventId()).thenReturn(1L);
        when(event.getCutoffStreamId()).thenReturn("not-a-stream-id");
        when(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.CLOSING)).thenReturn(List.of(event));
        when(eventDrainChecker.isDrained(1L, "not-a-stream-id")).thenReturn(false);

        assertThatCode(() -> scheduler.run()).doesNotThrowAnyException();

        verify(eventDrainChecker).isDrained(1L, "not-a-stream-id");
        assertThat(countWarnings(output)).isZero();
        assertThat(output.getAll()).doesNotContain("확인에 실패했습니다");
    }

    /** 마감 시작(cutoff)이 지금보다 elapsed만큼 앞선 CLOSING 이벤트 하나를 준비하고, Drain은 끝나지 않은 상태로 둔다. */
    private void givenClosingEventStartedBefore(Duration elapsed) {
        String cutoffStreamId = NOW.minus(elapsed).toEpochMilli() + "-0";
        Event event = org.mockito.Mockito.mock(Event.class);
        when(event.getEventId()).thenReturn(1L);
        when(event.getCutoffStreamId()).thenReturn(cutoffStreamId);
        when(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.CLOSING)).thenReturn(List.of(event));
        when(eventDrainChecker.isDrained(1L, cutoffStreamId)).thenReturn(false);
        when(clock.instant()).thenReturn(NOW);
    }

    private void runTicksAdvancing(int count, Duration step) {
        Instant now = NOW;
        for (int i = 0; i < count; i++) {
            when(clock.instant()).thenReturn(now);
            scheduler.run();
            now = now.plus(step);
        }
    }

    private static int countWarnings(CapturedOutput output) {
        return output.getAll().split("Drain을 끝내지 못하고", -1).length - 1;
    }

    @Test
    void Snapshot_생성_실패가_이미_완료된_CLOSED_전이를_되돌리지_않는다(CapturedOutput output) {
        Event event = org.mockito.Mockito.mock(Event.class);
        when(event.getEventId()).thenReturn(1L);
        when(event.getCutoffStreamId()).thenReturn("123-0");
        when(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.CLOSING)).thenReturn(List.of(event));
        when(eventDrainChecker.isDrained(1L, "123-0")).thenReturn(true);
        doThrow(new RuntimeException("snapshot failed"))
                .when(officialSnapshotService).createIfAbsent(1L);

        assertThatCode(scheduler::run).doesNotThrowAnyException();

        verify(eventCommandService).completeClosing(1L);
        verify(officialSnapshotService).createIfAbsent(1L);
        assertThat(output.getAll())
                .contains("공식 Snapshot 생성에 실패했습니다")
                .doesNotContain("마감 완료(CLOSING→CLOSED) 확인에 실패했습니다");
    }

    @Test
    void 지정한_Event만_조회해_Drain_완료를_처리한다() {
        Event event = org.mockito.Mockito.mock(Event.class);
        when(event.getEventId()).thenReturn(1L);
        when(event.getStatus()).thenReturn(EventStatus.CLOSING);
        when(event.getCutoffStreamId()).thenReturn("123-0");
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(eventDrainChecker.isDrained(1L, "123-0")).thenReturn(true);

        scheduler.run(1L);

        verify(eventRepository).findById(1L);
        verify(eventCommandService).completeClosing(1L);
        verify(officialSnapshotService).createIfAbsent(1L);
    }
}

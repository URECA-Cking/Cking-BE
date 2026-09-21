package kr.co.cking.event.scheduler;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
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

    @Test
    void Drain이_30틱_연속_끝나지_않으면_그때_한번_경고한다(CapturedOutput output) {
        Event event = org.mockito.Mockito.mock(Event.class);
        when(event.getEventId()).thenReturn(1L);
        when(event.getCutoffStreamId()).thenReturn("123-0");
        when(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.CLOSING)).thenReturn(List.of(event));
        when(eventDrainChecker.isDrained(1L, "123-0")).thenReturn(false);

        for (int i = 0; i < 29; i++) {
            scheduler.run();
        }
        assertThat(output.getAll()).doesNotContain("Drain을 끝내지 못하고");

        scheduler.run();
        assertThat(output.getAll()).contains("Drain을 끝내지 못하고").contains("연속 미완료 틱=30");
    }

    @Test
    void Snapshot_생성_실패가_이미_완료된_CLOSED_전이를_되돌리지_않는다() {
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

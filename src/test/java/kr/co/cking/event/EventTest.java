package kr.co.cking.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventTest {

    private static final Instant START = Instant.parse("2026-09-10T00:00:00Z");
    private static final Instant END = Instant.parse("2026-09-20T00:00:00Z");

    @Test
    void scheduled_이벤트는_시간과_무관하게_UPCOMING이다() {
        Event event = eventOf(EventStatus.SCHEDULED);

        assertThat(event.displayStatus(Instant.parse("2026-09-01T00:00:00Z")))
                .isEqualTo(DisplayStatus.UPCOMING);
    }

    @Test
    void open_이벤트는_종료전이면_IN_PROGRESS이다() {
        Event event = eventOf(EventStatus.OPEN);

        assertThat(event.displayStatus(Instant.parse("2026-09-15T00:00:00Z")))
                .isEqualTo(DisplayStatus.IN_PROGRESS);
    }

    @Test
    void open_이벤트는_종료시각_이후면_CLOSED이다() {
        Event event = eventOf(EventStatus.OPEN);

        assertThat(event.displayStatus(Instant.parse("2026-09-20T00:00:00Z")))
                .isEqualTo(DisplayStatus.CLOSED);
    }

    @Test
    void closing_closed_drawCompleted_published_이벤트는_시간과_무관하게_CLOSED이다() {
        for (EventStatus status : new EventStatus[]{
                EventStatus.CLOSING, EventStatus.CLOSED, EventStatus.DRAW_COMPLETED, EventStatus.PUBLISHED}) {
            Event event = eventOf(status);

            assertThat(event.displayStatus(Instant.parse("2026-09-01T00:00:00Z")))
                    .as("status=%s", status)
                    .isEqualTo(DisplayStatus.CLOSED);
        }
    }

    private static Event eventOf(EventStatus status) {
        return Event.builder()
                .status(status)
                .startAt(START)
                .endAt(END)
                .build();
    }
}

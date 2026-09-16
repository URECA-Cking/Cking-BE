package kr.co.cking.event.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void draft_pendingApproval_rejected_이벤트는_표시상태가_정의되지_않아_예외가_난다() {
        for (EventStatus status : new EventStatus[]{
                EventStatus.DRAFT, EventStatus.PENDING_APPROVAL, EventStatus.REJECTED}) {
            Event event = eventOf(status);

            assertThatThrownBy(() -> event.displayStatus(Instant.parse("2026-09-01T00:00:00Z")))
                    .as("status=%s", status)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void createdAt을_지정하지_않으면_prePersist에서_채워진다() {
        Event event = eventOf(EventStatus.OPEN);

        event.prePersist();

        assertThat(event.getCreatedAt()).isNotNull();
    }

    @Test
    void createdAt을_지정했으면_prePersist가_덮어쓰지_않는다() {
        Instant fixed = Instant.parse("2026-09-01T00:00:00Z");
        Event event = Event.builder()
                .status(EventStatus.OPEN)
                .startAt(START)
                .endAt(END)
                .createdAt(fixed)
                .build();

        event.prePersist();

        assertThat(event.getCreatedAt()).isEqualTo(fixed);
    }

    private static Event eventOf(EventStatus status) {
        return Event.builder()
                .status(status)
                .startAt(START)
                .endAt(END)
                .build();
    }
}

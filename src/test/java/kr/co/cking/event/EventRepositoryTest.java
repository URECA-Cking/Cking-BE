package kr.co.cking.event;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class EventRepositoryTest {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EntityManager entityManager;

    private static final Instant START = Instant.parse("2026-09-10T00:00:00Z");
    private static final Instant END = Instant.parse("2026-09-20T00:00:00Z");

    @Test
    void 삭제되지_않은_이벤트만_조회된다() {
        long memberId = insertMember();
        long creatorId = insertCreator(memberId);
        persistEvent(creatorId, memberId, EventStatus.SCHEDULED, END, null);
        persistEvent(creatorId, memberId, EventStatus.SCHEDULED, END, Instant.parse("2026-09-01T00:00:00Z"));

        var page = eventRepository.findByDeletedAtIsNull(PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getDeletedAt()).isNull();
    }

    @Test
    void displayStatus로_필터링한다() {
        long memberId = insertMember();
        long creatorId = insertCreator(memberId);
        persistEvent(creatorId, memberId, EventStatus.SCHEDULED, END, null);
        persistEvent(creatorId, memberId, EventStatus.OPEN, Instant.parse("2026-09-30T00:00:00Z"), null);
        persistEventBetween(creatorId, memberId, EventStatus.OPEN,
                Instant.parse("2026-08-25T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"));
        persistEvent(creatorId, memberId, EventStatus.PUBLISHED, END, null);
        Instant now = Instant.parse("2026-09-15T00:00:00Z");

        var upcoming = eventRepository.search(null, DisplayStatus.UPCOMING, now, PageRequest.of(0, 10));
        var inProgress = eventRepository.search(null, DisplayStatus.IN_PROGRESS, now, PageRequest.of(0, 10));
        var closed = eventRepository.search(null, DisplayStatus.CLOSED, now, PageRequest.of(0, 10));

        assertThat(upcoming.getContent()).hasSize(1).allMatch(e -> e.getStatus() == EventStatus.SCHEDULED);
        assertThat(inProgress.getContent()).hasSize(1).allMatch(e -> e.getEndAt().isAfter(now));
        assertThat(closed.getContent()).hasSize(2);
    }

    @Test
    void 승인전_거절_이벤트는_필터_여부와_무관하게_제외된다() {
        long memberId = insertMember();
        long creatorId = insertCreator(memberId);
        persistEvent(creatorId, memberId, EventStatus.DRAFT, END, null);
        persistEvent(creatorId, memberId, EventStatus.PENDING_APPROVAL, END, null);
        persistEvent(creatorId, memberId, EventStatus.REJECTED, END, null);
        persistEvent(creatorId, memberId, EventStatus.SCHEDULED, END, null);
        Instant now = Instant.parse("2026-09-15T00:00:00Z");

        var noFilter = eventRepository.search(null, (DisplayStatus) null, now, PageRequest.of(0, 10));

        assertThat(noFilter.getContent()).hasSize(1).allMatch(e -> e.getStatus() == EventStatus.SCHEDULED);
    }

    private void persistEvent(long creatorId, long memberId, EventStatus status, Instant endAt, Instant deletedAt) {
        persistEventBetween(creatorId, memberId, status, START, endAt, deletedAt);
    }

    private void persistEventBetween(long creatorId, long memberId, EventStatus status, Instant startAt, Instant endAt) {
        persistEventBetween(creatorId, memberId, status, startAt, endAt, null);
    }

    private void persistEventBetween(long creatorId, long memberId, EventStatus status, Instant startAt, Instant endAt,
                                      Instant deletedAt) {
        Event event = Event.builder()
                .creatorId(creatorId)
                .requestId(java.util.UUID.randomUUID().toString())
                .title("타이틀")
                .startAt(startAt)
                .endAt(endAt)
                .winnerCount(1)
                .drawMethod("WEIGHTED")
                .status(status)
                .createdBy(memberId)
                .createdAt(Instant.now())
                .build();
        entityManager.persist(event);
        if (deletedAt != null) {
            entityManager.createNativeQuery("UPDATE event SET deleted_at = :deletedAt WHERE event_id = :id")
                    .setParameter("deletedAt", deletedAt)
                    .setParameter("id", event.getEventId())
                    .executeUpdate();
        }
    }

    private long insertMember() {
        entityManager.createNativeQuery(
                        "INSERT INTO member (name, role) VALUES ('테스트유저', 'USER')")
                .executeUpdate();
        Number id = (Number) entityManager.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult();
        return id.longValue();
    }

    private long insertCreator(long memberId) {
        entityManager.createNativeQuery(
                        "INSERT INTO creator (member_id, name) VALUES (:memberId, '테스트크리에이터')")
                .setParameter("memberId", memberId)
                .executeUpdate();
        Number id = (Number) entityManager.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult();
        return id.longValue();
    }
}

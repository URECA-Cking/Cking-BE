package kr.co.cking.event.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;

import kr.co.cking.event.domain.DisplayStatus;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;

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
    private static final Sort EVENT_LIST_SORT = Sort.by(Sort.Direction.DESC, "createdAt", "eventId");

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

        var upcoming = eventRepository.search(creatorId, DisplayStatus.UPCOMING, now, PageRequest.of(0, 10));
        var inProgress = eventRepository.search(creatorId, DisplayStatus.IN_PROGRESS, now, PageRequest.of(0, 10));
        var closed = eventRepository.search(creatorId, DisplayStatus.CLOSED, now, PageRequest.of(0, 10));

        assertThat(upcoming.getContent()).hasSize(1).allMatch(e -> e.getStatus() == EventStatus.SCHEDULED);
        assertThat(inProgress.getContent()).hasSize(1).allMatch(e -> e.getEndAt().isAfter(now));
        assertThat(closed.getContent()).hasSize(2);
    }

    @Test
    void endAt이_지난_OPEN_이벤트만_자동마감_대상으로_조회된다() {
        long memberId = insertMember();
        long creatorId = insertCreator(memberId);
        persistEvent(creatorId, memberId, EventStatus.OPEN, Instant.parse("2026-09-11T00:00:00Z"), null);
        persistEvent(creatorId, memberId, EventStatus.OPEN, Instant.parse("2026-09-30T00:00:00Z"), null);
        persistEvent(creatorId, memberId, EventStatus.CLOSING, Instant.parse("2026-09-11T00:00:00Z"), null);
        Instant now = Instant.parse("2026-09-15T00:00:00Z");

        var overdue = eventRepository.findByStatusAndEndAtLessThanEqual(EventStatus.OPEN, now);

        assertThat(overdue).hasSize(1).allMatch(e -> e.getEndAt().isBefore(now));
    }

    @Test
    void 시작시각이_지났고_종료시각은_남은_SCHEDULED_이벤트만_자동시작_대상으로_조회된다() {
        long memberId = insertMember();
        long creatorId = insertCreator(memberId);
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        persistEventBetween(creatorId, memberId, EventStatus.SCHEDULED,
                Instant.parse("2026-09-14T00:00:00Z"), Instant.parse("2026-09-16T00:00:00Z"));
        persistEventBetween(creatorId, memberId, EventStatus.SCHEDULED,
                Instant.parse("2026-09-16T00:00:00Z"), Instant.parse("2026-09-17T00:00:00Z"));
        persistEventBetween(creatorId, memberId, EventStatus.SCHEDULED,
                Instant.parse("2026-09-13T00:00:00Z"), Instant.parse("2026-09-15T00:00:00Z"));
        persistEventBetween(creatorId, memberId, EventStatus.OPEN,
                Instant.parse("2026-09-14T00:00:00Z"), Instant.parse("2026-09-16T00:00:00Z"));

        var scheduled = eventRepository.findByStatusAndStartAtLessThanEqualAndEndAtGreaterThan(
                EventStatus.SCHEDULED,
                now,
                now
        );

        assertThat(scheduled).hasSize(1)
                .allMatch(event -> event.getStatus() == EventStatus.SCHEDULED
                        && !event.getStartAt().isAfter(now)
                        && event.getEndAt().isAfter(now));
    }

    @Test
    void CLOSING_상태_이벤트만_조회된다() {
        long memberId = insertMember();
        long creatorId = insertCreator(memberId);
        persistEvent(creatorId, memberId, EventStatus.OPEN, END, null);
        persistEvent(creatorId, memberId, EventStatus.CLOSING, END, null);
        persistEvent(creatorId, memberId, EventStatus.CLOSED, END, null);

        var closing = eventRepository.findByStatus(EventStatus.CLOSING);

        assertThat(closing).hasSize(1).allMatch(e -> e.getStatus() == EventStatus.CLOSING);
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

        var noFilter = eventRepository.search(creatorId, (DisplayStatus) null, now, PageRequest.of(0, 10));

        assertThat(noFilter.getContent()).hasSize(1).allMatch(e -> e.getStatus() == EventStatus.SCHEDULED);
    }

    /**
     * creatorId=null인 전체 목록 경로 보호. 로컬 DB에 다른 테스트가 남긴 행이 있어도 깨지지 않도록
     * 개수가 아니라 이 테스트가 만든 행의 포함·제외 여부로 검증한다(서비스와 같은 최신 생성 순 정렬이라 첫 페이지에 온다).
     */
    @Test
    void creatorId가_null이면_전체_크리에이터에서_삭제_및_승인전_이벤트를_제외하고_조회한다() {
        long memberId = insertMember();
        long creatorId = insertCreator(memberId);
        long otherMemberId = insertMember();
        long otherCreatorId = insertCreator(otherMemberId);
        Event visible = persistEvent(creatorId, memberId, EventStatus.SCHEDULED, END, null);
        Event otherVisible = persistEvent(otherCreatorId, memberId, EventStatus.SCHEDULED, END, null);
        Event deleted = persistEvent(creatorId, memberId, EventStatus.SCHEDULED, END,
                Instant.parse("2026-09-01T00:00:00Z"));
        Event draft = persistEvent(creatorId, memberId, EventStatus.DRAFT, END, null);
        entityManager.flush();
        entityManager.clear();
        Instant now = Instant.parse("2026-09-15T00:00:00Z");

        var ids = eventRepository.search(null, (DisplayStatus) null, now, PageRequest.of(0, 50, EVENT_LIST_SORT)).getContent()
                .stream().map(Event::getEventId).toList();

        assertThat(ids).contains(visible.getEventId(), otherVisible.getEventId())
                .doesNotContain(deleted.getEventId(), draft.getEventId());
    }

    private Event persistEvent(long creatorId, long memberId, EventStatus status, Instant endAt, Instant deletedAt) {
        return persistEventBetween(creatorId, memberId, status, START, endAt, deletedAt);
    }

    private void persistEventBetween(long creatorId, long memberId, EventStatus status, Instant startAt, Instant endAt) {
        persistEventBetween(creatorId, memberId, status, startAt, endAt, null);
    }

    private Event persistEventBetween(long creatorId, long memberId, EventStatus status, Instant startAt, Instant endAt,
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
        return event;
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

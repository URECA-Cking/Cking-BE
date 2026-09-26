package kr.co.cking.event.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class EventEntryRepositoryJpaTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EventEntryRepository entryRepository;

    @Test
    void 회원과_event별로_appliedAt과_entryId_역순_cursor조회한다() {
        long memberId = insertMember("응모조회사용자");
        long otherMemberId = insertMember("다른사용자");
        long creatorId = insertCreator(memberId);
        long eventId = insertEvent(creatorId, memberId, "조회 이벤트");
        long otherEventId = insertEvent(creatorId, memberId, "다른 이벤트");

        long oldestId = insertEntry(memberId, eventId, 1L, "2026-09-18 01:00:00.000000");
        long sameTimeLowerId = insertEntry(memberId, eventId, 2L, "2026-09-18 02:00:00.000000");
        long sameTimeHigherId = insertEntry(memberId, eventId, 3L, "2026-09-18 02:00:00.000000");
        insertEntry(otherMemberId, eventId, 9L, "2026-09-18 03:00:00.000000");
        insertEntry(memberId, otherEventId, 8L, "2026-09-18 03:00:00.000000");

        List<EventEntryView> first =
                entryRepository.findFirstPageView(memberId, eventId, PageRequest.of(0, 2));

        assertThat(first).extracting(EventEntryView::getEntryId)
                .containsExactly(sameTimeHigherId, sameTimeLowerId);
        assertThat(first).extracting(EventEntryView::getUsedTicketCount)
                .containsExactly(3L, 2L);

        List<EventEntryView> next = entryRepository.findAfterCursorView(
                memberId,
                eventId,
                first.get(1).getAppliedAt(),
                first.get(1).getEntryId(),
                PageRequest.of(0, 2)
        );

        assertThat(next).extracting(EventEntryView::getEntryId).containsExactly(oldestId);
    }

    // 응모권 종류는 event_entry에 없고, SPEND Ledger가 공용 Ledger에 있는지로 판별한다.
    @Test
    void 공용_Ledger에_SPEND가_있는_응모만_common으로_조회한다() {
        long memberId = insertMember("종류조회사용자");
        long creatorId = insertCreator(memberId);
        long eventId = insertEvent(creatorId, memberId, "종류 이벤트");
        long creatorEntryId = insertEntry(memberId, eventId, 1L, "2026-09-18 01:00:00.000000");
        long commonEntryId = insertEntry(memberId, eventId, 2L, "2026-09-18 02:00:00.000000");
        entityManager.createNativeQuery("""
                        INSERT INTO common_ticket_ledger (member_id, event_entry_id, delta_amount, type, request_id)
                        VALUES (:memberId, :entryId, -2, 'SPEND', :requestId)
                        """)
                .setParameter("memberId", memberId)
                .setParameter("entryId", commonEntryId)
                .setParameter("requestId", UUID.randomUUID().toString())
                .executeUpdate();

        List<EventEntryView> views = entryRepository.findFirstPageView(memberId, eventId, PageRequest.of(0, 10));

        assertThat(views).extracting(EventEntryView::getEntryId).containsExactly(commonEntryId, creatorEntryId);
        assertThat(views).extracting(EventEntryView::getCommon).containsExactly(true, false);
    }

    private long insertMember(String name) {
        entityManager.createNativeQuery("INSERT INTO member (name, role) VALUES (:name, 'USER')")
                .setParameter("name", name)
                .executeUpdate();
        return lastInsertId();
    }

    private long insertCreator(long memberId) {
        entityManager.createNativeQuery("INSERT INTO creator (member_id, name) VALUES (:memberId, '응모조회크리에이터')")
                .setParameter("memberId", memberId)
                .executeUpdate();
        return lastInsertId();
    }

    private long insertEvent(long creatorId, long memberId, String title) {
        entityManager.createNativeQuery("""
                        INSERT INTO event
                            (creator_id, request_id, title, start_at, end_at, winner_count, draw_method, status, created_by)
                        VALUES
                            (:creatorId, :requestId, :title, '2026-09-18 00:00:00', '2026-09-19 00:00:00',
                             1, 'WEIGHTED', 'OPEN', :memberId)
                        """)
                .setParameter("creatorId", creatorId)
                .setParameter("requestId", UUID.randomUUID().toString())
                .setParameter("title", title)
                .setParameter("memberId", memberId)
                .executeUpdate();
        return lastInsertId();
    }

    private long insertEntry(long memberId, long eventId, long usedTicketCount, String appliedAt) {
        entityManager.createNativeQuery("""
                        INSERT INTO event_entry
                            (member_id, event_id, request_id, used_ticket_count, applied_at)
                        VALUES (:memberId, :eventId, :requestId, :usedTicketCount, :appliedAt)
                        """)
                .setParameter("memberId", memberId)
                .setParameter("eventId", eventId)
                .setParameter("requestId", UUID.randomUUID().toString())
                .setParameter("usedTicketCount", usedTicketCount)
                .setParameter("appliedAt", appliedAt)
                .executeUpdate();
        return lastInsertId();
    }

    private long lastInsertId() {
        return ((Number) entityManager.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }
}

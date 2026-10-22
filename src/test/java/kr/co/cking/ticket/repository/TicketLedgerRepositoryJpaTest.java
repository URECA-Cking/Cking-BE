package kr.co.cking.ticket.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class TicketLedgerRepositoryJpaTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TicketLedgerRepository ledgerRepository;

    @Test
    void member_creator별_ledger를_최신순으로_조회한다() {
        long memberId = insertMember();
        long creatorId = insertCreator(memberId);
        insertLedger(memberId, creatorId, 1L, "2026-09-16 02:00:00.000000");
        insertLedger(memberId, creatorId, 2L, "2026-09-16 01:00:00.000000");
        insertLedger(memberId, creatorId, 3L, "2026-09-16 00:00:00.000000");

        List<TicketLedgerView> result = ledgerRepository.findFirstPageView(memberId, creatorId, PageRequest.of(0, 2));

        assertThat(result).extracting(TicketLedgerView::getDeltaAmount).containsExactly(1L, 2L);
    }

    @Test
    void 소프트삭제된_이벤트와_종료된_미션도_이름을_내려준다() {
        long memberId = insertMember();
        long creatorId = insertCreator(memberId);
        long eventId = exec("""
                INSERT INTO event (creator_id, request_id, title, start_at, end_at, winner_count, draw_method, status, created_by, deleted_at)
                VALUES (:c, UUID(), '삭제된 이벤트', '2026-09-18 00:00:00', '2026-09-19 00:00:00', 1, 'WEIGHTED', 'CLOSED', :m, NOW())
                """, java.util.Map.of("c", creatorId, "m", memberId));
        long entryId = exec("INSERT INTO event_entry (member_id, event_id, request_id, used_ticket_count, applied_at) VALUES (:m, :e, UUID(), 1, NOW(6))", java.util.Map.of("m", memberId, "e", eventId));
        long missionId = exec("INSERT INTO mission (creator_id, type, reward_amount, active_to) VALUES (:c, 'YOUTUBE_SUBSCRIPTION', 1, '2020-01-01 00:00:00')", java.util.Map.of("c", creatorId));
        entityManager.createNativeQuery("""
                INSERT INTO mission_completion (member_id, creator_id, mission_id, period_key, completion_key, request_id, payload_fingerprint, completed_at)
                VALUES (:m, :c, :mi, '2026-09-16', '2026-09-16', UUID(), REPEAT('a', 64), NOW(6))
                """).setParameter("m", memberId).setParameter("c", creatorId).setParameter("mi", missionId).executeUpdate();
        long completionId = ((Number) entityManager.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        entityManager.createNativeQuery("""
                INSERT INTO ticket_ledger (member_id, creator_id, event_entry_id, delta_amount, type, request_id, created_at)
                VALUES (:m, :c, :e, -1, 'SPEND', UUID(), '2026-09-16 02:00:00.000000')
                """).setParameter("m", memberId).setParameter("c", creatorId).setParameter("e", entryId).executeUpdate();
        entityManager.createNativeQuery("""
                INSERT INTO ticket_ledger (member_id, creator_id, mission_completion_id, delta_amount, type, request_id, created_at)
                VALUES (:m, :c, :mc, 1, 'EARN', UUID(), '2026-09-16 01:00:00.000000')
                """).setParameter("m", memberId).setParameter("c", creatorId).setParameter("mc", completionId).executeUpdate();

        List<TicketLedgerView> result = ledgerRepository.findFirstPageView(memberId, creatorId, PageRequest.of(0, 10));

        assertThat(result).extracting(TicketLedgerView::getEventTitle).containsExactly("삭제된 이벤트", null);
        assertThat(result).extracting(TicketLedgerView::getMissionType).containsExactly(null, "YOUTUBE_SUBSCRIPTION");
    }

    private long exec(String sql, java.util.Map<String, Object> params) {
        var q = entityManager.createNativeQuery(sql);
        params.forEach(q::setParameter);
        q.executeUpdate();
        return ((Number) entityManager.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private long insertMember() {
        entityManager.createNativeQuery("INSERT INTO member (name, role) VALUES ('티켓테스트', 'USER')")
                .executeUpdate();
        return ((Number) entityManager.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private long insertCreator(long memberId) {
        entityManager.createNativeQuery("INSERT INTO creator (member_id, name, activity_name, activity_name_normalized, activity_name_configured) VALUES (:memberId, '티켓크리에이터', (@creator_fixture_activity_name := CONCAT('c', SUBSTRING(MD5(UUID()), 1, 19))), @creator_fixture_activity_name, true)")
                .setParameter("memberId", memberId)
                .executeUpdate();
        return ((Number) entityManager.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private void insertLedger(long memberId, long creatorId, long deltaAmount, String createdAt) {
        entityManager.createNativeQuery("""
                        INSERT INTO ticket_ledger
                            (member_id, creator_id, delta_amount, type, reason, created_at)
                        VALUES (:memberId, :creatorId, :deltaAmount, 'COMPENSATE', '테스트', :createdAt)
                        """)
                .setParameter("memberId", memberId)
                .setParameter("creatorId", creatorId)
                .setParameter("deltaAmount", deltaAmount)
                .setParameter("createdAt", createdAt)
                .executeUpdate();
    }
}

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

    private long insertMember() {
        entityManager.createNativeQuery("INSERT INTO member (name, role) VALUES ('티켓테스트', 'USER')")
                .executeUpdate();
        return ((Number) entityManager.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private long insertCreator(long memberId) {
        entityManager.createNativeQuery("INSERT INTO creator (member_id, name) VALUES (:memberId, '티켓크리에이터')")
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

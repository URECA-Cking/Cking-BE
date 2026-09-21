package kr.co.cking.ticket.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;

import kr.co.cking.ticket.domain.UserTicketBalance;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class UserTicketBalanceRepositoryJpaTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserTicketBalanceRepository repository;

    @Test
    void 잔액을_memberId_creatorId_순으로_나눠_조회한다() {
        long creator1 = insertCreator(insertMember());
        long member2 = insertMember();
        long member3 = insertMember();
        insertBalance(member3, creator1);
        insertBalance(member2, creator1);
        entityManager.flush();
        entityManager.clear();
        // 공유 로컬 DB에 다른 행이 있을 수 있어, 1건씩 끝까지 순회해 방문 순서만 확인한다.
        List<Long> visited = new ArrayList<>();
        Pageable page = PageRequest.of(0, 1, Sort.by("id.memberId", "id.creatorId"));
        Slice<UserTicketBalance> slice;
        do {
            slice = repository.findAllBy(page);
            slice.forEach(balance -> visited.add(balance.getMemberId()));
            page = slice.nextPageable();
        } while (slice.hasNext());

        assertThat(visited).contains(member2, member3).isSorted();
        assertThat(visited.indexOf(member2)).isLessThan(visited.indexOf(member3));
    }

    private long insertMember() {
        entityManager.createNativeQuery("INSERT INTO member (name, role) VALUES ('잔액테스트', 'USER')")
                .executeUpdate();
        return ((Number) entityManager.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private long insertCreator(long memberId) {
        entityManager.createNativeQuery("INSERT INTO creator (member_id, name) VALUES (:memberId, '잔액크리에이터')")
                .setParameter("memberId", memberId)
                .executeUpdate();
        return ((Number) entityManager.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private void insertBalance(long memberId, long creatorId) {
        entityManager.createNativeQuery(
                        "INSERT INTO user_ticket_balance (member_id, creator_id, balance) VALUES (:m, :c, 1)")
                .setParameter("m", memberId)
                .setParameter("c", creatorId)
                .executeUpdate();
    }
}

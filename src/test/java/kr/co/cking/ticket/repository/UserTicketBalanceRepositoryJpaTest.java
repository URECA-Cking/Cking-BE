package kr.co.cking.ticket.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import java.util.List;

import org.springframework.data.domain.PageRequest;

import kr.co.cking.ticket.domain.UserTicketBalance;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class UserTicketBalanceRepositoryJpaTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserTicketBalanceRepository repository;

    @Test
    void 마지막_키보다_큰_행만_키_순서대로_조회한다() {
        long creator = insertCreator(insertMember());
        long member1 = insertMember();
        long member2 = insertMember();
        long member3 = insertMember();
        insertBalance(member3, creator);
        insertBalance(member1, creator);
        insertBalance(member2, creator);
        entityManager.flush();
        entityManager.clear();

        // 공유 로컬 DB에 다른 행이 있을 수 있어, member1 이후 구간에서 이 테스트의 행만 걸러 확인한다.
        List<UserTicketBalance> after = repository.findNextBatch(member1, creator, PageRequest.of(0, 1_000));

        assertThat(after).extracting(UserTicketBalance::getMemberId)
                .filteredOn(id -> id == member2 || id == member3)
                .containsExactly(member2, member3);
        assertThat(after).extracting(UserTicketBalance::getMemberId).doesNotContain(member1);
    }

    @Test
    void 조회_도중_앞선_키가_삽입돼도_이미_읽은_행을_다시_읽지_않는다() {
        long creator = insertCreator(insertMember());
        long member1 = insertMember();
        long member2 = insertMember();
        long member3 = insertMember();
        insertBalance(member2, creator);
        insertBalance(member3, creator);
        entityManager.flush();
        entityManager.clear();

        // (member2, creator)까지 읽은 뒤 더 앞선 키(member1)가 삽입된 상황. offset이면 member2가 다시 읽힌다.
        insertBalance(member1, creator);
        entityManager.flush();
        entityManager.clear();
        List<UserTicketBalance> next = repository.findNextBatch(member2, creator, PageRequest.of(0, 1_000));

        assertThat(next).extracting(UserTicketBalance::getMemberId)
                .doesNotContain(member1, member2)
                .contains(member3);
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

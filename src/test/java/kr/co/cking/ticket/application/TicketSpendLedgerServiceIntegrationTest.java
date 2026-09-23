package kr.co.cking.ticket.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.co.cking.event.domain.EventEntry;
import kr.co.cking.event.repository.EventEntryRepository;
import kr.co.cking.ticket.application.dto.SpendCommand;
import kr.co.cking.ticket.domain.CommonTicketLedger;
import kr.co.cking.ticket.domain.CouponType;
import kr.co.cking.ticket.domain.TicketLedger;
import kr.co.cking.ticket.domain.TicketLedgerType;
import kr.co.cking.ticket.domain.UserCommonTicketBalance;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.CommonTicketLedgerRepository;
import kr.co.cking.ticket.repository.TicketLedgerRepository;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;

/**
 * 완료조건(이슈 #62): event_entry·ticket_ledger·user_ticket_balance 반영과 at-least-once
 * 재전달에 대한 멱등성, creatorId 교차검증을 검증한다.
 *
 * <p>member_id/creator_id/event_id는 명시적으로 고정 ID를 지정해서 INSERT한다 —
 * TicketEarnLedgerServiceIntegrationTest와 동일한 이유(로컬 DB member 테이블에
 * AUTO_INCREMENT 없음).
 */
@SpringBootTest
class TicketSpendLedgerServiceIntegrationTest {

    private static final long OWNER_MEMBER_ID = 96001L;
    private static final long OTHER_OWNER_MEMBER_ID = 96003L;
    private static final long MEMBER_ID = 96002L;
    private static final long CREATOR_ID = 96101L;
    private static final long OTHER_CREATOR_ID = 96102L;
    private static final long EVENT_ID_SEQ_START = 96201L;
    private static final String TEST_COMMON_MISSION_TYPE = "TEST_247_ISOLATION";

    @Autowired
    private TicketSpendLedgerService ticketSpendLedgerService;

    @Autowired
    private EventEntryRepository eventEntryRepository;

    @Autowired
    private TicketLedgerRepository ticketLedgerRepository;

    @Autowired
    private UserTicketBalanceRepository userTicketBalanceRepository;

    @Autowired
    private CommonTicketLedgerRepository commonTicketLedgerRepository;

    @Autowired
    private UserCommonTicketBalanceRepository userCommonTicketBalanceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long eventId;
    private long otherCreatorEventId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                OWNER_MEMBER_ID, "크리에이터 회원", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                OTHER_OWNER_MEMBER_ID, "다른 크리에이터 회원", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                MEMBER_ID, "응모 대상 회원", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, OWNER_MEMBER_ID, "테스트 크리에이터");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                OTHER_CREATOR_ID, OTHER_OWNER_MEMBER_ID, "다른 테스트 크리에이터");

        eventId = insertEvent(CREATOR_ID, OWNER_MEMBER_ID);
        otherCreatorEventId = insertEvent(OTHER_CREATOR_ID, OTHER_OWNER_MEMBER_ID);
        userTicketBalanceRepository.save(
                UserTicketBalance.builder().memberId(MEMBER_ID).creatorId(CREATOR_ID).balance(100L)
                        .updatedAt(java.time.Instant.now()).build());
        // 크리에이터 잔액(100)과 값을 다르게 둬서, COMMON 반영이 실제로 공용 잔액을
        // 건드리고 크리에이터 잔액과는 무관하다는 걸 값 자체로 구분할 수 있게 한다.
        userCommonTicketBalanceRepository.save(
                UserCommonTicketBalance.builder().memberId(MEMBER_ID).balance(50L)
                        .updatedAt(java.time.Instant.now()).build());
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private long insertEvent(long creatorId, long createdBy) {
        String requestId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO event (creator_id, title, start_at, end_at, winner_count, draw_method,
                                    status, request_id, created_by)
                VALUES (?, ?, '2026-09-01 00:00:00', '2026-12-01 00:00:00', 1, 'WEIGHTED', 'OPEN', ?, ?)
                """, creatorId, "테스트 이벤트", requestId, createdBy);
        return jdbcTemplate.queryForObject(
                "SELECT event_id FROM event WHERE request_id = ?", Long.class, requestId);
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM ticket_ledger WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM common_ticket_ledger WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM common_mission_completion WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM common_mission WHERE type = ?", TEST_COMMON_MISSION_TYPE);
        jdbcTemplate.update("DELETE FROM event_entry WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ? AND creator_id IN (?, ?)",
                MEMBER_ID, CREATOR_ID, OTHER_CREATOR_ID);
        jdbcTemplate.update("DELETE FROM user_common_ticket_balance WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM event WHERE creator_id IN (?, ?)", CREATOR_ID, OTHER_CREATOR_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id IN (?, ?)", CREATOR_ID, OTHER_CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?, ?)",
                MEMBER_ID, OWNER_MEMBER_ID, OTHER_OWNER_MEMBER_ID);
    }

    private SpendCommand command(String requestId, long ticketCount) {
        return new SpendCommand(eventId, MEMBER_ID, CREATOR_ID, requestId, ticketCount, CouponType.CREATOR);
    }

    // couponType=COMMON이어도 creatorId 필드는 여전히 이벤트에서 resolve된 값 그대로 싣는다
    // (교차검증용 - 이슈 #243). 어느 잔액을 차감할지는 couponType이 정한다.
    private SpendCommand commonCommand(String requestId, long ticketCount) {
        return new SpendCommand(eventId, MEMBER_ID, CREATOR_ID, requestId, ticketCount, CouponType.COMMON);
    }

    @Test
    void COMMON_요청은_공용_잔액과_공용_Ledger에_반영되고_크리에이터_잔액은_그대로다() {
        String requestId = UUID.randomUUID().toString();

        ticketSpendLedgerService.apply(commonCommand(requestId, 3L));

        EventEntry entry = eventEntryRepository.findByRequestId(requestId).orElseThrow();
        assertThat(entry.getUsedTicketCount()).isEqualTo(3L);

        CommonTicketLedger ledger = commonTicketLedgerRepository.findByRequestId(requestId).orElseThrow();
        assertThat(ledger.getType()).isEqualTo(TicketLedgerType.SPEND);
        assertThat(ledger.getEventEntryId()).isEqualTo(entry.getEntryId());
        assertThat(ledger.getDeltaAmount()).isEqualTo(-3L);
        assertThat(ledger.getBalanceBefore()).isEqualTo(50L);
        assertThat(ledger.getBalanceAfter()).isEqualTo(47L);
        // 크리에이터 전용 Ledger에는 아무것도 남지 않는다.
        assertThat(ticketLedgerRepository.findByRequestId(requestId)).isEmpty();

        UserCommonTicketBalance commonBalance =
                userCommonTicketBalanceRepository.findById(MEMBER_ID).orElseThrow();
        assertThat(commonBalance.getBalance()).isEqualTo(47L);
        // 크리에이터 잔액(100)은 COMMON 차감의 영향을 받지 않는다.
        UserTicketBalance creatorBalance =
                userTicketBalanceRepository.findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID).orElseThrow();
        assertThat(creatorBalance.getBalance()).isEqualTo(100L);
    }

    @Test
    void 공용_잔액이_없으면_COMMON_요청을_반영하지_않는다() {
        userCommonTicketBalanceRepository.deleteById(MEMBER_ID);
        String requestId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> ticketSpendLedgerService.apply(commonCommand(requestId, 3L)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(eventEntryRepository.findByRequestId(requestId)).isEmpty();
    }

    @Test
    void Entry_Ledger_Balance를_한_트랜잭션으로_반영한다() {
        String requestId = UUID.randomUUID().toString();

        ticketSpendLedgerService.apply(command(requestId, 3L));

        EventEntry entry = eventEntryRepository.findByRequestId(requestId).orElseThrow();
        assertThat(entry.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(entry.getEventId()).isEqualTo(eventId);
        assertThat(entry.getUsedTicketCount()).isEqualTo(3L);

        TicketLedger ledger = ticketLedgerRepository.findByRequestId(requestId).orElseThrow();
        assertThat(ledger.getType()).isEqualTo(TicketLedgerType.SPEND);
        assertThat(ledger.getEventEntryId()).isEqualTo(entry.getEntryId());
        assertThat(ledger.getMissionCompletionId()).isNull();
        assertThat(ledger.getDeltaAmount()).isEqualTo(-3L);
        assertThat(ledger.getBalanceBefore()).isEqualTo(100L);
        assertThat(ledger.getBalanceAfter()).isEqualTo(97L);

        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID).orElseThrow();
        assertThat(balance.getBalance()).isEqualTo(97L);
    }

    @Test
    void 같은_requestId를_재처리해도_잔액이_중복차감되지_않는다() {
        String requestId = UUID.randomUUID().toString();
        SpendCommand command = command(requestId, 4L);

        ticketSpendLedgerService.apply(command);
        ticketSpendLedgerService.apply(command); // at-least-once 재전달 시뮬레이션

        Integer entryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_entry WHERE member_id = ?", Integer.class, MEMBER_ID);
        Integer ledgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_ledger WHERE member_id = ?", Integer.class, MEMBER_ID);
        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID).orElseThrow();

        assertThat(entryCount).isEqualTo(1);
        assertThat(ledgerCount).isEqualTo(1);
        assertThat(balance.getBalance()).isEqualTo(96L);
    }

    @Test
    void 같은_requestId에_다른_ticketCount가_들어오면_반영하지_않는다() {
        String requestId = UUID.randomUUID().toString();
        ticketSpendLedgerService.apply(command(requestId, 4L));

        SpendCommand conflicting = command(requestId, 999L);

        assertThatThrownBy(() -> ticketSpendLedgerService.apply(conflicting))
                .isInstanceOf(IllegalStateException.class);

        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID).orElseThrow();
        // 최초 반영분(4)만 남아있고 충돌한 재요청(999)은 반영되지 않아야 한다.
        assertThat(balance.getBalance()).isEqualTo(96L);
    }

    // 이슈 #243: EventEntry에 couponType 컬럼이 없어 Ledger 존재로 역산해서 검증한다.
    @Test
    void 같은_requestId에_couponType만_달라도_반영하지_않는다() {
        String requestId = UUID.randomUUID().toString();
        ticketSpendLedgerService.apply(command(requestId, 4L)); // CREATOR로 최초 반영

        SpendCommand conflicting = commonCommand(requestId, 4L); // 같은 requestId, COMMON로 재전달

        assertThatThrownBy(() -> ticketSpendLedgerService.apply(conflicting))
                .isInstanceOf(IllegalStateException.class);

        // 최초 CREATOR 반영만 남아있고 COMMON Ledger는 생기지 않아야 한다.
        assertThat(commonTicketLedgerRepository.findByRequestId(requestId)).isEmpty();
        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID).orElseThrow();
        assertThat(balance.getBalance()).isEqualTo(96L);
    }

    // PR #247 리뷰(자비): requestId는 클라이언트가 API마다 독립적으로 생성하는 값이라
    // common_ticket_ledger 안에서만 유일하다. 공용 미션 EARN이 쓴 requestId를 CREATOR
    // 응모가 재사용하면(우연이든 버그든), requestId 기준 couponType 역산은 그 EARN 행을
    // COMMON으로 잘못 판정해서 정상 재전달까지 예외로 깨뜨릴 수 있었다 - eventEntryId
    // 기준으로 고친 뒤에는 EARN 행과 무관하게 정상 재전달이 통과해야 한다.
    @Test
    void 공용_미션_EARN과_같은_requestId를_쓴_CREATOR_응모_재전달은_EARN_행과_무관하게_정상_처리된다() {
        String sharedRequestId = UUID.randomUUID().toString();
        seedCommonMissionEarnLedger(sharedRequestId);

        SpendCommand command = command(sharedRequestId, 4L);
        ticketSpendLedgerService.apply(command); // 최초 반영
        ticketSpendLedgerService.apply(command); // at-least-once 재전달 - 예외 없이 통과해야 한다

        Integer entryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_entry WHERE member_id = ? AND request_id = ?",
                Integer.class, MEMBER_ID, sharedRequestId);
        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID).orElseThrow();

        assertThat(entryCount).isEqualTo(1);
        assertThat(balance.getBalance()).isEqualTo(96L); // 재전달로 중복 차감되지 않아야 한다
    }

    // sharedRequestId로 공용 미션 EARN이 이미 반영된 상태를 재현한다. common_mission ->
    // common_mission_completion -> common_ticket_ledger(EARN) 순으로 FK를 채워야 한다.
    private void seedCommonMissionEarnLedger(String sharedRequestId) {
        jdbcTemplate.update(
                "INSERT INTO common_mission (type, reward_amount, active_from, active_to) VALUES (?, 1, NULL, NULL)",
                TEST_COMMON_MISSION_TYPE);
        Long missionId = jdbcTemplate.queryForObject(
                "SELECT mission_id FROM common_mission WHERE type = ?", Long.class, TEST_COMMON_MISSION_TYPE);

        String fingerprint = "a".repeat(64);
        jdbcTemplate.update("""
                INSERT INTO common_mission_completion
                    (member_id, mission_id, period_key, request_id, payload_fingerprint, completed_at)
                VALUES (?, ?, '2026-09-23', ?, ?, NOW())
                """, MEMBER_ID, missionId, sharedRequestId, fingerprint);
        Long completionId = jdbcTemplate.queryForObject(
                "SELECT completion_id FROM common_mission_completion WHERE request_id = ?",
                Long.class, sharedRequestId);

        jdbcTemplate.update("""
                INSERT INTO common_ticket_ledger
                    (member_id, event_entry_id, mission_completion_id, delta_amount, type, request_id,
                     balance_before, balance_after, created_at)
                VALUES (?, NULL, ?, 1, 'EARN', ?, 0, 1, NOW())
                """, MEMBER_ID, completionId, sharedRequestId);
    }

    @Test
    void 같은_requestId에_creatorId만_달라도_반영하지_않는다() {
        String requestId = UUID.randomUUID().toString();
        ticketSpendLedgerService.apply(command(requestId, 4L));

        SpendCommand conflicting = new SpendCommand(eventId, MEMBER_ID, OTHER_CREATOR_ID, requestId, 4L, CouponType.CREATOR);

        assertThatThrownBy(() -> ticketSpendLedgerService.apply(conflicting))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void creatorId가_Event의_creatorId와_다르면_반영하지_않는다() {
        SpendCommand command = new SpendCommand(
                otherCreatorEventId, MEMBER_ID, CREATOR_ID, UUID.randomUUID().toString(), 3L, CouponType.CREATOR);

        assertThatThrownBy(() -> ticketSpendLedgerService.apply(command))
                .isInstanceOf(IllegalStateException.class);

        assertThat(eventEntryRepository.findByRequestId(command.requestId())).isEmpty();
    }

    @Test
    void 동시에_같은_requestId가_재전달돼도_한_번만_반영된다() throws InterruptedException {
        String requestId = UUID.randomUUID().toString();
        SpendCommand command = command(requestId, 4L);

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    try {
                        ticketSpendLedgerService.apply(command);
                        return true;
                    } catch (DataAccessException e) {
                        return false;
                    }
                }));
            }

            startLatch.countDown();

            long succeeded = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isGreaterThanOrEqualTo(1L);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        } finally {
            executor.shutdown();
        }

        Integer entryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_entry WHERE member_id = ?", Integer.class, MEMBER_ID);
        Integer ledgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_ledger WHERE member_id = ?", Integer.class, MEMBER_ID);
        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID).orElseThrow();

        assertThat(entryCount).isEqualTo(1);
        assertThat(ledgerCount).isEqualTo(1);
        assertThat(balance.getBalance()).isEqualTo(96L);
    }
}

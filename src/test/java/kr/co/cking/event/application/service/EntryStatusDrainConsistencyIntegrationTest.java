package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.ticket.domain.CouponType;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventEntryAggregate;
import kr.co.cking.event.repository.EventEntryRepository;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.event.scheduler.EventLifecycleScheduler;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.snapshot.application.OfficialSnapshotService;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;

/**
 * 이슈 #231 완료 조건: "Drain 완료 후 Redis 집계 == DB 집계"를 실제 Gate 적재 →
 * SPEND(entry-spend.lua) → 실제 SPEND Consumer 반영 → 마감·Drain → CLOSED 전 구간을
 * 거쳐 검증한다. 전용 stream key·consumer group으로 프로덕션 Consumer Group과 격리하고,
 * 스케줄러 자동 실행은 꺼서 {@link EventLifecycleScheduler#run(Long)}으로 직접 진행시킨다.
 */
@SpringBootTest(properties = {
        "cking.entry.stream-key=stream:ticket-deducted:drain-consistency-test",
        "cking.entry.history-consumer-group=cg:ticket-history:drain-consistency-test",
        "cking.scheduling.enabled=false"
})
class EntryStatusDrainConsistencyIntegrationTest {

    private static final long FAR_FUTURE_MILLIS = 9_999_999_999_999L;

    @Autowired
    private EntrySpendService entrySpendService;

    @Autowired
    private EventGateLoader eventGateLoader;

    @Autowired
    private EventClosingService eventClosingService;

    @Autowired
    private EventLifecycleScheduler eventLifecycleScheduler;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventEntryRepository eventEntryRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserTicketBalanceRepository userTicketBalanceRepository;

    @MockitoBean
    private OfficialSnapshotService officialSnapshotService;

    private Long ownerMemberId;
    private Long creatorId;
    private Long eventId;
    private Long userAId;
    private Long userBId;
    private final List<String> requestIds = new ArrayList<>();

    @Test
    void Drain_완료_후_Redis_집계가_DB_집계와_일치한다() throws InterruptedException {
        setUpEventWithGate();
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(creatorId, userAId), "10");
        redisTemplate.opsForValue().set(EntryRedisKeys.balance(creatorId, userBId), "10");
        // SPEND Consumer는 DB user_ticket_balance를 직접 갱신하므로(Redis와 별개 Tx),
        // 미리 행을 만들어 둬야 한다 - 없으면 컨슈머가 실패해 PEL에 남고 Drain이 끝나지 않는다.
        userTicketBalanceRepository.saveAndFlush(
                UserTicketBalance.builder().memberId(userAId).creatorId(creatorId).balance(10L).updatedAt(Instant.now()).build());
        userTicketBalanceRepository.saveAndFlush(
                UserTicketBalance.builder().memberId(userBId).creatorId(creatorId).balance(10L).updatedAt(Instant.now()).build());

        assertSpendSucceeds(userAId, 3);
        assertSpendSucceeds(userAId, 2);
        assertSpendSucceeds(userBId, 4);

        eventClosingService.startClosing(eventId);
        awaitClosed();

        long dbTotal = eventEntryRepository.aggregateByEvent(eventId).stream()
                .mapToLong(EventEntryAggregate::getTicketCount).sum();
        long dbUserA = ticketCountOf(userAId);
        long dbUserB = ticketCountOf(userBId);

        String redisTotal = redisTemplate.opsForValue().get(EntryRedisKeys.entryTotal(eventId));
        String redisUserA = (String) redisTemplate.opsForHash()
                .get(EntryRedisKeys.entrants(eventId), String.valueOf(userAId));
        String redisUserB = (String) redisTemplate.opsForHash()
                .get(EntryRedisKeys.entrants(eventId), String.valueOf(userBId));

        assertThat(dbTotal).isEqualTo(9L);
        assertThat(redisTotal).isEqualTo(String.valueOf(dbTotal));
        assertThat(redisUserA).isEqualTo(String.valueOf(dbUserA));
        assertThat(redisUserB).isEqualTo(String.valueOf(dbUserB));
    }

    private long ticketCountOf(Long memberId) {
        return eventEntryRepository.aggregateByEvent(eventId).stream()
                .filter(aggregate -> aggregate.getMemberId().equals(memberId))
                .mapToLong(EventEntryAggregate::getTicketCount)
                .findFirst()
                .orElse(0L);
    }

    private void assertSpendSucceeds(Long userId, int ticketCount) {
        String requestId = UUID.randomUUID().toString();
        requestIds.add(requestId);
        var result = entrySpendService.spend(eventId, userId, creatorId, requestId, ticketCount, CouponType.CREATOR);
        assertThat(result.code().name()).isEqualTo("SUCCESS");
    }

    private void setUpEventWithGate() {
        Member owner = memberRepository.saveAndFlush(new Member("Drain 정합성 크리에이터 회원", null, null, MemberRole.USER));
        ownerMemberId = owner.getMemberId();
        Creator creator = creatorRepository.saveAndFlush(new Creator(ownerMemberId, "Drain 정합성 크리에이터"));
        creatorId = creator.getCreatorId();
        Member userA = memberRepository.saveAndFlush(new Member("Drain 정합성 응모자A", null, null, MemberRole.USER));
        Member userB = memberRepository.saveAndFlush(new Member("Drain 정합성 응모자B", null, null, MemberRole.USER));
        userAId = userA.getMemberId();
        userBId = userB.getMemberId();

        Event event = eventRepository.saveAndFlush(Event.builder()
                .creatorId(creatorId)
                .requestId(UUID.randomUUID().toString())
                .title("Drain 정합성 테스트 이벤트")
                .startAt(Instant.now().minusSeconds(60))
                .endAt(Instant.ofEpochMilli(FAR_FUTURE_MILLIS))
                .winnerCount(1)
                .drawMethod("WEIGHTED")
                .status(EventStatus.OPEN)
                .createdBy(ownerMemberId)
                .createdAt(Instant.now())
                .build());
        eventId = event.getEventId();

        eventGateLoader.load(event);
    }

    /** SPEND Consumer(concurrency=1)가 실제로 반영해 스케줄러가 CLOSED로 전이시킬 때까지 최대 30초 기다린다. */
    private void awaitClosed() throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            eventLifecycleScheduler.run(eventId);
            Event current = eventRepository.findById(eventId).orElseThrow();
            if (current.getStatus() == EventStatus.CLOSED) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        throw new AssertionError("이벤트가 30초 안에 CLOSED로 전이되지 않았습니다. eventId=" + eventId);
    }

    @AfterEach
    void cleanUp() {
        List<String> keys = new ArrayList<>(List.of(
                EntryRedisKeys.status(eventId),
                EntryRedisKeys.endAt(eventId),
                EntryRedisKeys.cutoff(eventId),
                EntryRedisKeys.entryTotal(eventId),
                EntryRedisKeys.entrants(eventId),
                EntryRedisKeys.balance(creatorId, userAId),
                EntryRedisKeys.balance(creatorId, userBId)
        ));
        for (String requestId : requestIds) {
            keys.add(EntryRedisKeys.idem(requestId));
            keys.add(EntryRedisKeys.spendGuard(requestId));
        }
        redisTemplate.delete(keys);
        jdbcTemplate.update("DELETE FROM ticket_ledger WHERE creator_id = ?", creatorId);
        jdbcTemplate.update("DELETE FROM event_entry WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE creator_id = ?", creatorId);
        eventRepository.deleteById(eventId);
        creatorRepository.deleteById(creatorId);
        memberRepository.deleteById(userAId);
        memberRepository.deleteById(userBId);
        memberRepository.deleteById(ownerMemberId);
    }
}

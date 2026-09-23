package kr.co.cking.stream.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.co.cking.mission.CommonMissionCompletionRepository;
import kr.co.cking.stream.presentation.CommonEarnStreamListener;

/**
 * 공용 EARN PEL 회수(이슈 #219 리뷰 반영): PEL에 남은 메시지를 XCLAIM으로 회수해 재처리하고,
 * Dead Stream이 없는 대신 최대 재시도를 넘긴 메시지는 더 긴 간격으로만 다시 claim한다.
 *
 * <p>일시적 실패는 회원을 일부러 나중에 넣어 FK 위반으로 유도하고, 절대 성공할 수 없는
 * 실패는 존재하지 않는 공용 missionId로 유도한다. 공용 미션 유형은 {@code CommonMissionType}
 * enum에 묶여 있어 테스트 전용 공용 미션을 만들 수 없다.
 */
@SpringBootTest(properties = {
        "cking.ticket.common-earn-stream-key=stream:common-ticket-earned:pel-test",
        "cking.ticket.common-earn-consumer-group=cg:common-ticket-earn:pel-test",
        "cking.ticket.common-earn-pel-min-idle-ms=100",
        "cking.ticket.common-earn-pel-max-retry=1",
        "cking.ticket.common-earn-pel-over-limit-idle-ms=1000",
        "cking.scheduling.enabled=false"
})
class CommonEarnStreamPelRecoverySchedulerIntegrationTest {

    private static final String STREAM_KEY = "stream:common-ticket-earned:pel-test";
    private static final String CONSUMER_GROUP = "cg:common-ticket-earn:pel-test";
    private static final long AWAIT_TIMEOUT_MILLIS = 8000L;

    private static final long MEMBER_ID = 98311L;
    private static final long OTHER_MEMBER_ID = 98312L;
    private static final long MISSING_MISSION_ID = 98511L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CommonEarnStreamPelRecoveryScheduler scheduler;

    @Autowired
    private CommonEarnStreamListener commonEarnStreamListener;

    @Autowired
    private CommonMissionCompletionRepository commonMissionCompletionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long seededMissionId;

    @BeforeEach
    void setUp() {
        cleanUp();
        seededMissionId = jdbcTemplate.queryForObject(
                "SELECT mission_id FROM common_mission WHERE type = 'ATTENDANCE'", Long.class);
    }

    @AfterEach
    void tearDown() {
        // stream 키를 지우면 consumer group도 사라져 다음 테스트가 NOGROUP을 맞는다.
        // entry를 비우고 남은 PEL은 ACK로 정리한다(trim만으로는 PEL 항목이 남는다).
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(STREAM_KEY, CONSUMER_GROUP, Range.unbounded(), 100);
        pending.forEach(message -> redisTemplate.opsForStream()
                .acknowledge(STREAM_KEY, CONSUMER_GROUP, message.getId()));
        redisTemplate.opsForStream().trim(STREAM_KEY, 0);
        cleanUp();
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM common_ticket_ledger WHERE member_id IN (?, ?)", MEMBER_ID, OTHER_MEMBER_ID);
        jdbcTemplate.update("DELETE FROM user_common_ticket_balance WHERE member_id IN (?, ?)", MEMBER_ID, OTHER_MEMBER_ID);
        jdbcTemplate.update("DELETE FROM common_mission_completion WHERE member_id IN (?, ?)", MEMBER_ID, OTHER_MEMBER_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?)", MEMBER_ID, OTHER_MEMBER_ID);
    }

    @Test
    void PEL에_남은_공용_메시지를_XCLAIM으로_회수해서_재처리한다() throws InterruptedException {
        String requestId = UUID.randomUUID().toString();

        redisTemplate.opsForStream().add(STREAM_KEY, earnFields(requestId, MEMBER_ID, seededMissionId));

        // 회원이 아직 없어 첫 소비가 FK 위반으로 실패하고 메시지가 PEL에 남는다.
        awaitPending(1);
        assertThat(commonMissionCompletionRepository.findByRequestId(requestId)).isEmpty();

        insertMember(MEMBER_ID);
        Thread.sleep(150); // common-earn-pel-min-idle-ms(100ms)보다 충분히 대기

        scheduler.recoverPending();

        assertThat(commonMissionCompletionRepository.findByRequestId(requestId)).isPresent();
        Long balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM user_common_ticket_balance WHERE member_id = ?", Long.class, MEMBER_ID);
        assertThat(balance).isEqualTo(1L);
        assertThat(pendingMessages().isEmpty()).isTrue();
    }

    @Test
    void 최대_재시도를_넘긴_메시지는_간격이_지나기_전엔_claim하지_않고_지나면_재처리한다() throws InterruptedException {
        String requestId = UUID.randomUUID().toString();

        redisTemplate.opsForStream().add(STREAM_KEY, earnFields(requestId, MEMBER_ID, seededMissionId));

        // 1차 소비 실패(회원 없음) → 전달 1회.
        awaitPending(1);

        // 회수 1회차: 한도(1회) 이내라 claim → 여전히 실패 → 전달 2회(한도 초과).
        Thread.sleep(150);
        scheduler.recoverPending();
        assertThat(deliveryCountOfFirstPending()).isEqualTo(2L);

        // 회수 2회차: 한도 초과인데 over-limit 간격(1000ms)이 안 지났으니 claim하지 않는다.
        Thread.sleep(150);
        scheduler.recoverPending();
        assertThat(deliveryCountOfFirstPending()).isEqualTo(2L);
        assertThat(commonMissionCompletionRepository.findByRequestId(requestId)).isEmpty();

        // 원인이 해소되고 간격이 지나면 한도를 넘겼어도 포기하지 않고 재처리해 반영한다.
        insertMember(MEMBER_ID);
        Thread.sleep(1100);
        scheduler.recoverPending();

        assertThat(commonMissionCompletionRepository.findByRequestId(requestId)).isPresent();
        assertThat(pendingMessages().isEmpty()).isTrue();
    }

    @Test
    void 한도_초과_메시지가_앞_페이지를_채워도_뒤쪽_정상_메시지를_회수한다() throws InterruptedException {
        // 페이지 크기 2, over-limit 간격 60초 — 이 테스트 동안 한도 초과 메시지는 절대 claim되지 않는다.
        CommonEarnStreamPelRecoveryScheduler pagingScheduler = new CommonEarnStreamPelRecoveryScheduler(
                redisTemplate, commonEarnStreamListener, STREAM_KEY, CONSUMER_GROUP, 100L, 1L, 60_000L, 2);
        insertMember(MEMBER_ID);

        // 절대 성공할 수 없는 메시지 2건(공용 미션 없음)을 먼저 쌓고 한도를 넘긴다.
        redisTemplate.opsForStream().add(STREAM_KEY, earnFields(UUID.randomUUID().toString(), MEMBER_ID, MISSING_MISSION_ID));
        redisTemplate.opsForStream().add(STREAM_KEY, earnFields(UUID.randomUUID().toString(), MEMBER_ID, MISSING_MISSION_ID));
        awaitPending(2);
        Thread.sleep(150);
        pagingScheduler.recoverPending();

        // 그 뒤에 일시적으로 실패하는 정상 메시지(회원 없음)를 쌓는다.
        String requestId = UUID.randomUUID().toString();
        redisTemplate.opsForStream().add(STREAM_KEY, earnFields(requestId, OTHER_MEMBER_ID, seededMissionId));
        awaitPending(3);

        insertMember(OTHER_MEMBER_ID);
        Thread.sleep(150);
        pagingScheduler.recoverPending();

        // 첫 페이지(한도 초과 2건)를 건너뛰고 다음 페이지의 정상 메시지를 회수해 반영한다.
        assertThat(commonMissionCompletionRepository.findByRequestId(requestId)).isPresent();
        PendingMessages remaining = pendingMessages();
        assertThat(remaining.size()).isEqualTo(2);
        remaining.forEach(message -> assertThat(message.getTotalDeliveryCount()).isEqualTo(2L));
    }

    private Map<String, String> earnFields(String requestId, long memberId, Long missionId) {
        return Map.of(
                "requestId", requestId,
                "userId", String.valueOf(memberId),
                "missionType", "ATTENDANCE",
                "missionId", String.valueOf(missionId),
                "periodKey", "2026-09-16",
                "amount", "1"
        );
    }

    private void insertMember(long memberId) {
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                memberId, "공용 적립 대상 회원", "USER");
    }

    private PendingMessages pendingMessages() {
        return redisTemplate.opsForStream().pending(STREAM_KEY, CONSUMER_GROUP, Range.unbounded(), 10);
    }

    private long deliveryCountOfFirstPending() {
        return pendingMessages().get(0).getTotalDeliveryCount();
    }

    private void awaitPending(int expectedCount) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            if (pendingMessages().size() >= expectedCount) {
                return;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        throw new AssertionError("메시지가 제한 시간 내에 PEL에 쌓이지 않았습니다.");
    }
}

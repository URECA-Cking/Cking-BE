package kr.co.cking.stream.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
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

import kr.co.cking.mission.MissionCompletionRepository;
import kr.co.cking.stream.domain.DeadStreamMessage;
import kr.co.cking.stream.domain.DeadStreamType;
import kr.co.cking.stream.repository.DeadStreamMessageRepository;

/**
 * 완료조건(이슈 #43): PEL에 남은 메시지를 XCLAIM으로 회수해 재처리하고, 최대
 * 재시도를 넘긴 메시지는 dead_stream_message로 이동한다.
 *
 * <p>member/mission은 미리 넣어 두지만 creator는 일부러 넣지 않아, 처음 소비 시
 * FK 위반으로 DB 반영이 실패해 메시지가 PEL에 남도록 유도한다(서버 재기동 후에도
 * 원인이 해소되지 않으면 DB 반영이 실패한 채 PEL에 남는 상황을 재현). creator를
 * 나중에 넣고 회수를 재시도하면 성공하고, 끝까지 없으면 Dead Stream으로 이동한다.
 */
@SpringBootTest(properties = {
        "cking.ticket.earn-stream-key=stream:ticket-earned:pel-test",
        "cking.ticket.earn-consumer-group=cg:ticket-earn:pel-test",
        "cking.ticket.earn-pel-min-idle-ms=100",
        "cking.ticket.earn-pel-max-retry=1"
})
class EarnStreamPelRecoverySchedulerIntegrationTest {

    private static final String STREAM_KEY = "stream:ticket-earned:pel-test";
    private static final String CONSUMER_GROUP = "cg:ticket-earn:pel-test";
    private static final long AWAIT_TIMEOUT_MILLIS = 8000L;

    private static final long MEMBER_ID = 98301L;
    private static final long CREATOR_MEMBER_ID = 98302L;
    private static final long CREATOR_ID = 98401L;
    private static final long MISSION_ID = 98501L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EarnStreamPelRecoveryScheduler scheduler;

    @Autowired
    private DeadStreamMessageRepository deadStreamMessageRepository;

    @Autowired
    private MissionCompletionRepository missionCompletionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                MEMBER_ID, "적립 대상 회원", "USER");
    }

    @AfterEach
    void tearDown() {
        // stream 키 자체를 지우면 consumer group도 함께 사라져 다음 테스트가
        // NOGROUP 에러를 맞는다(그룹은 컨텍스트 시작 시 한 번만 생성됨) — entry만 비운다.
        redisTemplate.opsForStream().trim(STREAM_KEY, 0);
        cleanUp();
    }

    private void cleanUp() {
        deadStreamMessageRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM ticket_ledger WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM mission_completion WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM mission WHERE mission_id = ?", MISSION_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?)", MEMBER_ID, CREATOR_MEMBER_ID);
    }

    @Test
    void PEL에_남은_메시지를_XCLAIM으로_회수해서_재처리한다() throws InterruptedException {
        String requestId = UUID.randomUUID().toString();
        Map<String, String> fields = earnFields(requestId);

        redisTemplate.opsForStream().add(STREAM_KEY, fields);

        // creator가 아직 없어 첫 소비는 FK 위반으로 실패하고 메시지가 PEL에 남는다.
        awaitPending(1);
        assertThat(missionCompletionRepository.findByRequestId(requestId)).isEmpty();

        insertCreatorAndMission();
        Thread.sleep(150); // earn-pel-min-idle-ms(100ms)보다 충분히 대기

        scheduler.recoverPending();

        awaitCompletion(requestId);
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(STREAM_KEY, CONSUMER_GROUP, Range.unbounded(), 10);
        assertThat(pending.isEmpty()).isTrue();
    }

    @Test
    void 최대_재시도를_넘기면_Dead_Stream으로_이동하고_PEL에서_사라진다() throws InterruptedException {
        String requestId = UUID.randomUUID().toString();
        Map<String, String> fields = earnFields(requestId);

        String recordId = redisTemplate.opsForStream().add(STREAM_KEY, fields).getValue();

        // 1차 소비 실패(creator 없음) → PEL 적재.
        awaitPending(1);

        // 회수 1회차: 재시도지만 creator가 여전히 없어 다시 실패 → PEL에 남고 deliveryCount 증가.
        Thread.sleep(150);
        scheduler.recoverPending();
        Thread.sleep(150);

        // 회수 2회차: earn-pel-max-retry=1을 넘겨 Dead Stream으로 이동.
        scheduler.recoverPending();

        Optional<DeadStreamMessage> deadMessage =
                deadStreamMessageRepository.findBySourceStreamIdAndStreamType(recordId, DeadStreamType.EARN);
        assertThat(deadMessage).isPresent();
        assertThat(deadMessage.get().getRequestId()).isEqualTo(requestId);
        assertThat(deadMessage.get().isUnresolved()).isTrue();

        PendingMessages pending = redisTemplate.opsForStream()
                .pending(STREAM_KEY, CONSUMER_GROUP, Range.unbounded(), 10);
        assertThat(pending.isEmpty()).isTrue();
        assertThat(missionCompletionRepository.findByRequestId(requestId)).isEmpty();
    }

    private Map<String, String> earnFields(String requestId) {
        return Map.of(
                "requestId", requestId,
                "userId", String.valueOf(MEMBER_ID),
                "creatorId", String.valueOf(CREATOR_ID),
                "missionType", "ATTENDANCE",
                "missionId", String.valueOf(MISSION_ID),
                "periodKey", "2026-09-16",
                "missionKey", "attendance:%d:2026-09-16".formatted(CREATOR_ID),
                "amount", "5"
        );
    }

    private void insertCreatorAndMission() {
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                CREATOR_MEMBER_ID, "크리에이터 회원", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, CREATOR_MEMBER_ID, "PEL 테스트 크리에이터");
        jdbcTemplate.update("INSERT INTO mission (mission_id, creator_id, type, reward_amount) VALUES (?, ?, ?, ?)",
                MISSION_ID, CREATOR_ID, "ATTENDANCE", 5);
    }

    private void awaitPending(int expectedCount) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(STREAM_KEY, CONSUMER_GROUP, Range.unbounded(), 10);

            if (pending.size() >= expectedCount) {
                return;
            }

            sleep(100);
        }

        throw new AssertionError("메시지가 제한 시간 내에 PEL에 쌓이지 않았습니다.");
    }

    private void awaitCompletion(String requestId) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            if (missionCompletionRepository.findByRequestId(requestId).isPresent()) {
                return;
            }

            sleep(100);
        }

        throw new AssertionError("EARN Stream 메시지가 제한 시간 내에 반영되지 않았습니다. requestId=" + requestId);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

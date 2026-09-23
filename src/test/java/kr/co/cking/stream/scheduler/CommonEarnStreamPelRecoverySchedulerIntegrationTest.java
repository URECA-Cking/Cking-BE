package kr.co.cking.stream.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.co.cking.mission.CommonMissionCompletionRepository;
import kr.co.cking.stream.domain.DeadStreamMessage;
import kr.co.cking.stream.domain.DeadStreamType;
import kr.co.cking.stream.presentation.CommonEarnStreamListener;
import kr.co.cking.stream.repository.DeadStreamMessageRepository;
import kr.co.cking.ticket.application.CommonMissionEarnLedgerService;
import kr.co.cking.ticket.application.dto.CommonEarnCommand;
import tools.jackson.databind.ObjectMapper;

/**
 * 완료조건(이슈 #244): PEL에 남은 공용 EARN 메시지를 XCLAIM으로 회수해 재처리하고, 최대
 * 재시도를 넘긴 메시지는 dead_stream_message로 이동한다({@link EarnStreamPelRecoverySchedulerIntegrationTest}와
 * 동일 구조).
 *
 * <p>member는 일부러 나중에 넣어 첫 소비를 FK 위반으로 실패시키는 시나리오와, 존재하지
 * 않는 공용 missionId로 절대 성공할 수 없는 실패를 유도하는 시나리오를 나눠 검증한다.
 */
@SpringBootTest(properties = {
        "cking.ticket.common-earn-stream-key=stream:common-ticket-earned:pel-test",
        "cking.ticket.common-earn-consumer-group=cg:common-ticket-earn:pel-test",
        "cking.ticket.common-earn-pel-min-idle-ms=100",
        "cking.ticket.common-earn-pel-max-retry=1",
        "cking.scheduling.enabled=false"
})
class CommonEarnStreamPelRecoverySchedulerIntegrationTest {

    private static final String STREAM_KEY = "stream:common-ticket-earned:pel-test";
    private static final String CONSUMER_GROUP = "cg:common-ticket-earn:pel-test";
    private static final String REDELIVERY_STREAM_KEY = "stream:common-ticket-earned:pel-redelivery-test";
    private static final String REDELIVERY_CONSUMER_GROUP = "cg:common-ticket-earn:pel-redelivery-test";
    private static final long AWAIT_TIMEOUT_MILLIS = 8000L;

    private static final long MEMBER_ID = 98311L;
    private static final long MISSING_MISSION_ID = 98511L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CommonEarnStreamPelRecoveryScheduler scheduler;

    @Autowired
    private CommonMissionEarnLedgerService commonMissionEarnLedgerService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DeadStreamMessageRepository deadStreamMessageRepository;

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
        // stream 키 자체를 지우면 consumer group도 함께 사라져 다음 테스트가
        // NOGROUP 에러를 맞는다(그룹은 컨텍스트 시작 시 한 번만 생성됨) — entry만 비운다.
        // PR #250 리뷰(문구): trim은 Stream 본문만 비우고 PEL 항목은 남긴다 - "이관 실패"
        // 테스트가 일부러 PEL에 남긴 메시지를 ACK로 먼저 비우지 않으면, 다음 테스트의
        // awaitPending()이 이 잔존 항목 때문에 새 메시지를 기다리지 않고 바로 통과해버린다.
        PendingMessages leftover = redisTemplate.opsForStream()
                .pending(STREAM_KEY, CONSUMER_GROUP, Range.unbounded(), 100);
        leftover.forEach(m -> redisTemplate.opsForStream().acknowledge(STREAM_KEY, CONSUMER_GROUP, m.getId()));
        redisTemplate.delete(REDELIVERY_STREAM_KEY);
        redisTemplate.opsForStream().trim(STREAM_KEY, 0);
        cleanUp();
    }

    private void cleanUp() {
        deadStreamMessageRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM common_ticket_ledger WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM user_common_ticket_balance WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM common_mission_completion WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", MEMBER_ID);
    }

    @Test
    void PEL에_남은_공용_메시지를_XCLAIM으로_회수해서_재처리한다() throws InterruptedException {
        String requestId = UUID.randomUUID().toString();
        Map<String, String> fields = earnFields(requestId, seededMissionId);

        redisTemplate.opsForStream().add(STREAM_KEY, fields);

        // member가 아직 없어 첫 소비는 FK 위반으로 실패하고 메시지가 PEL에 남는다.
        awaitPending(1);
        assertThat(commonMissionCompletionRepository.findByRequestId(requestId)).isEmpty();

        insertMember(MEMBER_ID);
        Thread.sleep(150); // common-earn-pel-min-idle-ms(100ms)보다 충분히 대기

        scheduler.recoverPending();

        awaitCompletion(requestId);
        assertThat(pendingMessages().isEmpty()).isTrue();
    }

    @Test
    void DB_반영_후_ACK되지_않은_메시지를_재처리해도_중복_적립되지_않는다() throws InterruptedException {
        String requestId = UUID.randomUUID().toString();
        Map<String, String> fields = earnFields(requestId, seededMissionId);
        insertMember(MEMBER_ID);

        redisTemplate.opsForStream().add(REDELIVERY_STREAM_KEY, fields);
        redisTemplate.opsForStream().createGroup(
                REDELIVERY_STREAM_KEY, ReadOffset.from("0"), REDELIVERY_CONSUMER_GROUP);
        List<MapRecord<String, Object, Object>> delivered = redisTemplate.opsForStream().read(
                Consumer.from(REDELIVERY_CONSUMER_GROUP, "common-earn-before-ack-failure"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(REDELIVERY_STREAM_KEY, ReadOffset.lastConsumed())
        );
        assertThat(delivered).hasSize(1);

        commonMissionEarnLedgerService.apply(CommonEarnCommand.fromStreamFields(fields));

        CommonEarnStreamListener redeliveryListener = new CommonEarnStreamListener(
                commonMissionEarnLedgerService, redisTemplate, REDELIVERY_STREAM_KEY, REDELIVERY_CONSUMER_GROUP);
        CommonEarnStreamPelRecoveryScheduler redeliveryScheduler = new CommonEarnStreamPelRecoveryScheduler(
                redisTemplate, redeliveryListener, deadStreamMessageRepository, objectMapper,
                REDELIVERY_STREAM_KEY, REDELIVERY_CONSUMER_GROUP, 100L, 5L);
        Thread.sleep(150);
        redeliveryScheduler.recoverPending();

        Integer completionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM common_mission_completion WHERE request_id = ?", Integer.class, requestId);
        Integer ledgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM common_ticket_ledger WHERE request_id = ?", Integer.class, requestId);
        Long balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM user_common_ticket_balance WHERE member_id = ?", Long.class, MEMBER_ID);
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(REDELIVERY_STREAM_KEY, REDELIVERY_CONSUMER_GROUP, Range.unbounded(), 10);

        assertThat(completionCount).isEqualTo(1);
        assertThat(ledgerCount).isEqualTo(1);
        assertThat(balance).isEqualTo(1L);
        assertThat(pending.isEmpty()).isTrue();
    }

    @Test
    void 최대_재시도를_넘기면_Dead_Stream으로_이동하고_PEL에서_사라진다() throws InterruptedException {
        String requestId = UUID.randomUUID().toString();
        // dead_stream_message.member_id는 FK라 member가 있어야 이관 자체가 된다. 존재하지
        // 않는 missionId를 쓰므로 member 유무와 무관하게 EARN 반영 자체는 절대 성공할 수 없다.
        insertMember(MEMBER_ID);

        String recordId = redisTemplate.opsForStream()
                .add(STREAM_KEY, earnFields(requestId, MISSING_MISSION_ID)).getValue();

        // 1차 소비 실패 → PEL 적재.
        awaitPending(1);

        // 회수 1회차: 재시도지만 원인이 그대로라 다시 실패 → PEL에 남고 deliveryCount 증가.
        Thread.sleep(150);
        scheduler.recoverPending();
        Thread.sleep(150);

        // 회수 2회차: common-earn-pel-max-retry=1을 넘겨 Dead Stream으로 이동.
        scheduler.recoverPending();

        Optional<DeadStreamMessage> deadMessage = deadStreamMessageRepository
                .findBySourceStreamIdAndStreamType(recordId, DeadStreamType.COMMON_EARN);
        assertThat(deadMessage).isPresent();
        assertThat(deadMessage.get().getRequestId()).isEqualTo(requestId);
        assertThat(deadMessage.get().isUnresolved()).isTrue();

        assertThat(pendingMessages().isEmpty()).isTrue();
        assertThat(commonMissionCompletionRepository.findByRequestId(requestId)).isEmpty();
    }

    // dead_stream_message.member_id는 FK라, 존재하지 않는 userId로 이관 자체가 실패할 수 있다.
    // 그 실패가 같은 배치의 나머지 메시지 처리까지 막으면 안 된다.
    @Test
    void Dead_Stream_이관에_실패한_메시지가_있어도_같은_배치의_나머지_메시지는_계속_이관된다() throws InterruptedException {
        long unknownMemberId = 999999911L; // member 테이블에 존재하지 않음 - dead_stream_message INSERT가 FK로 실패한다.
        String failingRequestId = UUID.randomUUID().toString();
        String recoverableRequestId = UUID.randomUUID().toString();
        insertMember(MEMBER_ID); // MEMBER_ID 쪽은 이관이 성공해야 하므로 member가 있어야 한다.

        redisTemplate.opsForStream().add(STREAM_KEY,
                Map.of("requestId", failingRequestId, "userId", String.valueOf(unknownMemberId),
                        "missionType", "ATTENDANCE", "missionId", String.valueOf(MISSING_MISSION_ID),
                        "periodKey", "2026-09-16", "amount", "1"));
        String recoverableRecordId = redisTemplate.opsForStream()
                .add(STREAM_KEY, earnFields(recoverableRequestId, MISSING_MISSION_ID)).getValue();
        awaitPending(2);

        // 회수 1회차: 둘 다 여전히 실패(존재하지 않는 missionId) → PEL에 남고 deliveryCount 증가.
        Thread.sleep(150);
        scheduler.recoverPending();
        Thread.sleep(150);

        // 회수 2회차: 둘 다 한도를 넘겨 이관을 시도한다. unknownMemberId 쪽은 FK 위반으로
        // 이관 자체가 실패하지만, 예외를 삼키고 계속 진행해 MEMBER_ID 쪽은 정상 이관돼야 한다.
        scheduler.recoverPending();

        assertThat(deadStreamMessageRepository.findBySourceStreamIdAndStreamType(
                recoverableRecordId, DeadStreamType.COMMON_EARN)).isPresent();

        // 실패한 메시지는 이관되지 못한 채 PEL에 남는다(알려진 제약) - 그래도 다른 메시지를
        // 막지는 않았다는 게 이 테스트의 핵심이다.
        assertThat(pendingMessages().size()).isEqualTo(1);
    }

    private Map<String, String> earnFields(String requestId, long missionId) {
        return Map.of(
                "requestId", requestId,
                "userId", String.valueOf(MEMBER_ID),
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

    private void awaitPending(int expectedCount) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            if (pendingMessages().size() >= expectedCount) {
                return;
            }
            sleep(100);
        }

        throw new AssertionError("메시지가 제한 시간 내에 PEL에 쌓이지 않았습니다.");
    }

    private void awaitCompletion(String requestId) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            if (commonMissionCompletionRepository.findByRequestId(requestId).isPresent()) {
                return;
            }
            sleep(100);
        }

        throw new AssertionError("공용 EARN Stream 메시지가 제한 시간 내에 반영되지 않았습니다. requestId=" + requestId);
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

package kr.co.cking.stream.presentation;

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

import kr.co.cking.mission.CommonMissionCompletion;
import kr.co.cking.mission.CommonMissionCompletionRepository;

/**
 * {@code EarnStreamConsumerIntegrationTest}와 동일 계약(이슈 #219): cg:common-ticket-earn
 * 그룹으로 stream:common-ticket-earned를 실제로 소비해서 DB에 반영하고, DB 반영
 * 후에만 XACK하는지 끝까지(end-to-end) 검증한다. 운영 stream 키와 겹치지 않도록
 * 테스트 전용 키/그룹으로 오버라이드한다.
 *
 * <p>공용 미션(ATTENDANCE)은 유형당 하나뿐(uk_common_mission_type)이라 V16 마이그레이션이
 * 시딩한 행을 그대로 쓴다 — 이 테스트가 별도로 만들지 않는다.
 */
@SpringBootTest(properties = {
        "cking.ticket.common-earn-stream-key=stream:common-ticket-earned:test",
        "cking.ticket.common-earn-consumer-group=cg:common-ticket-earn:test"
})
class CommonEarnStreamConsumerIntegrationTest {

    private static final String STREAM_KEY = "stream:common-ticket-earned:test";
    private static final String CONSUMER_GROUP = "cg:common-ticket-earn:test";
    private static final long AWAIT_TIMEOUT_MILLIS = 8000L;

    private static final long MEMBER_ID = 99002L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CommonMissionCompletionRepository commonMissionCompletionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long missionId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                MEMBER_ID, "공용 적립 대상 회원", "USER");
        missionId = jdbcTemplate.queryForObject(
                "SELECT mission_id FROM common_mission WHERE type = 'ATTENDANCE'", Long.class);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(STREAM_KEY);
        cleanUp();
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM common_ticket_ledger WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM user_common_ticket_balance WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM common_mission_completion WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", MEMBER_ID);
    }

    @Test
    void 메시지를_소비해서_DB에_반영하고_XACK한다() {
        String requestId = UUID.randomUUID().toString();

        Map<String, String> fields = Map.of(
                "requestId", requestId,
                "userId", String.valueOf(MEMBER_ID),
                "missionType", "ATTENDANCE",
                "missionId", String.valueOf(missionId),
                "periodKey", "2026-09-16",
                "amount", "1"
        );

        redisTemplate.opsForStream().add(STREAM_KEY, fields);

        CommonMissionCompletion completion = awaitCompletion(requestId);

        assertThat(completion.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(completion.getMissionId()).isEqualTo(missionId);

        // DB 반영 후에만 XACK하므로, 반영이 끝난 시점엔 PEL이 비어 있어야 한다.
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(STREAM_KEY, CONSUMER_GROUP, Range.unbounded(), 10);
        assertThat(pending.isEmpty()).isTrue();
    }

    private CommonMissionCompletion awaitCompletion(String requestId) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            Optional<CommonMissionCompletion> found = commonMissionCompletionRepository.findByRequestId(requestId);

            if (found.isPresent()) {
                return found.get();
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        throw new AssertionError("공용 EARN Stream 메시지가 제한 시간 내에 반영되지 않았습니다. requestId=" + requestId);
    }
}

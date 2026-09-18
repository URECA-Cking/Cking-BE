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

import kr.co.cking.mission.MissionCompletion;
import kr.co.cking.mission.MissionCompletionRepository;

/**
 * 완료조건(이슈 #39): cg:ticket-earn 그룹으로 stream:ticket-earned를 실제로 소비해서
 * DB에 반영하고, DB 반영 후에만 XACK하는지 끝까지(end-to-end) 검증한다. 운영 stream
 * 키와 절대 겹치면 안 되므로 테스트 전용 키/그룹으로 오버라이드한다.
 *
 * <p>member_id/creator_id는 명시적으로 고정 ID를 지정해서 INSERT한다 — 로컬 개발 DB의
 * member 테이블이 AUTO_INCREMENT 없이 구성돼 있어(OfficialSnapshotServiceIntegrationTest와
 * 동일한 제약) repository.save()의 IDENTITY 채번에 의존할 수 없다.
 */
@SpringBootTest(properties = {
        "cking.ticket.earn-stream-key=stream:ticket-earned:test",
        "cking.ticket.earn-consumer-group=cg:ticket-earn:test"
})
class EarnStreamConsumerIntegrationTest {

    private static final String STREAM_KEY = "stream:ticket-earned:test";
    private static final String CONSUMER_GROUP = "cg:ticket-earn:test";
    private static final long AWAIT_TIMEOUT_MILLIS = 8000L;

    private static final long OWNER_MEMBER_ID = 98001L;
    private static final long MEMBER_ID = 98002L;
    private static final long CREATOR_ID = 98101L;
    private static final long MISSION_ID = 98201L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MissionCompletionRepository missionCompletionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                OWNER_MEMBER_ID, "크리에이터 회원", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                MEMBER_ID, "적립 대상 회원", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, OWNER_MEMBER_ID, "스트림 테스트 크리에이터");
        jdbcTemplate.update("INSERT INTO mission (mission_id, creator_id, type, reward_amount) VALUES (?, ?, ?, ?)",
                MISSION_ID, CREATOR_ID, "ATTENDANCE", 1);
    }

    @AfterEach
    void tearDown() {
        // 스트림 키 자체를 지우면 그 안의 Consumer Group도 함께 사라져 이후 테스트가
        // NOGROUP으로 깨진다(그룹은 컨텍스트 기동 시 한 번만 생성됨) — 키는 남기고
        // DB 행만 정리한다.
        cleanUp();
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM ticket_ledger WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ? AND creator_id = ?", MEMBER_ID, CREATOR_ID);
        jdbcTemplate.update("DELETE FROM mission_completion WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM mission WHERE mission_id = ?", MISSION_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?)", MEMBER_ID, OWNER_MEMBER_ID);
    }

    @Test
    void 메시지를_소비해서_DB에_반영하고_XACK한다() {
        String requestId = UUID.randomUUID().toString();

        Map<String, String> fields = Map.of(
                "requestId", requestId,
                "userId", String.valueOf(MEMBER_ID),
                "creatorId", String.valueOf(CREATOR_ID),
                "missionType", "ATTENDANCE",
                "missionId", String.valueOf(MISSION_ID),
                "periodKey", "2026-09-16",
                "missionKey", "attendance:%d:2026-09-16".formatted(CREATOR_ID),
                "amount", "5"
        );

        redisTemplate.opsForStream().add(STREAM_KEY, fields);

        MissionCompletion completion = awaitCompletion(requestId);

        assertThat(completion.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(completion.getCreatorId()).isEqualTo(CREATOR_ID);
        assertThat(completion.getMissionId()).isEqualTo(MISSION_ID);

        // DB 반영 후에만 XACK하므로, 반영이 끝난 시점엔 PEL이 비어 있어야 한다.
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(STREAM_KEY, CONSUMER_GROUP, Range.unbounded(), 10);
        assertThat(pending.isEmpty()).isTrue();
    }

    private MissionCompletion awaitCompletion(String requestId) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            Optional<MissionCompletion> found = missionCompletionRepository.findByRequestId(requestId);

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

        throw new AssertionError("EARN Stream 메시지가 제한 시간 내에 반영되지 않았습니다. requestId=" + requestId);
    }
}

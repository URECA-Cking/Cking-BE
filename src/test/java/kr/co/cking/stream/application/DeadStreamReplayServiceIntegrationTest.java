package kr.co.cking.stream.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import kr.co.cking.event.repository.EventEntryRepository;
import kr.co.cking.mission.MissionCompletionRepository;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.stream.domain.DeadStreamMessage;
import kr.co.cking.stream.domain.DeadStreamResolutionStatus;
import kr.co.cking.stream.domain.DeadStreamType;
import kr.co.cking.stream.repository.DeadStreamMessageRepository;

/**
 * 완료조건(Codex 리뷰 반영, 이슈 #62 확장): Dead Stream으로 이동한 SPEND 메시지도
 * EARN과 동일하게 운영자가 수동 replay로 재처리할 수 있어야 한다(FR-P2-020).
 */
@SpringBootTest
class DeadStreamReplayServiceIntegrationTest {

    private static final long OWNER_MEMBER_ID = 93001L;
    private static final long MEMBER_ID = 93002L;
    private static final long CREATOR_ID = 93101L;
    private static final long MISSION_ID = 93201L;
    private static final long RESOLVED_BY = 93301L;

    @Autowired
    private DeadStreamReplayService deadStreamReplayService;

    @Autowired
    private DeadStreamMessageRepository deadStreamMessageRepository;

    @Autowired
    private EventEntryRepository eventEntryRepository;

    @Autowired
    private MissionCompletionRepository missionCompletionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long eventId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                OWNER_MEMBER_ID, "크리에이터 회원", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                MEMBER_ID, "응모 대상 회원", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                RESOLVED_BY, "운영자 회원", "ADMIN");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, OWNER_MEMBER_ID, "replay 테스트 크리에이터");
        jdbcTemplate.update("INSERT INTO mission (mission_id, creator_id, type, reward_amount) VALUES (?, ?, ?, ?)",
                MISSION_ID, CREATOR_ID, "ATTENDANCE", 1);

        String requestId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO event (creator_id, title, start_at, end_at, winner_count, draw_method,
                                    status, request_id, created_by)
                VALUES (?, ?, '2026-09-01 00:00:00', '2026-12-01 00:00:00', 1, 'WEIGHTED', 'OPEN', ?, ?)
                """, CREATOR_ID, "replay 테스트 이벤트", requestId, OWNER_MEMBER_ID);
        eventId = jdbcTemplate.queryForObject(
                "SELECT event_id FROM event WHERE request_id = ?", Long.class, requestId);
        jdbcTemplate.update(
                "INSERT INTO user_ticket_balance (member_id, creator_id, balance, updated_at) VALUES (?, ?, ?, NOW(6))",
                MEMBER_ID, CREATOR_ID, 100L);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        deadStreamMessageRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM ticket_ledger WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM event_entry WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM mission_completion WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ? AND creator_id = ?", MEMBER_ID, CREATOR_ID);
        jdbcTemplate.update("DELETE FROM event WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM mission WHERE mission_id = ?", MISSION_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?, ?)", MEMBER_ID, OWNER_MEMBER_ID, RESOLVED_BY);
    }

    @Test
    void SPEND_Dead_Stream_메시지를_replay하면_Entry가_반영되고_RESOLVED된다() {
        String requestId = UUID.randomUUID().toString();
        Map<String, String> fields = Map.of(
                "eventId", String.valueOf(eventId),
                "userId", String.valueOf(MEMBER_ID),
                "creatorId", String.valueOf(CREATOR_ID),
                "requestId", requestId,
                "ticketCount", "3"
        );
        DeadStreamMessage saved = deadStreamMessageRepository.save(DeadStreamMessage.builder()
                .sourceStreamId("1234-0")
                .streamType(DeadStreamType.SPEND)
                .payload(objectMapper.writeValueAsString(fields))
                .requestId(requestId)
                .eventId(eventId)
                .memberId(MEMBER_ID)
                .failureReason("테스트 유도 실패")
                .retryCount(6)
                .lastFailedAt(Instant.now())
                .resolutionStatus(DeadStreamResolutionStatus.UNRESOLVED)
                .createdAt(Instant.now())
                .build());

        deadStreamReplayService.replay(saved.getId(), RESOLVED_BY);

        assertThat(eventEntryRepository.findByRequestId(requestId)).isPresent();

        DeadStreamMessage resolved = deadStreamMessageRepository.findById(saved.getId()).orElseThrow();
        assertThat(resolved.isUnresolved()).isFalse();
        assertThat(resolved.getResolvedBy()).isEqualTo(RESOLVED_BY);
    }

    @Test
    void EARN_Dead_Stream_메시지를_replay하면_Ledger가_반영되고_RESOLVED된다() {
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
        DeadStreamMessage saved = deadStreamMessageRepository.save(DeadStreamMessage.builder()
                .sourceStreamId("5678-0")
                .streamType(DeadStreamType.EARN)
                .payload(objectMapper.writeValueAsString(fields))
                .requestId(requestId)
                .memberId(MEMBER_ID)
                .failureReason("테스트 유도 실패")
                .retryCount(6)
                .lastFailedAt(Instant.now())
                .resolutionStatus(DeadStreamResolutionStatus.UNRESOLVED)
                .createdAt(Instant.now())
                .build());

        deadStreamReplayService.replay(saved.getId(), RESOLVED_BY);

        assertThat(missionCompletionRepository.findByRequestId(requestId)).isPresent();

        DeadStreamMessage resolved = deadStreamMessageRepository.findById(saved.getId()).orElseThrow();
        assertThat(resolved.isUnresolved()).isFalse();
    }

    @Test
    void 같은_메시지를_동시에_replay해도_한_번만_적용하고_처음_처리자를_유지한다() throws Exception {
        String requestId = UUID.randomUUID().toString();
        Map<String, String> fields = Map.of(
                "eventId", String.valueOf(eventId),
                "userId", String.valueOf(MEMBER_ID),
                "creatorId", String.valueOf(CREATOR_ID),
                "requestId", requestId,
                "ticketCount", "3"
        );
        DeadStreamMessage saved = deadStreamMessageRepository.save(DeadStreamMessage.builder()
                .sourceStreamId("1236-0")
                .streamType(DeadStreamType.SPEND)
                .payload(objectMapper.writeValueAsString(fields))
                .requestId(requestId)
                .eventId(eventId)
                .memberId(MEMBER_ID)
                .failureReason("테스트 유도 실패")
                .retryCount(6)
                .lastFailedAt(Instant.now())
                .resolutionStatus(DeadStreamResolutionStatus.UNRESOLVED)
                .createdAt(Instant.now())
                .build());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DeadStreamMessage> first = pool.submit(() -> {
                start.await();
                return deadStreamReplayService.replay(saved.getId(), RESOLVED_BY);
            });
            Future<DeadStreamMessage> second = pool.submit(() -> {
                start.await();
                return deadStreamReplayService.replay(saved.getId(), OWNER_MEMBER_ID);
            });
            start.countDown();

            DeadStreamMessage firstResult = first.get(30, TimeUnit.SECONDS);
            DeadStreamMessage secondResult = second.get(30, TimeUnit.SECONDS);

            // 둘 다 예외 없이 끝나고, 나중에 들어온 요청은 먼저 처리한 관리자의 결과를 그대로 받는다.
            DeadStreamMessage finalState = deadStreamMessageRepository.findById(saved.getId()).orElseThrow();
            assertThat(finalState.isUnresolved()).isFalse();
            assertThat(firstResult.getResolvedBy()).isEqualTo(finalState.getResolvedBy());
            assertThat(secondResult.getResolvedBy()).isEqualTo(finalState.getResolvedBy());
            // 먼저 커밋한 요청은 메모리의 Instant(Linux는 나노초)를, 나중 요청은 DB(DATETIME(6))에서 읽은 값을 돌려받는다.
            // MySQL은 소수부를 잘라내지 않고 반올림하므로 1마이크로초 오차까지 같은 값으로 본다.
            assertThat(firstResult.getResolvedAt()).isCloseTo(finalState.getResolvedAt(), within(1, ChronoUnit.MICROS));
            assertThat(secondResult.getResolvedAt()).isCloseTo(finalState.getResolvedAt(), within(1, ChronoUnit.MICROS));
            assertThat(eventEntryRepository.findByRequestId(requestId)).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 존재하지_않는_메시지_ID면_RESOURCE_NOT_FOUND를_던진다() {
        assertThatThrownBy(() -> deadStreamReplayService.replay(-1L, RESOLVED_BY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void 이미_RESOLVED인_메시지는_다시_적용하지_않고_처리_정보도_바꾸지_않는다() {
        String requestId = UUID.randomUUID().toString();
        Map<String, String> fields = Map.of(
                "eventId", String.valueOf(eventId),
                "userId", String.valueOf(MEMBER_ID),
                "creatorId", String.valueOf(CREATOR_ID),
                "requestId", requestId,
                "ticketCount", "3"
        );
        DeadStreamMessage message = DeadStreamMessage.builder()
                .sourceStreamId("1235-0")
                .streamType(DeadStreamType.SPEND)
                .payload(objectMapper.writeValueAsString(fields))
                .requestId(requestId)
                .eventId(eventId)
                .memberId(MEMBER_ID)
                .failureReason("테스트 유도 실패")
                .retryCount(6)
                .lastFailedAt(Instant.now())
                .resolutionStatus(DeadStreamResolutionStatus.UNRESOLVED)
                .createdAt(Instant.now())
                .build();
        message.resolve(OWNER_MEMBER_ID, Instant.parse("2026-09-20T00:00:00Z"));
        DeadStreamMessage saved = deadStreamMessageRepository.save(message);

        DeadStreamMessage result = deadStreamReplayService.replay(saved.getId(), RESOLVED_BY);

        assertThat(result.getResolvedBy()).isEqualTo(OWNER_MEMBER_ID);
        assertThat(eventEntryRepository.findByRequestId(requestId)).isEmpty();
    }
}

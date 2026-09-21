package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.drawing.repository.DrawSeedRepository;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventEntry;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.domain.PrizeConfig;
import kr.co.cking.event.repository.EventEntryRepository;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.snapshot.application.OfficialSnapshotResult;
import kr.co.cking.snapshot.application.OfficialSnapshotService;
import kr.co.cking.snapshot.application.SnapshotIntegrityService;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.snapshot.domain.DrawSnapshotCandidate;
import kr.co.cking.snapshot.domain.DrawSnapshotPrize;
import kr.co.cking.snapshot.repository.DrawSnapshotCandidateRepository;
import kr.co.cking.snapshot.repository.DrawSnapshotPrizeRepository;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.domain.WinnerManagementStatus;
import kr.co.cking.winner.repository.WinnerManagementRepository;
import kr.co.cking.winner.repository.WinnerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Sprint 1의 EventEntry 확정부터 INITIAL Drawing 관리자 조회까지를 관통하는 E2E 검증. */
@SpringBootTest
@AutoConfigureMockMvc
class Sprint1InitialDrawingE2ETest {

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventEntryRepository eventEntryRepository;
    @Autowired private OfficialSnapshotService officialSnapshotService;
    @Autowired private SnapshotIntegrityService snapshotIntegrityService;
    @Autowired private DrawSnapshotCandidateRepository candidateRepository;
    @Autowired private DrawSnapshotPrizeRepository prizeRepository;
    @Autowired private DrawingRepository drawingRepository;
    @Autowired private DrawSeedRepository seedRepository;
    @Autowired private WinnerRepository winnerRepository;
    @Autowired private WinnerManagementRepository winnerManagementRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate redisTemplate;

    private final List<Long> memberIds = new ArrayList<>();
    private Long creatorId;
    private Long eventId;
    private Long adminId;
    private Long firstCandidateId;
    private Long secondCandidateId;
    private Long thirdCandidateId;

    @BeforeEach
    void setUp() {
        Member admin = saveMember("Sprint1 관리자", "010-1000-0001", "admin@cking.test", MemberRole.ADMIN);
        adminId = admin.getMemberId();
        Member owner = saveMember("Sprint1 크리에이터", "010-1000-0002", "creator@cking.test", MemberRole.USER);
        Creator creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), owner.getName()));
        creatorId = creator.getCreatorId();

        Member first = saveMember("첫 번째 후보", "010-1000-0011", "first@cking.test", MemberRole.USER);
        Member second = saveMember("두 번째 후보", "010-1000-0012", "second@cking.test", MemberRole.USER);
        Member third = saveMember("세 번째 후보", "010-1000-0013", "third@cking.test", MemberRole.USER);
        firstCandidateId = first.getMemberId();
        secondCandidateId = second.getMemberId();
        thirdCandidateId = third.getMemberId();

        Event event = eventRepository.saveAndFlush(new Event(
                creatorId,
                "Sprint 1 E2E Event",
                "상품 등급 가중치 추첨 E2E",
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-15T00:00:00Z"),
                2,
                DrawMethod.WEIGHTED,
                owner.getMemberId(),
                UUID.randomUUID().toString(),
                List.of(
                        new PrizeConfig("FIRST", "10만원권", 1, 1L, 1),
                        new PrizeConfig("SECOND", "5만원권", 2, 99L, 1)
                )
        ));
        eventId = event.getEventId();
        jdbcTemplate.update("UPDATE event SET status = 'CLOSED' WHERE event_id = ?", eventId);

        eventEntryRepository.saveAllAndFlush(List.of(
                entry(firstCandidateId, 3L),
                entry(firstCandidateId, 7L),
                entry(secondCandidateId, 5L),
                entry(thirdCandidateId, 2L)
        ));
    }

    @AfterEach
    void tearDown() {
        if (eventId == null) {
            return;
        }
        List<Long> seedIds = jdbcTemplate.queryForList(
                "SELECT seed_id FROM drawing WHERE event_id = ?", Long.class, eventId);
        jdbcTemplate.update("""
                DELETE management
                FROM winner_management management
                JOIN winner ON management.winner_id = winner.id
                WHERE winner.event_id = ?
                """, eventId);
        jdbcTemplate.update("DELETE FROM winner WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM drawing WHERE event_id = ?", eventId);
        seedIds.forEach(seedRepository::deleteById);
        jdbcTemplate.update("""
                DELETE candidate
                FROM draw_snapshot_candidate candidate
                JOIN draw_snapshot snapshot ON candidate.snapshot_id = snapshot.id
                WHERE snapshot.event_id = ?
                """, eventId);
        jdbcTemplate.update("""
                DELETE prize
                FROM draw_snapshot_prize prize
                JOIN draw_snapshot snapshot ON prize.snapshot_id = snapshot.id
                WHERE snapshot.event_id = ?
                """, eventId);
        jdbcTemplate.update("DELETE FROM draw_snapshot WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM event_entry WHERE event_id = ?", eventId);
        eventRepository.deleteById(eventId);
        creatorRepository.deleteById(creatorId);
        memberRepository.deleteAllById(memberIds);
    }

    @Test
    void EventEntry부터_Snapshot_INITIAL_Drawing_관리자_조회까지_정상_흐름을_검증한다() throws Exception {
        String redisPing = redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
        assertThat(redisPing).isEqualTo("PONG");

        OfficialSnapshotResult snapshot = officialSnapshotService.createIfAbsent(eventId);
        VerifiedSnapshot verified = snapshotIntegrityService.verifyForDrawing(eventId);

        assertThat(verified.snapshotHash()).isEqualTo(snapshot.snapshotHash());
        assertThat(snapshot.snapshotHash()).matches("[0-9a-f]{64}");
        assertThat(verified.candidateCount()).isEqualTo(3);
        assertThat(verified.totalTicketCount()).isEqualTo(17L);
        assertThat(verified.prizeAlgorithmVersion()).isEqualTo("PRIZE_WEIGHTED_V1");
        assertThat(verified.candidates()).extracting(candidate -> candidate.memberId()).isSorted();
        assertThat(verified.candidates())
                .extracting(candidate -> candidate.memberId(), candidate -> candidate.ticketCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(firstCandidateId, 10L),
                        org.assertj.core.groups.Tuple.tuple(secondCandidateId, 5L),
                        org.assertj.core.groups.Tuple.tuple(thirdCandidateId, 2L)
                );
        assertThat(verified.prizes())
                .extracting(prize -> prize.prizeKey(), prize -> prize.displayName(), prize -> prize.priority(),
                        prize -> prize.weight(), prize -> prize.quantity())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("FIRST", "10만원권", 1, 1L, 1),
                        org.assertj.core.groups.Tuple.tuple("SECOND", "5만원권", 2, 99L, 1)
                );
        assertThat(verified.prizes()).allSatisfy(prize -> assertThat(prize.snapshotPrizeId()).isPositive());

        List<DrawSnapshotCandidate> storedCandidates = candidateRepository
                .findAllBySnapshot_IdOrderByMemberIdAsc(snapshot.snapshotId());
        assertThat(storedCandidates).extracting(DrawSnapshotCandidate::getMemberId)
                .isSorted();
        List<DrawSnapshotPrize> storedPrizes = prizeRepository
                .findAllBySnapshot_IdOrderByPriorityAscPrizeKeyAsc(snapshot.snapshotId());
        assertThat(storedPrizes).extracting(DrawSnapshotPrize::getPrizeKey)
                .containsExactly("FIRST", "SECOND");

        mockMvc.perform(get("/api/admin/events/{eventId}/snapshot", eventId)
                        .param("userId", adminId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshotId").value(snapshot.snapshotId()))
                .andExpect(jsonPath("$.data.candidateCount").value(3))
                .andExpect(jsonPath("$.data.totalTicketCount").value(17))
                .andExpect(jsonPath("$.data.prizeAlgorithmVersion").value("PRIZE_WEIGHTED_V1"))
                .andExpect(jsonPath("$.data.prizes.length()").value(2))
                .andExpect(jsonPath("$.data.prizes[0].prizeKey").value("FIRST"))
                .andExpect(jsonPath("$.data.prizes[1].prizeKey").value("SECOND"));

        MvcResult firstExecution = executeDrawing();
        long drawingId = drawingId(firstExecution);
        long seedCountAfterFirstExecution = seedRepository.count();
        long winnerCountAfterFirstExecution = winnerRepository.count();

        Drawing drawing = drawingRepository.findById(drawingId).orElseThrow();
        List<Winner> winners = winnerRepository.findAllByDrawingIdOrderByRankInDrawingAsc(drawingId);
        assertThat(drawing.getSnapshotId()).isEqualTo(snapshot.snapshotId());
        assertThat(drawing.getStatus()).isEqualTo(DrawingStatus.COMPLETED);
        assertThat(drawing.getVisibility()).isEqualTo(DrawingVisibility.PRIVATE);
        assertThat(drawing.getPrizeAlgorithmVersion()).isEqualTo("PRIZE_WEIGHTED_V1");
        assertThat(drawing.getInputPayload()).startsWith("CKING_DRAW_INPUT_V2\n");
        assertThat(drawing.getOutputPayload()).startsWith("CKING_DRAW_RESULT_V2\n");
        assertThat(drawing.getInputHash()).matches("[0-9a-f]{64}");
        assertThat(drawing.getResultHash()).matches("[0-9a-f]{64}");
        assertThat(winners).hasSize(2);
        assertThat(winners).extracting(Winner::getRankInDrawing).containsExactly(1, 2);
        assertThat(winners).extracting(Winner::getMemberId).doesNotHaveDuplicates();
        assertThat(winners).allSatisfy(winner -> {
            assertThat(winner.getEventId()).isEqualTo(eventId);
            assertThat(winner.getDrawingId()).isEqualTo(drawingId);
            assertThat(winner.getSnapshotId()).isEqualTo(snapshot.snapshotId());
            assertThat(winner.getAppliedTicketCount()).isPositive();
            assertThat(winner.getSnapshotPrizeId()).isPositive();
            assertThat(winner.getPrizeKey()).isNotBlank();
            assertThat(winner.getPrizeDisplayName()).isNotBlank();
            assertThat(winner.getPrizePriority()).isPositive();
        });
        Map<Long, kr.co.cking.snapshot.domain.PrizeValue> officialPrizes = verified.prizes().stream()
                .collect(Collectors.toMap(prize -> prize.snapshotPrizeId(), Function.identity()));
        assertThat(winners).allSatisfy(winner -> {
            var officialPrize = officialPrizes.get(winner.getSnapshotPrizeId());
            assertThat(officialPrize).isNotNull();
            assertThat(winner.getPrizeKey()).isEqualTo(officialPrize.prizeKey());
            assertThat(winner.getPrizeDisplayName()).isEqualTo(officialPrize.displayName());
            assertThat(winner.getPrizePriority()).isEqualTo(officialPrize.priority());
        });
        Map<String, Long> assignedCounts = winners.stream()
                .collect(Collectors.groupingBy(Winner::getPrizeKey, Collectors.counting()));
        verified.prizes().forEach(prize -> assertThat(assignedCounts.getOrDefault(prize.prizeKey(), 0L))
                .isLessThanOrEqualTo(prize.quantity()));
        assertThat(winners).allSatisfy(winner -> assertThat(
                winnerManagementRepository.findByWinnerId(winner.getId()).orElseThrow().getStatus())
                .isEqualTo(WinnerManagementStatus.SELECTED));
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(EventStatus.DRAW_COMPLETED);

        mockMvc.perform(get("/api/admin/drawings/{drawingId}", drawingId)
                        .param("userId", adminId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.drawingId").value(drawingId))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.visibility").value("PRIVATE"));

        mockMvc.perform(get("/api/admin/drawings/{drawingId}/result", drawingId)
                        .param("userId", adminId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.drawingId").value(drawingId))
                .andExpect(jsonPath("$.data.winners.length()").value(2))
                .andExpect(jsonPath("$.data.winners[0].rankInDrawing").value(1))
                .andExpect(jsonPath("$.data.winners[1].rankInDrawing").value(2))
                .andExpect(jsonPath("$.data.winners[0].name").isNotEmpty())
                .andExpect(jsonPath("$.data.winners[0].phone").isNotEmpty())
                .andExpect(jsonPath("$.data.winners[0].email").isNotEmpty())
                .andExpect(jsonPath("$.data.winners[0].prizeKey").isNotEmpty())
                .andExpect(jsonPath("$.data.winners[0].prizeDisplayName").isNotEmpty())
                .andExpect(jsonPath("$.data.winners[0].prizePriority").isNumber())
                .andExpect(jsonPath("$.data.winners[1].prizeKey").isNotEmpty());

        MvcResult replay = executeDrawing();
        assertThat(drawingId(replay)).isEqualTo(drawingId);
        assertThat(seedRepository.count()).isEqualTo(seedCountAfterFirstExecution);
        assertThat(winnerRepository.count()).isEqualTo(winnerCountAfterFirstExecution);
        assertThat(drawingCount()).isEqualTo(1L);
    }

    @Test
    void 동시_관리자_INITIAL_Drawing_요청도_하나의_결과로_수렴한다() throws Exception {
        officialSnapshotService.createIfAbsent(eventId);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first = executor.submit(() -> executeDrawingAfter(start));
            Future<MvcResult> second = executor.submit(() -> executeDrawingAfter(start));
            start.countDown();

            long firstDrawingId = drawingId(first.get());
            long secondDrawingId = drawingId(second.get());

            assertThat(secondDrawingId).isEqualTo(firstDrawingId);
            assertThat(drawingCount()).isEqualTo(1L);
            assertThat(winnerRepository.findAllByDrawingIdOrderByRankInDrawingAsc(firstDrawingId)).hasSize(2);
        }
    }

    private MvcResult executeDrawing() throws Exception {
        return mockMvc.perform(post("/api/admin/events/{eventId}/drawings", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + adminId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.eventId").value(eventId))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.winnerCount").value(2))
                .andReturn();
    }

    private MvcResult executeDrawingAfter(CountDownLatch start) throws Exception {
        start.await();
        return executeDrawing();
    }

    private long drawingId(MvcResult result) throws Exception {
        Number value = JsonPath.read(result.getResponse().getContentAsString(), "$.data.drawingId");
        return value.longValue();
    }

    private long drawingCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM drawing WHERE event_id = ? AND draw_no = 0",
                Long.class,
                eventId
        );
        return count == null ? 0L : count;
    }

    private Member saveMember(String name, String phone, String email, MemberRole role) {
        Member member = memberRepository.saveAndFlush(new Member(name, phone, email, role));
        memberIds.add(member.getMemberId());
        return member;
    }

    private EventEntry entry(Long memberId, long ticketCount) {
        return EventEntry.builder()
                .memberId(memberId)
                .eventId(eventId)
                .requestId(UUID.randomUUID().toString())
                .usedTicketCount(ticketCount)
                .appliedAt(Instant.parse("2026-09-14T00:00:00Z"))
                .build();
    }
}

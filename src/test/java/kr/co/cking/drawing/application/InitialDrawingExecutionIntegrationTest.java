package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawAttemptStatus;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.drawing.repository.DrawAttemptHistoryRepository;
import kr.co.cking.drawing.repository.DrawSeedRepository;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.snapshot.application.SnapshotHashV2Generator;
import kr.co.cking.snapshot.application.SnapshotHashV2Input;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.DrawSnapshot;
import kr.co.cking.snapshot.domain.PrizeValue;
import kr.co.cking.snapshot.repository.DrawSnapshotRepository;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.domain.WinnerManagementStatus;
import kr.co.cking.winner.repository.WinnerManagementRepository;
import kr.co.cking.winner.repository.WinnerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
class InitialDrawingExecutionIntegrationTest {

    @Autowired private InitialDrawingExecutionService service;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private DrawSnapshotRepository snapshotRepository;
    @MockitoSpyBean private DrawingRepository drawingRepository;
    @Autowired private DrawAttemptHistoryRepository attemptRepository;
    @Autowired private DrawSeedRepository seedRepository;
    @MockitoSpyBean private WinnerRepository winnerRepository;
    @MockitoSpyBean private WinnerManagementRepository winnerManagementRepository;
    @MockitoSpyBean private EventCommandService eventCommandService;
    @Autowired private SnapshotHashV2Generator snapshotHashV2Generator;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> memberIds = new ArrayList<>();
    private Long creatorId;
    private Long eventId;
    private Long snapshotId;
    private Long adminId;
    private long seedCountBefore;

    @BeforeEach
    void setUp() {
        Member admin = saveMember("추첨 관리자", MemberRole.ADMIN);
        adminId = admin.getMemberId();
        Member owner = saveMember("이벤트 크리에이터", MemberRole.USER);
        Creator creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), owner.getName()));
        creatorId = creator.getCreatorId();

        Member firstCandidate = saveMember("첫 번째 후보", MemberRole.USER);
        Member secondCandidate = saveMember("두 번째 후보", MemberRole.USER);
        Member thirdCandidate = saveMember("세 번째 후보", MemberRole.USER);
        Event event = eventRepository.saveAndFlush(Event.builder()
                .creatorId(creatorId)
                .requestId(UUID.randomUUID().toString())
                .title("INITIAL Drawing 통합 테스트")
                .startAt(Instant.parse("2026-09-01T00:00:00Z"))
                .endAt(Instant.parse("2026-09-02T00:00:00Z"))
                .winnerCount(2)
                .drawMethod(DrawMethod.WEIGHTED.name())
                .status(EventStatus.CLOSED)
                .createdBy(owner.getMemberId())
                .createdAt(Instant.parse("2026-09-01T00:00:00Z"))
                .build());
        eventId = event.getEventId();

        List<CandidateValue> candidates = List.of(
                new CandidateValue(firstCandidate.getMemberId(), 3L),
                new CandidateValue(secondCandidate.getMemberId(), 7L),
                new CandidateValue(thirdCandidate.getMemberId(), 5L)
        );
        List<PrizeValue> prizes = List.of(
                new PrizeValue("FIRST", "1등 상품", 1, 5, 1),
                new PrizeValue("SECOND", "2등 상품", 2, 95, 1));
        String snapshotHash = snapshotHashV2Generator.generate(new SnapshotHashV2Input(
                eventId, 2, DrawMethod.WEIGHTED.name(), "WEIGHTED_V1", "PRIZE_WEIGHTED_V1",
                candidates, prizes)).value();
        DrawSnapshot snapshot = snapshotRepository.saveAndFlush(DrawSnapshot.create(
                eventId, 2, DrawMethod.WEIGHTED.name(), "WEIGHTED_V1", "PRIZE_WEIGHTED_V1",
                snapshotHash, candidates, prizes));
        snapshotId = snapshot.getId();
        seedCountBefore = seedRepository.count();
    }

    @AfterEach
    void tearDown() {
        List<Long> seedIds = jdbcTemplate.queryForList(
                "SELECT seed_id FROM drawing WHERE event_id = ?", Long.class, eventId);
        jdbcTemplate.update("DELETE wm FROM winner_management wm JOIN winner w ON wm.winner_id = w.id WHERE w.event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM winner WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE h FROM draw_attempt_history h JOIN drawing d ON h.drawing_id = d.id WHERE d.event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM drawing WHERE event_id = ?", eventId);
        seedIds.forEach(seedRepository::deleteById);
        jdbcTemplate.update("DELETE FROM draw_snapshot_candidate WHERE snapshot_id = ?", snapshotId);
        jdbcTemplate.update("DELETE FROM draw_snapshot_prize WHERE snapshot_id = ?", snapshotId);
        snapshotRepository.deleteById(snapshotId);
        eventRepository.deleteById(eventId);
        creatorRepository.deleteById(creatorId);
        memberRepository.deleteAllById(memberIds);
    }

    @Test
    void INITIAL_Drawing과_Winner_Event_상태를_원자적으로_확정한다() {
        InitialDrawingResult result = service.execute(adminId, eventId);

        Drawing drawing = drawingRepository.findById(result.drawingId()).orElseThrow();
        List<Winner> winners = winnerRepository.findAllByDrawingIdOrderByRankInDrawingAsc(drawing.getId());

        assertThat(result.status()).isEqualTo(DrawingStatus.COMPLETED);
        assertThat(drawing.getStatus()).isEqualTo(DrawingStatus.COMPLETED);
        assertThat(drawing.getVisibility()).isEqualTo(DrawingVisibility.PRIVATE);
        assertThat(drawing.getDrawNo()).isZero();
        assertThat(drawing.getRequestedBy()).isEqualTo(adminId);
        assertThat(drawing.getInputPayload()).isNotBlank();
        assertThat(drawing.getInputHash()).matches("[0-9a-f]{64}");
        assertThat(drawing.getOutputPayload()).isNotBlank();
        assertThat(drawing.getResultHash()).matches("[0-9a-f]{64}");
        assertThat(drawing.getFirstStartedAt()).isNotNull();
        assertThat(drawing.getCompletedAt()).isNotNull();
        assertThat(drawing.getAttemptCount()).isEqualTo(1);
        assertThat(winners).hasSize(2);
        assertThat(winners).extracting(Winner::getRankInDrawing).containsExactly(1, 2);
        assertThat(winners).extracting(Winner::getMemberId).doesNotHaveDuplicates();
        assertThat(winners).allSatisfy(winner -> {
            assertThat(winner.getSnapshotId()).isEqualTo(snapshotId);
            assertThat(winner.getSnapshotPrizeId()).isPositive();
            assertThat(winner.getPrizeKey()).isNotBlank();
            assertThat(winner.getPrizeDisplayName()).isNotBlank();
            assertThat(winner.getPrizePriority()).isPositive();
        });
        assertThat(winners).allSatisfy(winner -> assertThat(
                winnerManagementRepository.findByWinnerId(winner.getId()).orElseThrow().getStatus())
                .isEqualTo(WinnerManagementStatus.SELECTED));
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(EventStatus.DRAW_COMPLETED);
        assertThat(seedRepository.existsById(drawing.getSeedId())).isTrue();
    }

    @Test
    void 완료된_Event를_재요청하면_기존_Drawing을_반환한다() {
        InitialDrawingResult first = service.execute(adminId, eventId);
        long seedCount = seedRepository.count();

        InitialDrawingResult replay = service.execute(adminId, eventId);

        assertThat(replay).isEqualTo(first);
        assertThat(drawingRepository.count()).isGreaterThanOrEqualTo(1L);
        assertThat(drawingRepository.findByEventIdAndDrawNo(eventId, 0)).hasValueSatisfying(drawing ->
                assertThat(drawing.getId()).isEqualTo(first.drawingId()));
        assertThat(seedRepository.count()).isEqualTo(seedCount);
    }

    @Test
    void Event_상태전이가_실패하면_부분결과를_Rollback하고_FAILED_Drawing과_Attempt를_보존한다() {
        doThrow(new RuntimeException("Event 전이 강제 실패"))
                .when(eventCommandService).completeDrawing(eventId);

        assertThatThrownBy(() -> service.execute(adminId, eventId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("강제 실패");

        assertFailedDrawingPreserved();
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.CLOSED);
    }

    @Test
    void Winner_저장이_실패하면_부분결과를_Rollback하고_FAILED_Drawing과_Attempt를_보존한다() {
        doThrow(new RuntimeException("Winner 저장 강제 실패"))
                .when(winnerRepository).saveAllAndFlush(any());

        assertThatThrownBy(() -> service.execute(adminId, eventId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("강제 실패");

        assertFailedDrawingPreserved();
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.CLOSED);
    }

    @Test
    void WinnerManagement_저장이_실패하면_부분결과를_Rollback하고_FAILED_Drawing과_Attempt를_보존한다() {
        doThrow(new RuntimeException("WinnerManagement 저장 강제 실패"))
                .when(winnerManagementRepository).saveAllAndFlush(any());

        assertThatThrownBy(() -> service.execute(adminId, eventId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("강제 실패");

        assertFailedDrawingPreserved();
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.CLOSED);
    }

    @Test
    void Drawing_완료_저장이_실패하면_부분결과를_Rollback하고_FAILED_Drawing과_Attempt를_보존한다() {
        doNothing().doThrow(new RuntimeException("Drawing 완료 저장 강제 실패"))
                .when(drawingRepository).flush();

        assertThatThrownBy(() -> service.execute(adminId, eventId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("강제 실패");

        assertFailedDrawingPreserved();
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.CLOSED);
    }

    @Test
    void 동시_INITIAL_요청도_하나의_Drawing과_같은_결과로_수렴한다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<InitialDrawingResult> first = executor.submit(() -> {
                start.await();
                return service.execute(adminId, eventId);
            });
            Future<InitialDrawingResult> second = executor.submit(() -> {
                start.await();
                return service.execute(adminId, eventId);
            });
            start.countDown();

            InitialDrawingResult firstResult = first.get();
            InitialDrawingResult secondResult = second.get();

            assertThat(secondResult).isEqualTo(firstResult);
            assertThat(drawingRepository.findByEventIdAndDrawNo(eventId, 0)).isPresent();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM drawing WHERE event_id = ? AND draw_no = 0",
                    Long.class,
                    eventId
            )).isEqualTo(1L);
        }
    }

    private Member saveMember(String name, MemberRole role) {
        Member member = memberRepository.saveAndFlush(new Member(name, null, null, role));
        memberIds.add(member.getMemberId());
        return member;
    }

    private void assertFailedDrawingPreserved() {
        Drawing drawing = drawingRepository.findByEventIdAndDrawNo(eventId, 0).orElseThrow();
        assertThat(drawing.getStatus()).isEqualTo(DrawingStatus.FAILED);
        assertThat(drawing.getAttemptCount()).isEqualTo(1);
        assertThat(drawing.getInputPayload()).isNotBlank();
        assertThat(drawing.getInputHash()).matches("[0-9a-f]{64}");
        assertThat(seedRepository.existsById(drawing.getSeedId())).isTrue();
        assertThat(attemptRepository.findByDrawingIdAndAttemptNo(drawing.getId(), 1))
                .hasValueSatisfying(attempt -> {
                    assertThat(attempt.getStatus()).isEqualTo(DrawAttemptStatus.FAILED);
                    assertThat(attempt.getFailureStage()).isNotNull();
                    assertThat(attempt.getFailureCode()).isNotBlank();
                    assertThat(attempt.getFailureMessage()).isNotBlank();
                    assertThat(attempt.getFinishedAt()).isNotNull();
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM winner WHERE event_id = ?", Long.class, eventId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM winner_management wm
                JOIN winner w ON w.id = wm.winner_id
                WHERE w.event_id = ?
                """, Long.class, eventId)).isZero();
        assertThat(seedRepository.count()).isEqualTo(seedCountBefore + 1);
    }
}

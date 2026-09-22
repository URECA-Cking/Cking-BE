package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingVerificationStatus;
import kr.co.cking.drawing.repository.DrawSeedRepository;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.drawing.repository.DrawingVerificationHistoryRepository;
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
import kr.co.cking.winner.repository.WinnerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class DrawingVerificationIntegrationTest {

    @Autowired private InitialDrawingExecutionService drawingExecutionService;
    @Autowired private DrawingVerificationService verificationService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private DrawSnapshotRepository snapshotRepository;
    @Autowired private DrawingRepository drawingRepository;
    @Autowired private WinnerRepository winnerRepository;
    @Autowired private DrawSeedRepository seedRepository;
    @Autowired private DrawingVerificationHistoryRepository historyRepository;
    @Autowired private SnapshotHashV2Generator snapshotHashGenerator;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> memberIds = new ArrayList<>();
    private Long creatorId;
    private Long eventId;
    private Long snapshotId;
    private Long adminId;
    private Long drawingId;

    @BeforeEach
    void setUp() {
        Member admin = saveMember("재현 검증 관리자", MemberRole.ADMIN);
        adminId = admin.getMemberId();
        Member owner = saveMember("재현 검증 크리에이터", MemberRole.USER);
        Creator creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), owner.getName()));
        creatorId = creator.getCreatorId();

        List<CandidateValue> candidates = LongStream.rangeClosed(1, 12)
                .mapToObj(index -> {
                    Member candidate = saveMember("재현 후보 " + index, MemberRole.USER);
                    return new CandidateValue(candidate.getMemberId(), index);
                })
                .toList();
        Event event = eventRepository.saveAndFlush(Event.builder()
                .creatorId(creatorId)
                .requestId(UUID.randomUUID().toString())
                .title("당첨 인원 수 재현 검증")
                .startAt(Instant.parse("2026-09-01T00:00:00Z"))
                .endAt(Instant.parse("2026-09-02T00:00:00Z"))
                .winnerCount(10)
                .drawMethod(DrawMethod.WEIGHTED.name())
                .status(EventStatus.CLOSED)
                .createdBy(owner.getMemberId())
                .createdAt(Instant.parse("2026-09-01T00:00:00Z"))
                .build());
        eventId = event.getEventId();

        List<PrizeValue> prizes = List.of(
                new PrizeValue("FIRST", "1등 상품", 1, 5, 2),
                new PrizeValue("SECOND", "2등 상품", 2, 95, 8)
        );
        String snapshotHash = snapshotHashGenerator.generate(new SnapshotHashV2Input(
                eventId, 10, DrawMethod.WEIGHTED.name(), "WEIGHTED_V1", "PRIZE_WEIGHTED_V1",
                candidates, prizes)).value();
        DrawSnapshot snapshot = snapshotRepository.saveAndFlush(DrawSnapshot.create(
                eventId, 10, DrawMethod.WEIGHTED.name(), "WEIGHTED_V1", "PRIZE_WEIGHTED_V1",
                snapshotHash, candidates, prizes));
        snapshotId = snapshot.getId();
        drawingId = drawingExecutionService.execute(adminId, eventId).drawingId();
    }

    @AfterEach
    void tearDown() {
        if (drawingId != null) {
            jdbcTemplate.update("DELETE FROM draw_verification_history WHERE drawing_id = ?", drawingId);
        }
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
    void 독립_재실행은_정확한_인원만_검증하고_원본_Drawing과_Winner를_변경하지_않는다() {
        Drawing before = drawingRepository.findById(drawingId).orElseThrow();
        List<String> winnersBefore = winnerRepository
                .findAllByDrawingIdOrderByRankInDrawingAsc(drawingId)
                .stream()
                .map(winner -> winner.getId() + ":" + winner.getPrizeKey())
                .toList();
        long seedCountBefore = seedRepository.count();

        DrawingVerificationResult first = verificationService.verify(drawingId, adminId);
        DrawingVerificationResult second = verificationService.verify(drawingId, adminId);

        Drawing after = drawingRepository.findById(drawingId).orElseThrow();
        List<String> winnersAfter = winnerRepository
                .findAllByDrawingIdOrderByRankInDrawingAsc(drawingId)
                .stream()
                .map(winner -> winner.getId() + ":" + winner.getPrizeKey())
                .toList();
        assertThat(first.status()).isEqualTo(DrawingVerificationStatus.VERIFIED);
        assertThat(first.expectedWinnerCount()).isEqualTo(10);
        assertThat(first.actualWinnerCount()).isEqualTo(10);
        assertThat(second.status()).isEqualTo(DrawingVerificationStatus.VERIFIED);
        assertThat(historyRepository.countByDrawingId(drawingId)).isEqualTo(2);
        assertThat(seedRepository.count()).isEqualTo(seedCountBefore);
        assertThat(after.getInputHash()).isEqualTo(before.getInputHash());
        assertThat(after.getResultHash()).isEqualTo(before.getResultHash());
        assertThat(winnersAfter).containsExactlyElementsOf(winnersBefore);
    }

    private Member saveMember(String name, MemberRole role) {
        Member member = memberRepository.saveAndFlush(new Member(name, null, null, role));
        memberIds.add(member.getMemberId());
        return member;
    }
}
